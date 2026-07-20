/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 视觉管理器（多人脸交互升级版）
 * 1. 基于双眼关键点实现真实视线跟随，不再依赖人脸框
 * 2. 均值滤波防抖，眼球移动沉稳无抖动
 * 3. 人脸短暂丢失滞留防抖，交互更自然
 * 4. 远近人脸兼容更好，侧脸容错提升
 * 5. 保留休眠唤醒、首次见面问候原有业务逻辑
 * 6. ML Kit 人脸分类：微笑/眨眼/惊讶 → 机器人表情镜像互动
 *    - 用户微笑 → 机器人开心表情
 *    - 用户眨眼 → 机器人同步眨眼（镜像效应）
 *    - 用户惊讶 → 机器人疑惑表情
 * 7. 【新增】多人脸交互逻辑
 *    - 2张人脸：眼珠左右往复缓慢扫视，模拟倾听两人对话
 *    - 3张及以上人脸：触发疑惑类眼部表情，呈现忙不过来的状态
 *    - 距离感知：人走近瞳孔微缩（对焦感），人走远瞳孔微扩
 */
class VisionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VisionManager-Mid"
        private const val NO_FACE_SLEEP_DELAY = 9000L       // 无人脸9秒自动休眠
        private const val FACE_LOST_STAY_TIME = 500L        // 人脸丢失滞留500ms再复位眼睛
        private const val SMOOTH_FILTER_COUNT = 5           // 5帧均值平滑滤波

        // 正常模式：每2帧检测1次（约15fps），平衡精度与性能
        private const val NORMAL_FRAME_SKIP = 1
        // 休眠低功耗模式：每6帧检测1次（约5fps），省电但仍能自动唤醒
        private const val SLEEP_FRAME_SKIP = 5

        // ========== 表情分类检测阈值 ==========
        private const val SMILE_THRESHOLD = 0.85f           // 微笑概率阈值（提高到0.85，必须明显微笑才触发）
        private const val SMILE_FRAME_COUNT = 5             // 连续5帧命中才触发（防抖更严格）
        private const val SMILE_COOLDOWN_MS = 2500L         // 微笑触发冷却时间2.5秒，避免频繁触发

        private const val EYE_CLOSED_THRESHOLD = 0.4f       // 眼睛闭合阈值，低于即判定闭眼
        private const val BLINK_MIN_DURATION_MS = 80L       // 眨眼最短持续80ms，过滤瞬时抖动
        private const val BLINK_MAX_DURATION_MS = 500L      // 眨眼最长持续500ms，超过不算眨眼
        private const val BLINK_COOLDOWN_MS = 300L          // 眨眼触发冷却300ms

        private const val SURPRISE_EYE_OPEN_THRESHOLD = 0.95f // 惊讶：必须瞪大眼睛（接近完全睁开）
        private const val SURPRISE_DELTA_THRESHOLD = 0.25f    // 短时间内突变幅度必须足够大（突然睁大）
        private const val SURPRISE_WINDOW_MS = 250L           // 突变检测时间窗口250ms（更短时间内的突变）
        private const val SURPRISE_COOLDOWN_MS = 3000L        // 惊讶触发冷却3秒
        private const val EXPRESSION_FILTER_COUNT = 5         // 表情概率5帧均值滤波（更稳定）

        // ========== 多人脸交互参数 ==========
        // 双人扫视：左右往复缓慢扫视，模拟倾听两人对话
        private const val TWO_FACE_SCAN_PERIOD_MS = 3000L     // 双人扫视完整周期3秒（一个来回）
        private const val TWO_FACE_SCAN_AMPLITUDE = 0.7f      // 扫视幅度系数（相对两人位置）
        private const val TWO_FACE_SCAN_SMOOTH = 0.15f        // 扫视平滑系数

        // 多人疑惑：3人及以上触发疑惑表情
        private const val MULTI_FACE_THRESHOLD = 3            // 3张及以上人脸触发疑惑
        private const val MULTI_FACE_COOLDOWN_MS = 3000L      // 多人疑惑触发冷却3秒，避免频繁切换

        // 距离感知瞳孔缩放：人走近缩小（对焦），人走远放大（放松）
        private const val DISTANCE_PUPIL_MIN_SCALE = 0.85f    // 最近距离瞳孔缩放下限
        private const val DISTANCE_PUPIL_MAX_SCALE = 1.15f    // 最远距离瞳孔放大上限
        private const val DISTANCE_REFERENCE_SIZE = 0.25f     // 基准人脸尺寸（屏幕宽度占比）
        private const val DISTANCE_SMOOTH_FACTOR = 0.1f       // 距离变化平滑系数（低通滤波）

        @Volatile
        private var instance: VisionManager? = null

        fun getInstance(context: Context): VisionManager {
            return instance ?: synchronized(this) {
                instance ?: VisionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var isDetecting = false
    private var isStopping = false  // 停止中标记，防止启停冲突
    private var hasFace = false
    private var hasTriggeredGreeting = false
    private var isSleepMode = false  // 是否处于休眠低功耗检测模式

    // 跳帧计数器
    private var frameSkipCounter = 0

    // 平滑滤波缓存队列
    private val offsetXBuffer = ArrayDeque<Float>(SMOOTH_FILTER_COUNT)
    private val offsetYBuffer = ArrayDeque<Float>(SMOOTH_FILTER_COUNT)

    // ========== 表情分类状态 ==========
    // 微笑检测
    private var smileConsecutiveCount = 0
    private var lastSmileTriggerTime = 0L
    private val smileProbBuffer = ArrayDeque<Float>(EXPRESSION_FILTER_COUNT)

    // 眨眼检测（边沿检测：睁眼→闭眼→睁眼）
    private var isEyeClosed = false
    private var eyeClosedStartTime = 0L
    private var lastBlinkTriggerTime = 0L
    private val leftEyeOpenBuffer = ArrayDeque<Float>(EXPRESSION_FILTER_COUNT)
    private val rightEyeOpenBuffer = ArrayDeque<Float>(EXPRESSION_FILTER_COUNT)

    // 惊讶检测（眼睑开合度突变）
    private var lastEyeOpenValue = 0.8f
    private var lastSurpriseTriggerTime = 0L
    private var eyeOpenHistory = ArrayDeque<Pair<Long, Float>>() // 时间戳+开合度

    // ========== 多人脸交互状态 ==========
    // 当前检测到的人脸数量
    private var currentFaceCount = 0

    // 双人扫视状态
    private var twoFaceScanPhase = 0f      // 扫视相位 0~1
    private var twoFaceLeftX = -0.5f       // 左侧人脸归一化X坐标
    private var twoFaceRightX = 0.5f       // 右侧人脸归一化X坐标
    private var lastTwoFaceScanTime = 0L   // 上次扫视更新时间

    // 多人疑惑状态
    private var lastMultiFaceTriggerTime = 0L

    // 距离感知瞳孔缩放
    private var smoothedDistanceScale = 1f  // 平滑后的距离缩放系数

    // 人脸丢失滞留计时
    private var faceLostStayJob: Job? = null
    private var sleepTimeoutJob: Job? = null

    interface VisionStateCallback {
        fun onStart() {}
        fun onFaceDetected(faceOffsetX: Float, faceOffsetY: Float, faceSize: Float) {}
        fun onFaceLost() {}
        fun onTimeout() {}
        fun onError(errorMsg: String) {}
        fun onStop() {}
        // 表情镜像互动回调
        fun onUserSmile(smileProb: Float) {}
        fun onUserBlink() {}
        fun onUserSurprised() {}
    }

    private var stateCallback: VisionStateCallback? = null

    // 中端配置：精准模式 + 全关键点 + 全分类（微笑/睁眼检测）+ 远距离人脸
    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    fun init() {
        Log.d(TAG, "VisionManager 表情镜像互动版初始化完成")
        clearSmoothBuffer()
        clearExpressionBuffer()
    }

    private fun clearSmoothBuffer() {
        offsetXBuffer.clear()
        offsetYBuffer.clear()
    }

    private fun clearExpressionBuffer() {
        smileProbBuffer.clear()
        leftEyeOpenBuffer.clear()
        rightEyeOpenBuffer.clear()
        eyeOpenHistory.clear()
        smileConsecutiveCount = 0
        isEyeClosed = false
        lastEyeOpenValue = 0.8f
        // 重置多人脸状态
        currentFaceCount = 0
        twoFaceScanPhase = 0f
        lastTwoFaceScanTime = 0L
        lastMultiFaceTriggerTime = 0L
        smoothedDistanceScale = 1f
    }

    fun startFaceDetect(lifecycleOwner: LifecycleOwner, callback: VisionStateCallback? = null) {
        if (isDetecting || isStopping) {
            Log.w(TAG, "人脸检测已在运行或正在停止，跳过启动 isDetecting=$isDetecting, isStopping=$isStopping")
            return
        }
        this.stateCallback = callback
        isDetecting = true
        hasFace = false
        hasTriggeredGreeting = false
        isSleepMode = false  // 启动时默认正常模式
        clearSmoothBuffer()
        clearExpressionBuffer()

        stateCallback?.onStart()
        Log.d(TAG, "启动中端精准人脸视线跟随")

        // 启动前先确保旧线程池已彻底关闭
        analysisExecutor?.shutdownNow()
        analysisExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                // 先彻底解绑所有之前的用例，确保干净状态
                provider.unbindAll()

                val preview = Preview.Builder().build()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor!!) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                resetSleepTimer()
                Log.d(TAG, "相机绑定成功，人脸检测启动完成")
            } catch (e: Exception) {
                Log.e(TAG, "启动相机失败", e)
                isDetecting = false
                stateCallback?.onError(e.message ?: "相机异常")
                stateCallback?.onStop()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        frameSkipCounter++
        val currentSkip = if (isSleepMode) SLEEP_FRAME_SKIP else NORMAL_FRAME_SKIP
        if (frameSkipCounter < currentSkip) {
            imageProxy.close()
            return
        }
        frameSkipCounter = 0

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    cancelFaceLostStayJob()
                    // 多人脸统一处理
                    handleMultiFaces(faces, mediaImage.width.toFloat(), mediaImage.height.toFloat())
                } else {
                    handleFaceAbsent()
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "人脸检测异常", it)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleFacePresent(face: Face, imgW: Float, imgH: Float) {
        // 优先取双眼关键点计算视线中心
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)

        val eyeCenterX: Float
        val eyeCenterY: Float

        if (leftEye != null && rightEye != null) {
            eyeCenterX = (leftEye.position.x + rightEye.position.x) / 2f
            eyeCenterY = (leftEye.position.y + rightEye.position.y) / 2f
        } else {
            // 无关键点回退人脸框中心
            val bounds = face.boundingBox
            eyeCenterX = bounds.centerX().toFloat()
            eyeCenterY = bounds.centerY().toFloat()
        }

        // 归一化 + 前置摄像头镜像翻转
        var rawOffsetX = (eyeCenterX - imgW / 2f) / (imgW / 2f)
        rawOffsetX = -rawOffsetX.coerceIn(-1f, 1f)
        val rawOffsetY = ((eyeCenterY - imgH / 2f) / (imgH / 2f)).coerceIn(-1f, 1f)

        // 加入平滑滤波
        val smoothX = getSmoothValue(offsetXBuffer, rawOffsetX)
        val smoothY = getSmoothValue(offsetYBuffer, rawOffsetY)
        val faceSize = (face.boundingBox.width().toFloat() / imgW).coerceIn(0f, 1f)

        if (!hasFace) {
            hasFace = true
            cancelSleepTimer()

            // 休眠模式下检测到人脸 → 立即唤醒，优先响应人脸识别反馈
            if (isSleepMode) {
                isSleepMode = false
                mainHandler.post {
                    // 立即终止睡眠状态，快速睁眼
                    ExpressionManager.getInstance().wakeFromSleep()
                    // 立即切回IDLE，不延迟，让后续高优先级反馈表情（问候/微笑回应等）立即触发
                    ExpressionManager.getInstance().requestExpression(ExpressionType.IDLE, force = true)
                }
                Log.d(TAG, "检测到人脸 → 立即唤醒，优先响应人脸识别反馈")
            }

            // 有人出现时触发问候（从休眠唤醒或刚启动时）
            // 注：不做人脸识别/身份判断，仅表示"检测到有人来了"
            if (!hasTriggeredGreeting) {
                hasTriggeredGreeting = true
                mainHandler.post {
                    // 触发应答问候表情
                    ExpressionManager.getInstance().requestExpression(ExpressionType.RESPONSE, 100)
                }
                mainHandler.postDelayed({
                    ExpressionManager.getInstance().requestExpression(ExpressionType.IDLE)
                }, 1100)
                Log.d(TAG, "首次检测到人眼 -> 应答打招呼")
            }
        }

        // ========== 表情分类检测 & 镜像互动 ==========
        processFaceClassification(face)

        stateCallback?.onFaceDetected(smoothX, smoothY, faceSize)
    }

    // ========== 多人脸交互逻辑 ==========

    /**
     * 多人脸统一处理入口
     * 1. 第一张脸作为主脸，保留原有单人脸全部功能（视线跟随、表情分类、休眠唤醒等）
     * 2. 根据人脸数量叠加多人脸交互效果：2人扫视、3人+疑惑
     * 3. 距离感知瞳孔缩放（无论多少人均生效）
     */
    private fun handleMultiFaces(faces: List<Face>, imgW: Float, imgH: Float) {
        val faceCount = faces.size
        currentFaceCount = faceCount

        // 第一张脸作为主脸，执行原有单人脸逻辑（视线跟随、表情分类等）
        val primaryFace = faces[0]
        handleFacePresent(primaryFace, imgW, imgH)

        // 计算所有人脸的平均尺寸（用于距离感知）
        val avgFaceSize = faces.map { it.boundingBox.width().toFloat() / imgW }.average().toFloat()

        // 距离感知瞳孔缩放（所有人脸场景都生效）
        updatePupilSizeByDistance(avgFaceSize)

        // 根据人脸数量分发处理
        when {
            faceCount >= MULTI_FACE_THRESHOLD -> {
                // 3人及以上：触发疑惑表情
                handleMultipleFacesConfused()
            }
            faceCount == 2 -> {
                // 2人：眼珠左右往复扫视
                handleTwoFacesScan(faces, imgW)
            }
            else -> {
                // 单人：重置多人脸状态，保持原有视线跟随
                resetMultiFaceState()
            }
        }
    }

    /**
     * 双人场景：眼珠左右往复缓慢扫视
     * 模拟机器人在倾听两人对话，视线在两人之间缓慢移动
     * 使用正弦波驱动，平滑往复，不生硬跳转
     */
    private fun handleTwoFacesScan(faces: List<Face>, imgW: Float) {
        val now = System.currentTimeMillis()

        // 按X坐标排序，确定左右两个人脸
        val sortedFaces = faces.sortedBy { it.boundingBox.centerX() }
        val leftFace = sortedFaces[0]
        val rightFace = sortedFaces[1]

        // 计算两个人脸的归一化X坐标（镜像翻转，与视线跟随一致）
        val leftRawX = (leftFace.boundingBox.centerX() - imgW / 2f) / (imgW / 2f)
        val rightRawX = (rightFace.boundingBox.centerX() - imgW / 2f) / (imgW / 2f)
        val leftNormX = -leftRawX.coerceIn(-1f, 1f)
        val rightNormX = -rightRawX.coerceIn(-1f, 1f)

        // 平滑更新左右目标位置，避免人脸抖动导致扫视范围跳变
        twoFaceLeftX += (leftNormX - twoFaceLeftX) * TWO_FACE_SCAN_SMOOTH
        twoFaceRightX += (rightNormX - twoFaceRightX) * TWO_FACE_SCAN_SMOOTH

        // 更新扫视相位（基于时间差，保证速度稳定）
        val deltaTime = if (lastTwoFaceScanTime > 0) {
            (now - lastTwoFaceScanTime).toFloat() / TWO_FACE_SCAN_PERIOD_MS
        } else {
            0f
        }
        lastTwoFaceScanTime = now
        twoFaceScanPhase = (twoFaceScanPhase + deltaTime) % 1f

        // 正弦波驱动：0 → 1 → 0 往复移动
        // sin(0) = 0, sin(π) = 0, sin(π/2) = 1 → 一个完整周期来回
        val sinValue = Math.sin(twoFaceScanPhase * Math.PI * 2).toFloat() * 0.5f + 0.5f // 0~1

        // 在左右两个人脸位置之间插值
        val scanX = twoFaceLeftX + (twoFaceRightX - twoFaceLeftX) * sinValue

        // 叠加扫视偏移到瞳孔偏移上（乘幅度系数，不超出两人范围）
        val finalOffsetX = scanX * TWO_FACE_SCAN_AMPLITUDE

        // 更新瞳孔偏移（X轴在两人之间扫视，Y轴保持居中）
        mainHandler.post {
            ExpressionManager.getInstance().setPupilOffset(finalOffsetX, 0f)
        }
    }

    /**
     * 三人及以上场景：触发疑惑表情
     * 呈现"忙不过来"的状态，优先级3，不打断高优先级状态
     * 带冷却机制，避免人数波动时频繁切换
     */
    private fun handleMultipleFacesConfused() {
        val now = System.currentTimeMillis()

        // 冷却检查，避免频繁触发
        if (now - lastMultiFaceTriggerTime < MULTI_FACE_COOLDOWN_MS) {
            return
        }

        lastMultiFaceTriggerTime = now
        Log.d(TAG, "检测到 $currentFaceCount 张人脸 → 触发疑惑表情（忙不过来）")

        mainHandler.post {
            // CONFUSED 优先级3，自动回落，不会打断说话等高优先级状态
            ExpressionManager.getInstance().requestExpression(ExpressionType.CONFUSED)
        }
    }

    /**
     * 距离感知：根据人脸大小动态调整瞳孔缩放
     * 人脸越大（越近）→ 瞳孔越小（对焦收缩感）
     * 人脸越小（越远）→ 瞳孔越大（放松放大感）
     * 使用低通滤波平滑过渡，避免突变
     */
    private fun updatePupilSizeByDistance(faceSizeRatio: Float) {
        // 以基准尺寸为中点，线性映射到缩放范围
        // faceSizeRatio = DISTANCE_REFERENCE_SIZE → scale = 1.0（基准）
        // faceSizeRatio 越大（越近）→ scale 越小（瞳孔缩小）
        // faceSizeRatio 越小（越远）→ scale 越大（瞳孔放大）

        val normalizedDist = (faceSizeRatio - DISTANCE_REFERENCE_SIZE) / DISTANCE_REFERENCE_SIZE
        // 映射到缩放范围：距离变化 ±100% 对应缩放 ±15%
        val targetScale = 1f - normalizedDist * 0.15f

        // 低通滤波平滑
        smoothedDistanceScale += (targetScale - smoothedDistanceScale) * DISTANCE_SMOOTH_FACTOR

        // 钳位到有效范围
        val finalScale = smoothedDistanceScale.coerceIn(DISTANCE_PUPIL_MIN_SCALE, DISTANCE_PUPIL_MAX_SCALE)

        // 更新到 ExpressionManager
        mainHandler.post {
            ExpressionManager.getInstance().setDistancePupilScale(finalScale)
        }
    }

    /**
     * 重置多人脸状态（回到单人/无人脸场景时调用）
     */
    private fun resetMultiFaceState() {
        twoFaceScanPhase = 0f
        lastTwoFaceScanTime = 0L
        // 注意：不重置 smoothedDistanceScale，距离感知平滑过渡
    }

    // ========== 人脸表情分类检测 ==========

    /**
     * 处理ML Kit人脸分类结果，检测微笑、眨眼、惊讶三类表情
     * 所有检测均在分析线程执行，触发表情切换时切主线程
     */
    private fun processFaceClassification(face: Face) {
        val now = System.currentTimeMillis()

        // 获取分类概率（可能为null，需判空）
        val smileProb = face.smilingProbability ?: -1f
        val leftEyeOpen = face.leftEyeOpenProbability ?: -1f
        val rightEyeOpen = face.rightEyeOpenProbability ?: -1f

        // 概率值无效（侧脸/遮挡）时跳过检测
        if (smileProb < 0f || leftEyeOpen < 0f || rightEyeOpen < 0f) {
            return
        }

        // 3帧均值平滑滤波，消除单帧抖动
        val smoothSmile = getSmoothValue(smileProbBuffer, smileProb)
        val smoothLeftEye = getSmoothValue(leftEyeOpenBuffer, leftEyeOpen)
        val smoothRightEye = getSmoothValue(rightEyeOpenBuffer, rightEyeOpen)
        val avgEyeOpen = (smoothLeftEye + smoothRightEye) / 2f

        // 检测微笑和眨眼
        detectSmile(smoothSmile, now)
        detectBlink(smoothLeftEye, smoothRightEye, avgEyeOpen, now)
    }

    /**
     * 微笑检测：连续N帧超过阈值 → 触发开心表情
     * 防抖：连续帧确认，避免误触
     * 冷却：触发后2秒内不重复触发
     */
    private fun detectSmile(smileProb: Float, now: Long) {
        if (smileProb >= SMILE_THRESHOLD) {
            smileConsecutiveCount++
            if (smileConsecutiveCount >= SMILE_FRAME_COUNT
                && now - lastSmileTriggerTime > SMILE_COOLDOWN_MS
            ) {
                lastSmileTriggerTime = now
                smileConsecutiveCount = 0
                Log.d(TAG, "检测到用户微笑 (prob=%.2f) → 触发开心表情".format(smileProb))

                // 切主线程触发表情（优先级4，不会打断说话等高优先级状态）
                mainHandler.post {
                    ExpressionManager.getInstance().requestExpression(ExpressionType.HAPPY)
                }
                stateCallback?.onUserSmile(smileProb)
            }
        } else {
            // 低于阈值重置计数
            if (smileConsecutiveCount > 0) smileConsecutiveCount = 0
        }
    }

    /**
     * 眨眼检测：边沿检测（睁眼→闭眼→睁眼完整周期）
     * 镜像效应：用户眨眼 → 机器人同步眨眼一次
     * 过滤：持续时间80~500ms才算有效眨眼，排除长时间闭眼
     */
    private fun detectBlink(leftEyeOpen: Float, rightEyeOpen: Float, avgEyeOpen: Float, now: Long) {
        val bothClosed = leftEyeOpen < EYE_CLOSED_THRESHOLD && rightEyeOpen < EYE_CLOSED_THRESHOLD

        when {
            // 状态：睁眼 → 闭眼（下降沿）
            bothClosed && !isEyeClosed -> {
                isEyeClosed = true
                eyeClosedStartTime = now
            }
            // 状态：闭眼 → 睁眼（上升沿）→ 判定为一次眨眼
            !bothClosed && isEyeClosed -> {
                val closedDuration = now - eyeClosedStartTime
                isEyeClosed = false

                // 有效眨眼：持续时间在合理范围内 + 过了冷却期
                if (closedDuration in BLINK_MIN_DURATION_MS..BLINK_MAX_DURATION_MS
                    && now - lastBlinkTriggerTime > BLINK_COOLDOWN_MS
                ) {
                    lastBlinkTriggerTime = now
                    Log.d(TAG, "检测到用户眨眼 (duration=${closedDuration}ms) → 镜像眨眼")

                    mainHandler.post {
                        ExpressionManager.getInstance().requestExpression(ExpressionType.BLINK)
                    }
                    stateCallback?.onUserBlink()
                }
            }
        }
    }

    private fun handleFaceAbsent() {
        if (hasFace) {
            hasFace = false
            currentFaceCount = 0
            stateCallback?.onFaceLost()
            startFaceLostStayTimer()
            resetSleepTimer()
            // 无人脸时恢复瞳孔基准大小
            mainHandler.post {
                ExpressionManager.getInstance().setDistancePupilScale(1f)
            }
            resetMultiFaceState()
        }
    }

    // 均值平滑滤波
    private fun getSmoothValue(buffer: ArrayDeque<Float>, newValue: Float): Float {
        if (buffer.size >= SMOOTH_FILTER_COUNT) buffer.removeFirst()
        buffer.addLast(newValue)
        return buffer.average().toFloat()
    }

    // 人脸丢失滞留倒计时
    private fun startFaceLostStayTimer() {
        faceLostStayJob?.cancel()
        faceLostStayJob = scope.launch {
            delay(FACE_LOST_STAY_TIME)
            mainHandler.post {
                ExpressionManager.getInstance().setPupilOffset(0f, 0f)
                clearSmoothBuffer()
            }
        }
    }

    private fun cancelFaceLostStayJob() {
        faceLostStayJob?.cancel()
        faceLostStayJob = null
    }

    private fun resetSleepTimer() {
        sleepTimeoutJob?.cancel()
        sleepTimeoutJob = scope.launch {
            delay(NO_FACE_SLEEP_DELAY)
            if (isDetecting && !hasFace) {
                mainHandler.post {
                    // 进入休眠：重置问候标志，下次有人来再打招呼
                    isSleepMode = true
                    hasTriggeredGreeting = false
                    ExpressionManager.getInstance().requestExpression(ExpressionType.SLEEPING, force = true)
                    ExpressionManager.getInstance().enterSleepWithDream()
                    Log.d(TAG, "无人脸9秒 → 进入休眠模式（打哈欠→浅眠→深眠）")
                }
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepTimeoutJob?.cancel()
        sleepTimeoutJob = null
    }

    fun stopFaceDetect() {
        if (!isDetecting || isStopping) return
        isStopping = true  // 标记停止中，防止启停冲突
        isDetecting = false
        hasFace = false
        isSleepMode = false
        cancelSleepTimer()
        cancelFaceLostStayJob()
        clearSmoothBuffer()
        clearExpressionBuffer()

        mainHandler.post {
            try {
                // 先解绑所有用例，释放相机
                cameraProvider?.unbindAll()
                Log.d(TAG, "相机已解绑")
            } catch (e: Exception) {
                Log.e(TAG, "解绑相机失败", e)
            } finally {
                cameraProvider = null
                // 彻底关闭分析线程池，中断正在执行的任务
                analysisExecutor?.shutdownNow()
                analysisExecutor = null
                isStopping = false
                stateCallback?.onStop()
                Log.d(TAG, "已停止中端人脸视线检测，资源已释放")
            }
        }
    }

    fun isDetecting(): Boolean = isDetecting

    fun release() {
        stopFaceDetect()
        faceDetector.close()
        stateCallback = null
    }
}