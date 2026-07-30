/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 人脸识别器
 *
 * 职责：
 * 1. 管理TFLite特征提取器（MobileFaceNet 192维）
 * 2. 维护已注册人脸库（从FaceRegisterManager获取）
 * 3. 执行1:N余弦相似度匹配
 * 4. 分层阈值策略（确认/猜测/陌生人）
 * 5. 多帧确认机制：避免单帧误识别
 * 6. 增量更新：识别成功后微调存储的特征向量
 *
 * 分层阈值策略（参考方案文档）：
 * - sim >= 0.50：确认识别成功 → 直接叫名字，开心表情
 * - 0.40 <= sim < 0.50：猜测性识别 → "你是XX吗？"（后续可加确认对话）
 * - sim < 0.40：陌生人 → 不做反应，等用户自报姓名触发注册
 *
 * 增量更新策略：
 * - 每次确认识别成功且sim > 0.55时
 * - stored = 0.9 * stored + 0.1 * newFeature
 * - 适应轻微外观变化（戴眼镜、发型变化、光线变化）
 *
 * @param context Android Context
 * @param registerManager 已注册人脸管理器
 */
class FaceIdentifier(
    private val context: Context,
    private val registerManager: FaceRegisterManager
) {
    companion object {
        private const val TAG = "FaceIdentifier"

        // ========== 分层相似度阈值（MobileFaceNet 192维，ArcFace训练） ==========
        /** 确认识别阈值：>=此值直接认出用户 */
        const val THRESHOLD_CONFIRMED = 0.85f
        /** 猜测识别阈值：>=此值但<确认阈值，猜测身份 */
        const val THRESHOLD_TENTATIVE = 0.40f
        /** 陌生人阈值：<此值判定为陌生人 */
        const val THRESHOLD_STRANGER = 0.30f

        // ========== 多帧确认 ==========
        /** 连续多少帧识别成功才确认（防止单帧误识） */
        const val CONFIRM_FRAME_COUNT = 3
        /** 同一人识别冷却时间（毫秒）：确认后一段时间内不重复回调同一人，但允许识别其他人 */
        const val RECOGNITION_COOLDOWN_MS = 15000L

        // ========== 增量更新 ==========
        /** 增量更新阈值：相似度高于此值才更新（避免污染特征） */
        const val UPDATE_THRESHOLD = 0.55f
        /** 增量更新权重：新特征的权重（0.1 = 10%新特征 + 90%旧特征） */
        const val UPDATE_ALPHA = 0.1f
    }

    // 特征提取器：默认使用TFLite MobileFaceNet
    private var featureExtractor: FaceFeatureExtractor = TFLiteFaceEmbedder(context)

    // 协程作用域（Default线程，用于特征提取和比对）
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ========== 多帧确认状态 ==========
    private var consecutiveRecognizedId: String? = null
    private var consecutiveRecognizedCount = 0
    private var consecutiveTentativeId: String? = null
    private var consecutiveTentativeCount = 0
    private var consecutiveStrangerCount = 0
    @Volatile private var lastRecognizedProfileId: String? = null
    @Volatile private var lastRecognizeTime = 0L

    // ========== 识别置信度枚举 ==========
    enum class Confidence {
        CONFIRMED,   // 确认认出
        TENTATIVE,   // 猜测（需要确认）
        STRANGER     // 陌生人
    }

    // ========== 识别回调 ==========
    /**
     * 识别结果监听器
     */
    interface RecognitionListener {
        /** 确认识别到熟人（多帧确认后触发） */
        fun onRecognized(profile: FaceProfile, similarity: Float) {}
        /** 猜测性识别（低置信度，可能是某人） */
        fun onTentativeRecognition(profile: FaceProfile, similarity: Float) {}
        /** 识别到陌生人（多帧确认后触发） */
        fun onStrangerDetected(maxSimilarity: Float) {}
        /** 单次识别结果（每帧都会回调，用于UI反馈/调试） */
        fun onFrameResult(result: FaceRecognitionResult) {}
    }

    private var listener: RecognitionListener? = null

    fun setRecognitionListener(listener: RecognitionListener?) {
        this.listener = listener
    }

    @Volatile
    private var isInitialized = false

    /**
     * 初始化识别器（加载TFLite模型）
     * 同步执行模型加载，确保初始化返回时模型已就绪。
     * 这样 FaceRegisterManager 共享的 extractor 实例已处于可用状态。
     */
    fun initialize() {
        try {
            featureExtractor.initialize(context)
            isInitialized = featureExtractor.isReady()
            Log.i(TAG, "FaceIdentifier initialized with ${featureExtractor.javaClass.simpleName}, " +
                    "featureDim=${featureExtractor.featureDimension}, ready=$isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize feature extractor", e)
            isInitialized = false
        }
    }

    /**
     * 切换特征提取器（用于模型升级/AB测试/降级）
     */
    fun setFeatureExtractor(extractor: FaceFeatureExtractor) {
        featureExtractor.release()
        featureExtractor = extractor
        extractor.initialize(context)
        isInitialized = extractor.isReady()
        Log.i(TAG, "Feature extractor switched to ${extractor.javaClass.simpleName}, ready=$isInitialized")
    }

    /**
     * 执行人脸识别（异步，在后台线程执行）
     *
     * @param fullImage 完整摄像头帧Bitmap（已处理旋转和镜像）
     * @param face ML Kit检测到的Face对象
     */
    fun identifyAsync(fullImage: Bitmap, face: Face, srcWidth: Int = fullImage.width, srcHeight: Int = fullImage.height, rotation: Int = 0) {
        // 模型未就绪时跳过，避免无效请求堆积
        if (!isInitialized || !featureExtractor.isReady()) {
            safeRecycle(fullImage)
            return
        }
        // Bitmap已被回收，跳过（防止Native崩溃）
        if (fullImage.isRecycled) {
            return
        }
        scope.launch {
            try {
                val result = identify(fullImage, face, srcWidth, srcHeight, rotation)
                withContext(Dispatchers.Main) {
                    listener?.onFrameResult(result)
                    processResult(result)
                }
            } finally {
                // 识别完成，回收传入的Bitmap（VisionManager移交了所有权）
                safeRecycle(fullImage)
            }
        }
    }

    /**
     * 安全回收Bitmap（忽略异常）
     */
    private fun safeRecycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * 同步识别（在当前线程执行，测试/调试用）
     */
    fun identify(fullImage: Bitmap, face: Face, srcWidth: Int = fullImage.width, srcHeight: Int = fullImage.height, rotation: Int = 0): FaceRecognitionResult {
        if (!featureExtractor.isReady()) {
            return FaceRecognitionResult.Failed("Feature extractor not ready")
        }
        // 防止使用已回收的Bitmap导致Native崩溃
        if (fullImage.isRecycled || fullImage.width == 0 || fullImage.height == 0) {
            return FaceRecognitionResult.Failed("Invalid bitmap")
        }

        // 1. 人脸质量检查（角度/关键点）
        val quality = FaceAligner.evaluateFaceQuality(face, fullImage.width, fullImage.height)
        if (!quality.isAligned) {
            // 关键点不全，无法对齐，直接返回失败
            return FaceRecognitionResult.Failed("Face landmarks incomplete")
        }
        if (!quality.isGoodQuality) {
            Log.v(TAG, "Low quality face: yaw=%.1f, roll=%.1f, ratio=%.2f".format(
                quality.yawAngle, quality.rollAngle, quality.faceWidth.toFloat()/fullImage.width))
        }

        // 2. 提取特征（内部处理：裁剪→对齐→预处理→TFLite推理）
        // 注意：传入完整帧fullImage，因为face坐标是相对于完整帧的
        val feature = featureExtractor.extractFeature(fullImage, face, srcWidth, srcHeight, rotation, mirrorX = true)
            ?: return FaceRecognitionResult.Failed("Feature extraction failed")

        // 3. 和人脸库1:N比对
        val profiles = registerManager.getAllProfiles()
        if (profiles.isEmpty()) {
            return FaceRecognitionResult.Stranger(0f)
        }

        var bestMatch: FaceProfile? = null
        var bestSimilarity = -1f

        for (profile in profiles) {
            val profileFeature = profile.feature ?: continue
            val sim = featureExtractor.cosineSimilarity(feature, profileFeature)
            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestMatch = profile
            }
        }

        // 4. 根据分层阈值判断结果
        return when {
            bestMatch == null -> FaceRecognitionResult.Stranger(0f)
            bestSimilarity >= THRESHOLD_CONFIRMED ->
                FaceRecognitionResult.Recognized(bestMatch, bestSimilarity)
            bestSimilarity >= THRESHOLD_TENTATIVE ->
                FaceRecognitionResult.Uncertain(bestMatch, bestSimilarity)
            else ->
                FaceRecognitionResult.Stranger(bestSimilarity)
        }.also {
            // 保存本次特征用于增量更新（在processResult中使用）
            if (it is FaceRecognitionResult.Recognized) {
                lastExtractedFeature = feature
            }
        }
    }

    // 保存最近一次提取的特征，用于增量更新
    @Volatile
    private var lastExtractedFeature: FloatArray? = null

    /**
     * 处理单帧结果，做多帧确认
     */
    private fun processResult(result: FaceRecognitionResult) {
        when (result) {
            is FaceRecognitionResult.Recognized -> {
                val id = result.profile.id
                if (id == consecutiveRecognizedId) {
                    consecutiveRecognizedCount++
                } else {
                    consecutiveRecognizedId = id
                    consecutiveRecognizedCount = 1
                }
                consecutiveTentativeCount = 0
                consecutiveStrangerCount = 0

                // 连续N帧识别为同一人 → 确认识别
                if (consecutiveRecognizedCount >= CONFIRM_FRAME_COUNT) {
                    val now = System.currentTimeMillis()
                    val isSamePersonRecently = id == lastRecognizedProfileId &&
                            now - lastRecognizeTime < RECOGNITION_COOLDOWN_MS

                    lastRecognizedProfileId = id
                    lastRecognizeTime = now

                    if (!isSamePersonRecently) {
                        // 新人或冷却已过：更新特征、触发回调
                        result.profile.markRecognized()

                        // 增量更新特征向量（适应外观变化）
                        if (result.similarity >= UPDATE_THRESHOLD) {
                            updateFeatureEmbedding(result.profile, lastExtractedFeature)
                        }

                        registerManager.updateProfile(result.profile)
                        Log.i(TAG, "✅ Recognized: ${result.profile.name}, sim=%.3f".format(result.similarity))
                        listener?.onRecognized(result.profile, result.similarity)
                    } else {
                        // 同一人在冷却期内：静默跳过，不重复打招呼
                        Log.v(TAG, "Same person '${result.profile.name}' in cooldown, skip")
                    }
                    resetFrameCounters()
                }
            }
            is FaceRecognitionResult.Uncertain -> {
                // 猜测性识别
                val id = result.bestMatch?.id
                if (id == consecutiveTentativeId) {
                    consecutiveTentativeCount++
                } else {
                    consecutiveTentativeId = id
                    consecutiveTentativeCount = 1
                }

                // 连续更多帧猜测才触发（避免误触发）
                if (consecutiveTentativeCount >= CONFIRM_FRAME_COUNT * 2) {
                    result.bestMatch?.let { profile ->
                        Log.d(TAG, "🤔 Tentative: ${profile.name}, sim=%.3f".format(result.similarity))
                        listener?.onTentativeRecognition(profile, result.similarity)
                    }
                    resetFrameCounters()
                }
            }
            is FaceRecognitionResult.Stranger -> {
                consecutiveStrangerCount++
                consecutiveRecognizedId = null
                consecutiveRecognizedCount = 0
                consecutiveTentativeId = null
                consecutiveTentativeCount = 0

                // 连续N帧识别为陌生人 → 确认
                if (consecutiveStrangerCount >= CONFIRM_FRAME_COUNT * 2) {
                    Log.d(TAG, "❓ Stranger, maxSim=%.3f".format(result.maxSimilarity))
                    listener?.onStrangerDetected(result.maxSimilarity)
                    resetFrameCounters()
                }
            }
            is FaceRecognitionResult.Failed -> {
                // 提取失败，重置计数
                resetFrameCounters()
            }
        }
    }

    /**
     * 增量更新特征向量
     *
     * stored = (1-α) * stored + α * newFeature，然后L2归一化
     * 这样存储的特征会缓慢适应外观变化
     */
    private fun updateFeatureEmbedding(profile: FaceProfile, newFeature: FloatArray?) {
        val stored = profile.feature ?: return
        val newF = newFeature ?: return
        if (stored.size != newF.size) return

        try {
            for (i in stored.indices) {
                stored[i] = (1 - UPDATE_ALPHA) * stored[i] + UPDATE_ALPHA * newF[i]
            }
            // 重新L2归一化
            var norm = 0f
            for (v in stored) norm += v * v
            norm = kotlin.math.sqrt(norm)
            if (norm > 1e-6f) {
                for (i in stored.indices) stored[i] /= norm
            }
            Log.d(TAG, "Feature embedding updated for ${profile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Feature update failed", e)
        }
    }

    /**
     * 重置多帧确认计数器
     */
    private fun resetFrameCounters() {
        consecutiveRecognizedId = null
        consecutiveRecognizedCount = 0
        consecutiveTentativeId = null
        consecutiveTentativeCount = 0
        consecutiveStrangerCount = 0
        lastExtractedFeature = null
    }

    /**
     * 人脸丢失：清除最近识别记录，使此人重新出现时能被立即识别
     */
    fun onFaceLost() {
        lastRecognizedProfileId = null
        lastRecognizeTime = 0L
        resetFrameCounters()
    }

    /**
     * 重置识别状态（切换用户/注销时调用）
     */
    fun reset() {
        resetFrameCounters()
        lastRecognizedProfileId = null
        lastRecognizeTime = 0L
    }

    /**
     * 获取特征提取器（供FaceRegisterManager共享使用）
     */
    fun getFeatureExtractor(): FaceFeatureExtractor = featureExtractor

    /**
     * 释放资源
     */
    fun release() {
        featureExtractor.release()
        reset()
    }
}
