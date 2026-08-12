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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.roboparty.rpoexpression.communication.HeadControlManager
import com.roboparty.rpoexpression.config.ConfigManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 视觉管理器（性能优化版 - 人越少越精准，人越多越写意）
 *
 * 核心设计原则：
 * 1. 单人场景：每帧精确计算，高置信度跟踪，电机实时跟随
 * 2. 双人场景：0.5-1Hz低频校准人脸位置，时间驱动正弦插值（4-5秒周期），电机同步摆动
 * 3. 多人场景：0.5Hz低频校准人脸位置，大范围扫视路径（5-7秒周期），进入时语音播报
 * 4. 人数变化500ms防抖 + 800ms平滑过渡，避免电机跳变
 * 5. 有效人脸过滤：置信度<0.5 或 占屏比<0.05 的人脸直接丢弃
 * 6. 所有场景共用同一份ML Kit检测结果，不重复计算
 *
 * 保留原有功能：
 * - 基于双眼+嘴部三角区的自然注视点计算
 * - 5帧均值滤波防抖
 * - 人脸丢失500ms滞留
 * - 9秒无人脸自动休眠
 * - 微笑/眨眼/惊讶表情镜像
 * - 距离感知瞳孔缩放
 * - TCP+Protobuf发送给板端控制电机
 */
class VisionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VisionManager"

        // ========== 基础时序参数 ==========
        private const val NO_FACE_SLEEP_DELAY = 9000L       // 无人脸9秒自动休眠
        private const val FACE_LOST_STAY_TIME = 500L        // 人脸丢失滞留500ms再复位
        private const val SMOOTH_FILTER_COUNT = 5           // 5帧均值平滑滤波

        // 帧率控制：单人30fps精确跟踪，多人场景自动降频
        private const val NORMAL_FRAME_SKIP = 0             // 单人：每帧都检测（约30fps）
        private const val MULTI_FACE_FRAME_SKIP = 2         // 多人：每3帧检测1次（约10fps，降低CPU）
        private const val SLEEP_FRAME_SKIP = 5              // 休眠：每6帧检测1次（约5fps）

        // ========== 有效人脸过滤阈值 ==========
        private const val MIN_FACE_CONFIDENCE = 0.5f        // 人脸检测置信度阈值（ML Kit返回）
        private const val MIN_FACE_SIZE_RATIO = 0.05f       // 最小人脸占屏比（宽度/屏幕宽度），过滤远处小人脸

        // ========== 人数变化防抖与过渡 ==========
        private const val FACE_COUNT_DEBOUNCE_MS = 500L     // 人数变化防抖500ms，确认人数稳定才切换模式
        private const val MODE_TRANSITION_MS = 800L         // 模式切换平滑过渡800ms，电机不跳变

        // ========== 单人场景参数 ==========
        private const val SINGLE_FACE_CONFIDENCE = 0.9f     // 单人跟踪置信度（高，稳定跟踪）

        // ========== 双人场景参数（正弦交替） ==========
        private const val TWO_FACE_PERIOD_MIN_MS = 4000L    // 双人正弦周期最短4秒
        private const val TWO_FACE_PERIOD_MAX_MS = 5000L    // 双人正弦周期最长5秒
        private const val TWO_FACE_POS_CALIBRATE_MS = 1000L // 双人场景人脸位置校准间隔1秒（1Hz）
        private const val TWO_FACE_AMPLITUDE_X = 0.7f       // 双人水平摆动幅度（归一化坐标）
        private const val TWO_FACE_CONFIDENCE = 0.7f        // 双人跟踪置信度（中）

        // ========== 多人场景参数（大范围扫视） ==========
        private const val MULTI_FACE_THRESHOLD = 3          // 3人及以上进入多人扫视模式
        private const val MULTI_FACE_PERIOD_MIN_MS = 5000L  // 多人扫视周期最短5秒
        private const val MULTI_FACE_PERIOD_MAX_MS = 7000L  // 多人扫视周期最长7秒
        private const val MULTI_FACE_POS_CALIBRATE_MS = 2000L // 多人场景人脸位置校准间隔2秒（0.5Hz）
        private const val MULTI_FACE_SWEEP_AMPLITUDE = 0.85f // 多人扫视幅度（大范围）
        private const val MULTI_FACE_CONFIDENCE = 0.6f      // 多人跟踪置信度（较低，写意）

        // ========== 多人主导人脸检测（滞后区控制） ==========
        // 当一个人明显比其他人近（脸更大）时，退化单人跟踪跟随该人
        // 使用施密特触发器式双阈值，避免在边界处频繁切换
        private const val DOMINANT_FACE_RATIO_ENTER = 2.0f  // maxFace/secondFace >= 2.0 → 开始跟随主导人脸
        private const val DOMINANT_FACE_RATIO_EXIT = 1.5f   // maxFace/secondFace <= 1.5 → 停止跟随，瞳孔扫视
        // 1.5 < ratio < 2.0 → 滞后区，保持上一帧状态

        // ========== 表情分类检测阈值 ==========
        private const val SMILE_THRESHOLD = 0.95f   // 大幅提高阈值，ML Kit smilingProbability 容易误判
        private const val SMILE_FRAME_COUNT = 8     // 连续8帧才确认（约270ms），过滤瞬态表情
        private const val SMILE_COOLDOWN_MS = 5000L // 冷却5秒，减少频繁触发
        private const val EXPRESSION_FILTER_COUNT = 5

        // ========== 距离感知瞳孔缩放 ==========
        private const val DISTANCE_PUPIL_MIN_SCALE = 0.85f
        private const val DISTANCE_PUPIL_MAX_SCALE = 1.15f
        private const val DISTANCE_REFERENCE_SIZE = 0.25f
        private const val DISTANCE_SMOOTH_FACTOR = 0.1f

        // ========== trackingId 锁定超时 ==========
        private const val LOCKED_FACE_TIMEOUT_MS = 500L      // 锁定的人脸丢失超过 500ms 才重新选择

        // ========== 人脸识别降频 ==========
        private const val RECOGNITION_FRAME_INTERVAL_MS = 500L  // 识别帧间隔500ms（约2fps），降低CPU

        // ========== 多人场景语音回复集 ==========
        private val MULTI_FACE_GREETINGS = arrayOf(
            "哇，好多人呀！",
            "这么热闹呀！",
            "大家好呀！",
            "哇，来了好多朋友！",
            "嘿嘿，人好多好开心！",
            "哟，大家伙都在呢！"
        )

        @Volatile
        private var instance: VisionManager? = null

        fun getInstance(context: Context): VisionManager {
            return instance ?: synchronized(this) {
                instance ?: VisionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ========== 跟踪模式定义 ==========
    /**
     * 人脸跟踪模式枚举
     * 设计理念：人越少越精准，人越多越写意
     */
    private enum class TrackMode {
        NO_FACE,        // 无人脸：电机回中，瞳孔回中
        SINGLE,         // 单人：精确跟踪，每帧计算，电机实时跟随
        TWO_FACE,       // 双人：正弦交替注视，预计算路径插值，电机同步摆动
        MULTI_FACE      // 多人(3+)：大范围扫视，预计算路径，进入时语音播报
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 头部控制管理器（单例，TCP长连接）
    private val headControlManager = HeadControlManager.getInstance()

    // ========== 相机与检测状态 ==========
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var isDetecting = false
    private var isStopping = false
    private var hasFace = false
    private var hasTriggeredGreeting = false
    private var isSleepMode = false

    private var frameSkipCounter = 0

    // ========== 平滑滤波 ==========
    private val offsetXBuffer = ArrayDeque<Float>(SMOOTH_FILTER_COUNT)
    private val offsetYBuffer = ArrayDeque<Float>(SMOOTH_FILTER_COUNT)

    // ========== 表情分类状态 ==========
    private var smileConsecutiveCount = 0
    private var lastSmileTriggerTime = 0L
    private val smileProbBuffer = ArrayDeque<Float>(EXPRESSION_FILTER_COUNT)

    // ========== 跟踪模式状态机 ==========
    @Volatile private var currentMode = TrackMode.NO_FACE   // 当前生效的跟踪模式
    @Volatile private var pendingFaceCount = 0              // 待确认的人脸数量（防抖用）
    private var faceCountStableSince = 0L                   // 人脸数量稳定的起始时间
    private var lastSpokenMultiFaceTime = 0L                // 上次多人语音播报时间（避免重复播报）

    // 人脸识别帧控制
    private var lastRecognitionFrameTime = 0L               // 上次发送识别帧的时间
    private var lastDebugFrameTime = 0L                     // 上次更新调试画面的时间

    // 模式过渡状态
    private var isTransitioning = false                     // 是否正在模式过渡中
    private var transitionStartTime = 0L                    // 过渡开始时间
    private var transitionFromX = 0f                        // 过渡起始X
    private var transitionFromY = 0f                        // 过渡起始Y
    private var transitionToMode = TrackMode.NO_FACE        // 过渡目标模式
    private var transitionJob: Job? = null                  // 过渡协程Job，用于取消旧过渡

    // ========== 双人/多人场景：预计算路径与低频校准 ==========
    // 人脸位置缓存（低频更新，不每帧重算）
    private var cachedFacePositions = listOf<Pair<Float, Float>>()  // 缓存的人脸归一化坐标
    private var lastFaceCalibrateTime = 0L                          // 上次人脸位置校准时间

    // 双人正弦参数
    private var twoFacePeriodMs = TWO_FACE_PERIOD_MIN_MS +
            (Math.random() * (TWO_FACE_PERIOD_MAX_MS - TWO_FACE_PERIOD_MIN_MS)).toLong()
    private var twoFacePhaseStartTime = 0L                          // 双人正弦相位起始时间
    private var twoFaceCenterX = 0f                                 // 双人摆动中心X
    private var twoFaceCenterY = 0f                                 // 双人摆动中心Y

    // 多人扫视参数
    private var multiFacePeriodMs = MULTI_FACE_PERIOD_MIN_MS +
            (Math.random() * (MULTI_FACE_PERIOD_MAX_MS - MULTI_FACE_PERIOD_MIN_MS)).toLong()
    private var multiFaceSweepStartTime = 0L                        // 多人扫视起始时间
    private var multiFaceCenterX = 0f                               // 多人扫视中心X
    private var multiFaceCenterY = 0f                               // 多人扫视中心Y
    private var hasSpokenForThisMultiSession = false                // 当前多人会话是否已播报语音
    private var multiDominantTracking = false                       // 多人模式中是否正在跟随主导人脸

    // 距离感知
    private var smoothedDistanceScale = 1f

    // ========== trackingId 锁定（任务3.2） ==========
    // 单人模式下锁定主脸的 trackingId，防止旁边经过更大的脸导致目标切换
    private var lockedTrackingId: Int? = null          // 锁定的 trackingId
    private var lockedFaceLostTime: Long = 0L          // 锁定的人脸丢失时刻（0=未丢失）

    // ========== 协程Job ==========
    private var faceLostStayJob: Job? = null
    private var sleepTimeoutJob: Job? = null

    // ========== 丢脸滞留与缓慢回中 ==========
    private var lastStableMotorX = 0f
    private var lastStableMotorY = 0f
    @Volatile private var faceLostPending = false
    private var returnToCenterJob: Job? = null

    // ========== 电机单一发送器（15Hz + 死区 + 最大步长） ==========
    // 所有逻辑只写 pendingMotor*，由独立协程统一发送，避免 30fps 直追噪声
    @Volatile private var pendingMotorX = 0f
    @Volatile private var pendingMotorY = 0f
    @Volatile private var pendingMotorConfidence = 0f
    private var motorSenderJob: Job? = null
    private var lastSentMotorX = 0f
    private var lastSentMotorY = 0f
    private var lastSentMotorConfidence = 0f

    // 电机独立 One Euro Filter（与屏幕瞳孔的 5 帧均值完全独立）
    private val motorFilterX = OneEuroFilter()
    private val motorFilterY = OneEuroFilter()
    private var lastMotorFilterTime = 0L

    // ========== 连续稳定帧确认（采样-保持，防止单帧 outlier 驱动电机） ==========
    // 跟踪状态下：pendingMotor* 帧间变化 < 死区时累计稳定帧，连续 N 帧后更新 confirmedMotor*
    // 帧间变化 >= 死区时重置计数，confirmed 保持不变（移动过程中电机不动，停下后到位）
    private var confirmedMotorX = 0f
    private var confirmedMotorY = 0f
    private var lastPendingMotorX = 0f
    private var lastPendingMotorY = 0f
    private var stableFrameCount = 0
    private val STABLE_FRAME_THRESHOLD = 2  // 15Hz 下约 133ms，平衡响应与抗 outlier

    // ========== 回调接口 ==========
    interface VisionStateCallback {
        fun onStart() {}
        fun onFaceDetected(faceOffsetX: Float, faceOffsetY: Float, faceSize: Float) {}
        fun onFaceLost() {}
        fun onTimeout() {}
        fun onError(errorMsg: String) {}
        fun onStop() {}
        fun onUserSmile(smileProb: Float) {}
        /**
         * 请求语音播报（多人场景进入时触发）
         * 由上层（MainActivity）调用AudioManager播放，避免VisionManager直接依赖音频模块
         */
        fun onSpeakRequest(text: String) {}

        /**
         * 人脸识别帧回调（单人模式下，降频触发，约2fps）
         * 传递裁剪好的人脸Bitmap和ML Kit Face对象，供上层人脸识别模块使用
         *
         * @param faceBitmap 裁剪后的人脸Bitmap（带20%margin）
         * @param face ML Kit检测到的Face对象（含关键点）
         * @param fullBitmap 完整帧Bitmap（供注册时多帧采样使用）
         */
        fun onFaceFrameForRecognition(faceBitmap: Bitmap, face: Face, fullBitmap: Bitmap) {}
    }

    private var stateCallback: VisionStateCallback? = null

    // 人脸识别帧回调（独立于stateCallback，避免覆盖）
    private var recognitionFrameCallback: ((faceBitmap: Bitmap, face: Face, fullBitmap: Bitmap, srcWidth: Int, srcHeight: Int, rotation: Int) -> Unit)? = null

    /**
     * 设置人脸识别帧回调
     * 单人模式下约2fps触发，传递裁剪好的人脸Bitmap供识别模块使用
     */
    fun setRecognitionFrameCallback(callback: ((faceBitmap: Bitmap, face: Face, fullBitmap: Bitmap, srcWidth: Int, srcHeight: Int, rotation: Int) -> Unit)?) {
        this.recognitionFrameCallback = callback
    }

    // ML Kit人脸检测器：高精度模式
    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(MIN_FACE_SIZE_RATIO)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    fun init() {
        Log.d(TAG, "VisionManager 性能优化版初始化完成（单人精准/双人正弦/多人扫视）")
        resetAllState()
    }

    /**
     * 重置所有状态
     */
    private fun resetAllState() {
        clearSmoothBuffer()
        clearExpressionBuffer()
        currentMode = TrackMode.NO_FACE
        pendingFaceCount = 0
        faceCountStableSince = 0L
        isTransitioning = false
        transitionStartTime = 0L
        transitionJob?.cancel()
        transitionJob = null
        cachedFacePositions = emptyList()
        lastFaceCalibrateTime = 0L
        twoFacePhaseStartTime = 0L
        multiFaceSweepStartTime = 0L
        hasSpokenForThisMultiSession = false
        multiDominantTracking = false
        lockedTrackingId = null
        lockedFaceLostTime = 0L
        smoothedDistanceScale = 1f
        multiSmoothedX = 0f
        multiSmoothedY = 0f
        faceLostPending = false
        returnToCenterJob?.cancel()
        returnToCenterJob = null
        lastStableMotorX = 0f
        lastStableMotorY = 0f
        // 电机单一发送器状态重置
        pendingMotorX = 0f
        pendingMotorY = 0f
        pendingMotorConfidence = 0f
        lastSentMotorX = 0f
        lastSentMotorY = 0f
        lastSentMotorConfidence = 0f
        motorFilterX.reset()
        motorFilterY.reset()
        lastMotorFilterTime = 0L
        // 稳定帧确认状态重置
        confirmedMotorX = 0f
        confirmedMotorY = 0f
        lastPendingMotorX = 0f
        lastPendingMotorY = 0f
        stableFrameCount = 0
    }

    private fun clearSmoothBuffer() {
        offsetXBuffer.clear()
        offsetYBuffer.clear()
    }

    private fun clearExpressionBuffer() {
        smileProbBuffer.clear()
        smileConsecutiveCount = 0
    }

    /**
     * 启动人脸检测
     */
    fun startFaceDetect(lifecycleOwner: LifecycleOwner, callback: VisionStateCallback? = null) {
        if (isDetecting || isStopping) {
            Log.w(TAG, "人脸检测已在运行或正在停止，跳过")
            return
        }
        this.stateCallback = callback
        isDetecting = true
        hasFace = false
        hasTriggeredGreeting = false
        isSleepMode = false
        resetAllState()
        startMotorSenderLoop()

        stateCallback?.onStart()
        Log.d(TAG, "启动人脸检测（性能优化版）")

        analysisExecutor?.shutdownNow()
        analysisExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                // 注意：不需要Preview用例！我们自己从ImageAnalysis拿帧转Bitmap
                // 如果绑定了Preview但不设置SurfaceProvider，会导致预览乱渲染到其他View上（比如眼睛里显示摄像头画面）
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor!!) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                resetSleepTimer()
                Log.d(TAG, "相机绑定成功")
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
        // 根据当前模式动态调整跳帧率：多人场景降频节省CPU
        val currentSkip = when {
            isSleepMode -> SLEEP_FRAME_SKIP
            currentMode == TrackMode.MULTI_FACE || currentMode == TrackMode.TWO_FACE -> MULTI_FACE_FRAME_SKIP
            else -> NORMAL_FRAME_SKIP
        }
        if (frameSkipCounter < currentSkip) {
            imageProxy.close()
            return
        }
        frameSkipCounter = 0

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
        // ML Kit 返回的 Face 坐标是旋转后自然方向的，归一化时必须用旋转后的宽高
        val isRotated = rotation == 90 || rotation == 270
        val analysisW = if (isRotated) mediaImage.height.toFloat() else mediaImage.width.toFloat()
        val analysisH = if (isRotated) mediaImage.width.toFloat() else mediaImage.height.toFloat()

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                // 第一步：过滤有效人脸（置信度+大小）
                val validFaces = filterValidFaces(faces, analysisW)
                val now = System.currentTimeMillis()

                // 判断这一帧是否需要转Bitmap（调试需要 或 识别需要）
                val needDebugFrame = DebugVisionState.visible.value && (now - lastDebugFrameTime >= 100) // 调试10fps
                val needRecognitionFrame = validFaces.isNotEmpty() && (now - lastRecognitionFrameTime >= RECOGNITION_FRAME_INTERVAL_MS)
                val needBitmap = needDebugFrame || needRecognitionFrame

                // 如果需要转Bitmap，先在当前线程（Image还没close）快速拷贝NV21数据
                // 这样即使后面imageProxy.close()了，我们还有数据，不会崩溃
                var nv21Data: ByteArray? = null
                var imgWidth = 0
                var imgHeight = 0
                if (needBitmap) {
                    try {
                        val yBuffer = mediaImage.planes[0].buffer
                        val uBuffer = mediaImage.planes[1].buffer
                        val vBuffer = mediaImage.planes[2].buffer
                        val ySize = yBuffer.remaining()
                        val uSize = uBuffer.remaining()
                        val vSize = vBuffer.remaining()
                        nv21Data = ByteArray(ySize + uSize + vSize)
                        yBuffer.get(nv21Data, 0, ySize)
                        vBuffer.get(nv21Data, ySize, vSize)
                        uBuffer.get(nv21Data, ySize + vSize, uSize)
                        imgWidth = mediaImage.width
                        imgHeight = mediaImage.height
                    } catch (e: Exception) {
                        Log.e(TAG, "Copy NV21 data failed", e)
                        nv21Data = null
                    }
                }

                if (validFaces.isNotEmpty()) {
                    cancelFaceLostStayJob()
                    processFaces(validFaces, analysisW, analysisH)

                    // 调试：更新人脸信息（不依赖Bitmap，直接更新）
                    if (DebugVisionState.visible.value) {
                        DebugVisionState.updateFaceInfo(validFaces, analysisW, analysisH)
                    }

                    // 如果需要转Bitmap，异步转（复用同一份NV21数据，只转一次）
                    if (needBitmap && nv21Data != null) {
                        if (needDebugFrame) lastDebugFrameTime = now
                        if (needRecognitionFrame) lastRecognitionFrameTime = now
                        val primaryFace = validFaces[0]
                        // 多人场景也识别最大的那张脸（最靠近镜头的用户）
                        val wantRecognition = needRecognitionFrame

                        scope.launch {
                            var fullBitmap: Bitmap? = null
                            try {
                                // 只转一次Bitmap，调试和识别复用
                                fullBitmap = nv21ToBitmap(nv21Data, imgWidth, imgHeight, rotation)
                            } catch (e: Exception) {
                                Log.e(TAG, "NV21 to Bitmap failed", e)
                            }

                            if (fullBitmap != null && !fullBitmap.isRecycled) {
                                // ========== Bitmap 所有权管理（修复竞态崩溃） ==========
                                // 调试窗口和识别管线各自独立管理 Bitmap 生命周期，
                                // 避免异步识别协程使用已被回收的 Bitmap 导致 Native 崩溃。
                                val bitmapForRecognition: Bitmap? = if (wantRecognition) {
                                    if (needDebugFrame) {
                                        // 两者都需要：为识别创建独立副本，避免生命周期冲突
                                        try {
                                            fullBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Copy bitmap for recognition failed", e)
                                            null
                                        }
                                    } else {
                                        // 仅识别需要：直接移交所有权
                                        fullBitmap
                                    }
                                } else null

                                mainHandler.post {
                                    // 1. 调试窗口（拥有 fullBitmap，自动管理回收）
                                    if (needDebugFrame) {
                                        DebugVisionState.updateFrame(
                                            fullBitmap, validFaces.size,
                                            srcWidth = imgWidth,
                                            srcHeight = imgHeight,
                                            rotationDegrees = rotation,
                                            mirrorX = true
                                        )
                                    }

                                    // 2. 识别回调（拥有 bitmapForRecognition，异步处理完成后回收）
                                    if (bitmapForRecognition != null) {
                                        recognitionFrameCallback?.invoke(bitmapForRecognition, primaryFace, bitmapForRecognition, imgWidth, imgHeight, rotation)
                                    }


                                }
                            }

                            // 更新节流时间（必须在处理完帧后更新，否则节流失效）
                            if (needDebugFrame) lastDebugFrameTime = now
                            if (needRecognitionFrame) lastRecognitionFrameTime = now
                        }
                    }
                } else {
                    handleFaceAbsent()
                    // 不再立即发送 (0,0,0)：由丢脸滞留机制保持最后目标，500ms后缓慢回中
                    if (DebugVisionState.visible.value) {
                        DebugVisionState.updateFaceInfo(emptyList(), analysisW, analysisH)
                    }
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "人脸检测异常", it)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * 过滤有效人脸：
     * 1. 过滤质量分 < MIN_FACE_CONFIDENCE 的人脸
     * 2. 过滤占屏比 < 0.05 的远处小人脸
     */
    private fun filterValidFaces(faces: List<Face>, imgW: Float): List<Face> {
        return faces.filter { face ->
            // 1. 人脸质量分过滤（ML Kit 无直接 confidence 字段，综合评估）
            // - trackingId 存在：稳定跟踪的人脸，置信度高（+0.5）
            // - 关键点数量：检测到的关键点越多，质量越好（+0.3）
            // - 表情分类有效：微笑/睁眼概率有效（+0.2）
            var qualityScore = 0f
            if (face.trackingId != null) qualityScore += 0.5f
            val landmarkCount = listOf(
                FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE,
                FaceLandmark.MOUTH_BOTTOM, FaceLandmark.NOSE_BASE
            ).count { face.getLandmark(it) != null }
            qualityScore += landmarkCount / 4f * 0.3f
            if (face.smilingProbability != null && face.leftEyeOpenProbability != null) qualityScore += 0.2f

            // 2. 占屏比过滤
            val faceWidth = face.boundingBox.width().toFloat()
            val sizeRatio = faceWidth / imgW

            qualityScore >= MIN_FACE_CONFIDENCE && sizeRatio >= MIN_FACE_SIZE_RATIO
        }.sortedByDescending { it.boundingBox.width() } // 按人脸大小排序，大脸优先
    }

    /**
     * 处理人脸（统一入口）
     * 包含：人数防抖、模式切换、各模式逻辑分发
     */
    private fun processFaces(faces: List<Face>, imgW: Float, imgH: Float) {
        val now = System.currentTimeMillis()
        val faceCount = faces.size

        // 从丢脸滞留/回中过程中恢复：立即取消回中与滞留定时器，直接跟踪（跳过人数防抖等待）
        val recoveringFromLoss = faceLostPending || returnToCenterJob != null
        if (recoveringFromLoss) {
            faceLostPending = false
            cancelFaceLostStayJob()
            returnToCenterJob?.cancel()
            returnToCenterJob = null
            pendingFaceCount = faceCount
            faceCountStableSince = now - FACE_COUNT_DEBOUNCE_MS - 1
        }

        // ========== 第一步：人数变化500ms防抖 ==========
        if (faceCount != pendingFaceCount) {
            pendingFaceCount = faceCount
            faceCountStableSince = now
            Log.d(TAG, "检测到人数变化：$faceCount 人，等待 ${FACE_COUNT_DEBOUNCE_MS}ms 确认")
            return // 等防抖确认后再处理
        }

        // 人数还没稳定，继续等待
        if (now - faceCountStableSince < FACE_COUNT_DEBOUNCE_MS) {
            return
        }

        // ========== 第二步：确定目标模式 ==========
        val targetMode = when {
            faceCount >= MULTI_FACE_THRESHOLD -> TrackMode.MULTI_FACE
            faceCount == 2 -> TrackMode.TWO_FACE
            faceCount == 1 -> TrackMode.SINGLE
            else -> TrackMode.NO_FACE
        }

        // ========== 第三步：模式切换处理（含800ms平滑过渡） ==========
        if (targetMode != currentMode && !isTransitioning) {
            startModeTransition(targetMode, faces, imgW, imgH, now)
            return
        }

        // ========== 第四步：选择主脸（trackingId 锁定 or 最大脸） ==========
        val primaryFace = selectPrimaryFace(faces, now)

        // ========== 第五步：公共处理（唤醒、问候、表情镜像） ==========
        processFaceCommon(primaryFace)

        // 距离感知（平均人脸大小）
        val avgFaceSize = faces.map { it.boundingBox.width().toFloat() / imgW }.average().toFloat()
        updatePupilSizeByDistance(avgFaceSize)

        // 过渡期间：过渡协程是唯一电机/瞳孔指令源，普通帧不发送位置命令，避免锯齿振荡
        if (isTransitioning) {
            return
        }

        // ========== 第六步：按模式分发处理 ==========
        when (currentMode) {
            TrackMode.SINGLE -> handleSingleFaceMode(primaryFace, imgW, imgH)
            TrackMode.TWO_FACE -> handleTwoFaceMode(faces, imgW, imgH, now)
            TrackMode.MULTI_FACE -> handleMultiFaceMode(faces, imgW, imgH, now)
            TrackMode.NO_FACE -> {
                setMotorTarget(0f, 0f, 0f)
                setPupilImmediate(0f, 0f)
            }
        }
    }

    /**
     * 选择主脸（任务3.2：trackingId 锁定）
     *
     * 单人模式：优先选择 trackingId 匹配的人脸，防止旁边经过更大的脸导致目标切换
     * 多人模式：保持最大脸策略（不锁定）
     *
     * 锁定规则：
     * - 首次进入 SINGLE 模式时记录主脸 trackingId
     * - 后续帧优先选择 trackingId 匹配的人脸
     * - 锁定的人脸丢失超过 500ms 后才重新选择最大脸
     */
    private fun selectPrimaryFace(faces: List<Face>, now: Long): Face {
        // faces 已按 boundingBox.width() 降序排列
        val maxFace = faces[0]

        // 多人模式下不锁定，始终用最大脸
        if (faces.size >= 2) {
            // 如果之前锁定了某个人但现在进入多人场景，清除锁定
            if (lockedTrackingId != null) {
                lockedTrackingId = null
                lockedFaceLostTime = 0L
            }
            return maxFace
        }

        // ========== 单人模式：trackingId 锁定逻辑 ==========
        val lockedId = lockedTrackingId

        if (lockedId != null) {
            // 尝试在当前帧中找到锁定的人脸
            val lockedFace = faces.find { it.trackingId == lockedId }

            if (lockedFace != null) {
                // 找到锁定的人脸，重置丢失计时
                lockedFaceLostTime = 0L
                return lockedFace
            } else {
                // 锁定的人脸在本帧丢失
                if (lockedFaceLostTime == 0L) {
                    lockedFaceLostTime = now
                    Log.d(TAG, "锁定人脸 $lockedId 丢失，等待 ${LOCKED_FACE_TIMEOUT_MS}ms")
                }

                // 在超时时间内，保持锁定（不切换），返回当前最大脸作为临时跟踪目标
                if (now - lockedFaceLostTime < LOCKED_FACE_TIMEOUT_MS) {
                    return maxFace
                } else {
                    // 超过 500ms，放弃锁定，重新选择最大脸
                    Log.d(TAG, "锁定人脸 $lockedId 超时，重新选择主脸")
                    lockedTrackingId = maxFace.trackingId
                    lockedFaceLostTime = 0L
                    return maxFace
                }
            }
        } else {
            // 没有锁定的人脸，记录当前最大脸的 trackingId
            lockedTrackingId = maxFace.trackingId
            lockedFaceLostTime = 0L
            Log.d(TAG, "锁定主脸 trackingId=${maxFace.trackingId}")
            return maxFace
        }
    }

    /**
     * 启动模式切换（带800ms平滑过渡）
     * 过渡期间：从当前位置平滑插值到新模式的起始位置，电机不跳变
     */
    private fun startModeTransition(targetMode: TrackMode, faces: List<Face>, imgW: Float, imgH: Float, now: Long) {
        // 取消之前的过渡协程，避免多个过渡同时运行
        transitionJob?.cancel()

        // 记录当前瞳孔/电机位置作为过渡起点
        transitionFromX = if (offsetXBuffer.isNotEmpty()) offsetXBuffer.average().toFloat() else 0f
        transitionFromY = if (offsetYBuffer.isNotEmpty()) offsetYBuffer.average().toFloat() else 0f
        transitionToMode = targetMode
        transitionStartTime = now
        isTransitioning = true

        // 清空平滑buffer，避免旧模式数据影响新模式
        clearSmoothBuffer()

        Log.d(TAG, "模式切换：$currentMode → $targetMode，开始 ${MODE_TRANSITION_MS}ms 平滑过渡")

        // 进入多人模式时触发语音播报（只播一次，且有冷却）
        if (targetMode == TrackMode.MULTI_FACE) {
            triggerMultiFaceGreeting(now)
        }

        // 初始化新模式的参数
        when (targetMode) {
            TrackMode.TWO_FACE -> {
                // 预计算双人正弦参数
                twoFacePeriodMs = TWO_FACE_PERIOD_MIN_MS +
                        (Math.random() * (TWO_FACE_PERIOD_MAX_MS - TWO_FACE_PERIOD_MIN_MS)).toLong()
                // 强制校准一次人脸位置（忽略间隔检查）
                lastFaceCalibrateTime = 0L
                calibrateFacePositionsForMode(faces, imgW, imgH)
                twoFacePhaseStartTime = now + MODE_TRANSITION_MS // 过渡结束后正式开始
            }
            TrackMode.MULTI_FACE -> {
                multiFacePeriodMs = MULTI_FACE_PERIOD_MIN_MS +
                        (Math.random() * (MULTI_FACE_PERIOD_MAX_MS - MULTI_FACE_PERIOD_MIN_MS)).toLong()
                // 强制校准一次人脸位置
                lastFaceCalibrateTime = 0L
                calibrateFacePositionsForMode(faces, imgW, imgH)
                multiFaceSweepStartTime = now + MODE_TRANSITION_MS
                hasSpokenForThisMultiSession = false
                multiDominantTracking = false
            }
            TrackMode.SINGLE -> {
                // 单人模式不需要预计算，直接跟踪
            }
            TrackMode.NO_FACE -> {
                // 无人脸，回中
            }
        }

        // 启动过渡协程：800ms内平滑插值
        transitionJob = scope.launch {
            val startX = transitionFromX
            val startY = transitionFromY
            // 计算过渡终点
            val (endX, endY) = when (targetMode) {
                TrackMode.SINGLE -> {
                    val (x, y) = calculateGazeOffset(faces[0], imgW, imgH)
                    Pair(x, y)
                }
                TrackMode.TWO_FACE -> {
                    // 双人模式从左边人脸开始
                    if (cachedFacePositions.size >= 2) cachedFacePositions[0] else Pair(-0.5f, 0f)
                }
                TrackMode.MULTI_FACE -> {
                    // 多人模式从中心开始扫视
                    Pair(0f, 0f)
                }
                TrackMode.NO_FACE -> Pair(0f, 0f)
            }

            val transitionStart = transitionStartTime
            while (System.currentTimeMillis() - transitionStart < MODE_TRANSITION_MS) {
                val progress = ((System.currentTimeMillis() - transitionStart) / MODE_TRANSITION_MS.toFloat())
                    .coerceIn(0f, 1f)
                // 缓动函数：先快后慢（ease-out），更自然
                val easedProgress = 1f - (1f - progress) * (1f - progress)
                val currentX = startX + (endX - startX) * easedProgress
                val currentY = startY + (endY - startY) * easedProgress

                setPupilSmooth(currentX, currentY)
                // 过渡期间电机也平滑移动
                val motorConfidence = when (targetMode) {
                    TrackMode.SINGLE -> SINGLE_FACE_CONFIDENCE
                    TrackMode.TWO_FACE -> TWO_FACE_CONFIDENCE
                    TrackMode.MULTI_FACE -> MULTI_FACE_CONFIDENCE
                    TrackMode.NO_FACE -> 0f
                }
                setMotorTarget(currentX, currentY, motorConfidence * progress)
                delay(33) // ~30fps更新
            }

            // 过渡结束，正式进入新模式
            isTransitioning = false
            currentMode = targetMode
            Log.d(TAG, "模式切换完成：$currentMode")
        }
    }

    /**
     * 多人场景语音播报
     * 随机选择一句问候语，通过回调让上层播放
     */
    private fun triggerMultiFaceGreeting(now: Long) {
        // 10秒冷却，避免频繁进出多人模式重复播报
        if (now - lastSpokenMultiFaceTime < 10000L) return
        lastSpokenMultiFaceTime = now

        val greeting = MULTI_FACE_GREETINGS.random()
        Log.d(TAG, "多人场景语音播报：$greeting")
        mainHandler.post {
            stateCallback?.onSpeakRequest(greeting)
        }
    }

    /**
     * 低频校准人脸位置（多人/双人场景使用）
     * 不是每帧都重算所有人脸位置，而是按固定间隔更新缓存
     * 双人：1Hz（1秒一次）
     * 多人：0.5Hz（2秒一次）
     *
     * @param force true=忽略间隔强制校准（模式切换时用）
     */
    private fun calibrateFacePositions(faces: List<Face>, imgW: Float, imgH: Float, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val calibrateInterval = when (currentMode) {
            TrackMode.TWO_FACE -> TWO_FACE_POS_CALIBRATE_MS
            TrackMode.MULTI_FACE -> MULTI_FACE_POS_CALIBRATE_MS
            else -> return // 单人模式不需要校准，每帧精确计算
        }

        if (!force && now - lastFaceCalibrateTime < calibrateInterval && cachedFacePositions.isNotEmpty()) {
            return // 还没到校准时间，使用缓存
        }

        doCalibrate(faces, imgW, imgH)
        lastFaceCalibrateTime = now
    }

    /**
     * 模式切换时的校准：指定目标模式
     */
    private fun calibrateFacePositionsForMode(faces: List<Face>, imgW: Float, imgH: Float) {
        doCalibrate(faces, imgW, imgH)
        lastFaceCalibrateTime = System.currentTimeMillis()
    }

    /**
     * 执行实际的人脸位置校准
     */
    private fun doCalibrate(faces: List<Face>, imgW: Float, imgH: Float) {
        // 重新计算所有人脸的注视点并缓存
        cachedFacePositions = faces.map { face ->
            val gaze = calculateGazeOffset(face, imgW, imgH)
            Pair(gaze.correctedX, gaze.correctedY)
        }

        // 更新中心位置（用于正弦/扫视路径）
        if (cachedFacePositions.isNotEmpty()) {
            multiFaceCenterX = cachedFacePositions.map { it.first }.average().toFloat()
            multiFaceCenterY = cachedFacePositions.map { it.second }.average().toFloat()
            if (cachedFacePositions.size >= 2) {
                twoFaceCenterX = (cachedFacePositions[0].first + cachedFacePositions[1].first) / 2f
                twoFaceCenterY = (cachedFacePositions[0].second + cachedFacePositions[1].second) / 2f
            }
        }

        Log.d(TAG, "人脸位置校准完成，共 ${cachedFacePositions.size} 人")
    }

    // ========== 各模式处理逻辑 ==========

    /**
     * 单人模式：精确跟踪
     * - 每帧计算注视点
     * - 5帧均值平滑
     * - 高置信度发送给电机
     */
    private fun handleSingleFaceMode(face: Face, imgW: Float, imgH: Float) {
        val gaze = calculateGazeOffset(face, imgW, imgH)

        // 屏幕瞳孔：5帧均值滤波（视觉效果需要更平滑）
        val smoothX = getSmoothValue(offsetXBuffer, gaze.correctedX)
        val smoothY = getSmoothValue(offsetYBuffer, gaze.correctedY)

        // 电机：One Euro Filter（静止时强滤波抑制噪声，快速移动时自动降低延迟）
        // 与屏幕瞳孔的 5 帧均值完全独立，参数从配置读取支持热更新
        val now = System.currentTimeMillis()
        val dt = if (lastMotorFilterTime > 0L) (now - lastMotorFilterTime) / 1000f else 0.033f
        lastMotorFilterTime = now
        val cfg = ConfigManager.getInstance().config.value.faceDetection
        motorFilterX.setParameters(cfg.motorOneEuroMinCutoff, cfg.motorOneEuroBeta, cfg.motorOneEuroDCutoff)
        motorFilterY.setParameters(cfg.motorOneEuroMinCutoff, cfg.motorOneEuroBeta, cfg.motorOneEuroDCutoff)
        val motorX = motorFilterX.filter(gaze.correctedX, dt)
        val motorY = motorFilterY.filter(gaze.correctedY, dt)

        val faceSize = (face.boundingBox.width().toFloat() / imgW).coerceIn(0f, 1f)

        // 更新调试信息（校准用，显示屏幕瞳孔的滤波值）
        if (DebugVisionState.visible.value) {
            DebugVisionState.updatePupilCalibration(
                rawOffsetX = gaze.rawX,
                rawOffsetY = gaze.rawY,
                correctedOffsetX = smoothX,
                correctedOffsetY = smoothY
            )
        }

        stateCallback?.onFaceDetected(smoothX, smoothY, faceSize)
        setPupilImmediate(smoothX, smoothY)
        setMotorTarget(motorX, motorY, SINGLE_FACE_CONFIDENCE)
    }

    /**
     * 双人模式：正弦交替注视
     * - 1Hz低频校准人脸位置
     * - 时间驱动正弦插值，不每帧重算
     * - 周期4-5秒，瞳孔和电机同步在两人之间平滑摆动
     * - 使用缓动正弦波（不是机械的纯正弦，而是更自然的S曲线）
     */
    private fun handleTwoFaceMode(faces: List<Face>, imgW: Float, imgH: Float, now: Long) {
        // 低频校准人脸位置
        calibrateFacePositions(faces, imgW, imgH)

        if (cachedFacePositions.size < 2) return

        // 基于时间计算正弦相位
        val elapsed = (now - twoFacePhaseStartTime).toFloat()
        val phase = (elapsed / twoFacePeriodMs) * 2f * PI.toFloat()
        // 正弦值 [-1, 1]，用缓动函数让运动更自然（两端慢中间快）
        val rawSin = sin(phase)
        // 平滑正弦：使用cos的缓动，让在两端停留更久（看一个人时停留，切换时快速移动）
        val t = (rawSin + 1f) / 2f // [0, 1]
        val easedT = smoothStep(t)
        val sweepX = -TWO_FACE_AMPLITUDE_X + easedT * TWO_FACE_AMPLITUDE_X * 2f

        // 最终位置：中心 + 正弦偏移
        val targetX = twoFaceCenterX + sweepX
        val targetY = twoFaceCenterY

        setPupilSmooth(targetX, targetY)
        setMotorTarget(targetX, targetY, TWO_FACE_CONFIDENCE)
    }

    /**
     * 多人模式：大范围扫视
     * - 0.5Hz低频校准人脸位置
     * - 时间驱动8字形/椭圆扫视路径
     * - 周期5-7秒，大范围扫视在场所有人
     * - 进入时已触发语音播报
     */
    private fun handleMultiFaceMode(faces: List<Face>, imgW: Float, imgH: Float, now: Long) {
        // faces 已按 boundingBox.width() 降序排列（filterValidFaces 中 sortedByDescending）
        val maxFace = faces[0]
        val secondFace = faces.getOrNull(1)

        // ========== 主导人脸检测（滞后区控制） ==========
        if (secondFace != null) {
            val ratio = maxFace.boundingBox.width().toFloat() /
                    secondFace.boundingBox.width().toFloat()
            when {
                ratio >= DOMINANT_FACE_RATIO_ENTER -> {
                    if (!multiDominantTracking) {
                        multiDominantTracking = true
                        Log.d(TAG, "多人模式 → 主导人脸跟踪 ratio=%.2f".format(ratio))
                    }
                }
                ratio <= DOMINANT_FACE_RATIO_EXIT -> {
                    if (multiDominantTracking) {
                        multiDominantTracking = false
                        lastFaceCalibrateTime = 0L // 切回扫视时强制校准一次人脸位置
                        Log.d(TAG, "多人模式 → 瞳孔扫视（头部保持不动）ratio=%.2f".format(ratio))
                    }
                }
                // 1.5 < ratio < 2.0：滞后区，保持上一帧状态，不切换
            }
        }

        if (multiDominantTracking) {
            // ========== 退化单人跟踪：跟随主导人脸 ==========
            val gaze = calculateGazeOffset(maxFace, imgW, imgH)

            // 电机：One Euro Filter（与单人模式一致）
            val dt = if (lastMotorFilterTime > 0L) (now - lastMotorFilterTime) / 1000f else 0.033f
            lastMotorFilterTime = now
            val cfg = ConfigManager.getInstance().config.value.faceDetection
            motorFilterX.setParameters(cfg.motorOneEuroMinCutoff, cfg.motorOneEuroBeta, cfg.motorOneEuroDCutoff)
            motorFilterY.setParameters(cfg.motorOneEuroMinCutoff, cfg.motorOneEuroBeta, cfg.motorOneEuroDCutoff)
            val motorX = motorFilterX.filter(gaze.correctedX, dt)
            val motorY = motorFilterY.filter(gaze.correctedY, dt)

            // 瞳孔和电机都跟随主导人脸
            setPupilSmooth(gaze.correctedX, gaze.correctedY)
            setMotorTarget(motorX, motorY, SINGLE_FACE_CONFIDENCE)
        } else {
            // ========== 多人扫视：瞳孔 Lissajous 扫视，头部保持不动 ==========
            calibrateFacePositions(faces, imgW, imgH)

            // 基于时间计算扫视路径（椭圆 Lissajous 曲线，更自然的大范围扫视）
            val elapsed = (now - multiFaceSweepStartTime).toFloat()
            val phaseX = (elapsed / multiFacePeriodMs) * 2f * PI.toFloat()
            val phaseY = phaseX * 0.7f + PI.toFloat() / 4f // Y轴相位偏移，形成8字/椭圆

            val sweepX = sin(phaseX) * MULTI_FACE_SWEEP_AMPLITUDE
            val sweepY = sin(phaseY) * MULTI_FACE_SWEEP_AMPLITUDE * 0.5f // Y轴幅度小一些

            val targetX = multiFaceCenterX + sweepX
            val targetY = multiFaceCenterY + sweepY

            // 瞳孔继续扫视，头部保持不动（不调用 setMotorTarget，电机保持最后位置）
            setPupilSmooth(targetX, targetY)
        }
    }

    /**
     * 平滑阶跃函数（S曲线）：3t² - 2t³
     * 让运动在两端慢、中间快，更自然
     */
    private fun smoothStep(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    // ========== 注视点计算 ==========

    /**
     * 计算单个人脸的注视点（双眼中心）
     * @return (rawOffsetX, rawOffsetY, correctedOffsetX, correctedOffsetY)
     *         raw: 原始归一化坐标 -1~1，已做镜像翻转，未做摄像头位置校正
     *         corrected: 经过摄像头位置校正后的坐标
     */
    private fun calculateGazeOffset(face: Face, imgW: Float, imgH: Float): GazeOffsetResult {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)

        // 从配置读取融合权重
        val cfg = ConfigManager.getInstance().config.value.faceDetection
        val eyeWeightX = cfg.gazeEyeWeightX
        val eyeWeightY = cfg.gazeEyeWeightY

        // 人脸框中心（始终可用，比 landmark 稳定）
        val bounds = face.boundingBox
        val faceX = bounds.exactCenterX()
        // Y轴用脸部偏上位置（约40%高度处），更接近视觉中心
        val faceY = bounds.top + bounds.height() * 0.40f

        val gazeCenterX: Float
        val gazeCenterY: Float

        if (leftEye != null && rightEye != null) {
            // 双眼中心
            val eyeX = (leftEye.position.x + rightEye.position.x) / 2f
            val eyeY = (leftEye.position.y + rightEye.position.y) / 2f
            // 融合：双眼中心 * weight + 人脸框中心 * (1 - weight)
            // X轴双眼权重高（更符合"看向眼睛"的视觉目标）
            // Y轴人脸框权重更高（landmark 噪声更大）
            gazeCenterX = eyeX * eyeWeightX + faceX * (1f - eyeWeightX)
            gazeCenterY = eyeY * eyeWeightY + faceY * (1f - eyeWeightY)
        } else {
            // 眼睛关键点丢失时，自动降级为 100% 人脸框中心
            gazeCenterX = faceX
            gazeCenterY = faceY
        }

        // 归一化 + 前置摄像头镜像翻转
        var rawOffsetX = (gazeCenterX - imgW / 2f) / (imgW / 2f)
        rawOffsetX = -rawOffsetX.coerceIn(-1f, 1f)

        // Y轴归一化
        val rawOffsetY = ((gazeCenterY - imgH / 2f) / (imgH / 2f)).coerceIn(-1f, 1f)

        // 摄像头位置校正（统一加法：corrected = raw + correction）
        // correction 正值 = 目标向对应正方向移动（X正向右，Y正向下）
        val cameraOffsetCorrectionX = cfg.cameraOffsetCorrectionX
        val cameraOffsetCorrectionY = cfg.cameraOffsetCorrectionY
        val correctedOffsetX = (rawOffsetX + cameraOffsetCorrectionX).coerceIn(-1f, 1f)
        val correctedOffsetY = (rawOffsetY + cameraOffsetCorrectionY).coerceIn(-1f, 1f)

        return GazeOffsetResult(rawOffsetX, rawOffsetY, correctedOffsetX, correctedOffsetY)
    }

    /**
     * 注视点计算结果
     */
    private data class GazeOffsetResult(
        val rawX: Float,
        val rawY: Float,
        val correctedX: Float,
        val correctedY: Float
    )

    // ========== 公共处理逻辑 ==========

    private fun processFaceCommon(face: Face) {
        if (!hasFace) {
            hasFace = true
            ExpressionManager.getInstance().setVisionTrackingActive(true)
            cancelSleepTimer()

            if (isSleepMode) {
                isSleepMode = false
                mainHandler.post {
                    ExpressionManager.getInstance().wakeFromSleep()
                    ExpressionManager.getInstance().requestExpression(ExpressionType.IDLE, force = true)
                }
                Log.d(TAG, "检测到人脸 → 唤醒")
            }

            if (!hasTriggeredGreeting) {
                hasTriggeredGreeting = true
                mainHandler.post {
                    ExpressionManager.getInstance().requestExpression(ExpressionType.RESPONSE, 100)
                }
                mainHandler.postDelayed({
                    ExpressionManager.getInstance().requestExpression(ExpressionType.IDLE)
                }, 1100)
                Log.d(TAG, "首次检测到人 → 应答打招呼")
            }
        }

        processFaceClassification(face)
    }

    // ========== 电机与瞳孔控制 ==========

    /**
     * 发送电机控制指令（统一出口）
     * 所有模式最终都通过这个方法发送给HeadControlManager
     */
    /**
     * 设置电机目标（不直接发送，写入 pending 变量由 15Hz 发送协程统一发出）
     * 所有电机指令必须通过此方法写入，确保单一入口。
     */
    private fun setMotorTarget(x: Float, y: Float, confidence: Float) {
        val clampedX = x.coerceIn(-1f, 1f)
        val clampedY = y.coerceIn(-1f, 1f)
        val clampedConf = confidence.coerceIn(0f, 1f)
        // 记录最后一个有效稳定目标（置信度>0.5 表示有效跟踪），供丢脸滞留时保持
        if (clampedConf > 0.5f) {
            lastStableMotorX = clampedX
            lastStableMotorY = clampedY
        }
        pendingMotorX = clampedX
        pendingMotorY = clampedY
        pendingMotorConfidence = clampedConf
    }

    /**
     * 电机发送协程：固定频率（默认 15Hz）读取 pending 目标，
     * 经死区 + 最大步长后处理后发送到板端。
     * 是唯一调用 headControlManager.sendFaceOffset 的地方。
     */
    private fun startMotorSenderLoop() {
        motorSenderJob?.cancel()
        motorSenderJob = scope.launch {
            while (true) {
                val cfg = ConfigManager.getInstance().config.value.faceDetection
                delay(cfg.motorSendIntervalMs)

                val x = pendingMotorX
                val y = pendingMotorY
                val conf = pendingMotorConfidence

                val isTracking = conf > 0.5f
                val wasTracking = lastSentMotorConfidence > 0.5f
                val trackingStateChanged = isTracking != wasTracking

                // 非跟踪状态（回中/过渡/休眠）：直接发送 pending 目标，不同步做稳定帧确认
                if (trackingStateChanged || !isTracking) {
                    val stepX = (x - lastSentMotorX).coerceIn(-cfg.motorMaxStep, cfg.motorMaxStep)
                    val stepY = (y - lastSentMotorY).coerceIn(-cfg.motorMaxStep, cfg.motorMaxStep)
                    val sendX = lastSentMotorX + stepX
                    val sendY = lastSentMotorY + stepY
                    headControlManager.sendFaceOffset(sendX, sendY, conf)
                    lastSentMotorX = sendX
                    lastSentMotorY = sendY
                    lastSentMotorConfidence = conf
                    // 同步重置确认状态，确保回到跟踪状态时从当前值开始
                    confirmedMotorX = x
                    confirmedMotorY = y
                    lastPendingMotorX = x
                    lastPendingMotorY = y
                    stableFrameCount = 0
                    continue
                }

                // ========== 跟踪状态：连续稳定帧确认（采样-保持） ==========
                // 比较相邻两次读取的 pending 目标的帧间变化
                val frameDeltaX = Math.abs(x - lastPendingMotorX)
                val frameDeltaY = Math.abs(y - lastPendingMotorY)

                if (frameDeltaX < cfg.motorDeadZoneX && frameDeltaY < cfg.motorDeadZoneY) {
                    // 帧间变化小于死区：目标稳定，计数 +1
                    stableFrameCount++
                    if (stableFrameCount >= STABLE_FRAME_THRESHOLD) {
                        // 连续稳定 N 帧：确认目标，更新 confirmed 值
                        confirmedMotorX = x
                        confirmedMotorY = y
                    }
                } else {
                    // 帧间变化超过死区：目标在移动或出现 outlier，重置计数
                    // confirmed 保持不变（采样-保持：移动过程中电机不动）
                    stableFrameCount = 0
                }
                lastPendingMotorX = x
                lastPendingMotorY = y

                // 发送 confirmed 目标（经死区 + 最大步长后处理）
                val sendDeltaX = Math.abs(confirmedMotorX - lastSentMotorX)
                val sendDeltaY = Math.abs(confirmedMotorY - lastSentMotorY)

                // 死区：确认目标与上次发送值差异在死区内 → 跳过
                if (sendDeltaX < cfg.motorDeadZoneX && sendDeltaY < cfg.motorDeadZoneY) {
                    continue
                }

                // 最大步长限制
                val stepX = (confirmedMotorX - lastSentMotorX).coerceIn(-cfg.motorMaxStep, cfg.motorMaxStep)
                val stepY = (confirmedMotorY - lastSentMotorY).coerceIn(-cfg.motorMaxStep, cfg.motorMaxStep)
                val sendX = lastSentMotorX + stepX
                val sendY = lastSentMotorY + stepY

                headControlManager.sendFaceOffset(sendX, sendY, conf)
                lastSentMotorX = sendX
                lastSentMotorY = sendY
                lastSentMotorConfidence = conf
            }
        }
    }

    private fun stopMotorSenderLoop() {
        motorSenderJob?.cancel()
        motorSenderJob = null
    }

    /**
     * 立即设置瞳孔位置（纯设置，不做滤波）
     * 调用方负责滤波：handleSingleFaceMode 先 getSmoothValue 再传入，
     * 确保屏幕瞳孔与电机收到同一个滤波值，且 buffer 每帧只写入一次。
     */
    private fun setPupilImmediate(targetX: Float, targetY: Float) {
        mainHandler.post {
            ExpressionManager.getInstance().setPupilOffset(targetX, targetY)
        }
    }

    // 多人/双人模式独立的指数平滑变量（不污染单人模式的均值滤波buffer）
    private var multiSmoothedX = 0f
    private var multiSmoothedY = 0f

    /**
     * 平滑设置瞳孔位置（多人/双人/过渡模式用，指数平滑）
     * 不使用offsetXBuffer，避免和单人模式的5帧均值滤波互相干扰
     */
    private fun setPupilSmooth(targetX: Float, targetY: Float) {
        // 指数平滑：系数越小越平滑，多人模式用0.15让眼球移动更写意
        val smoothFactor = 0.15f
        multiSmoothedX += (targetX - multiSmoothedX) * smoothFactor
        multiSmoothedY += (targetY - multiSmoothedY) * smoothFactor

        mainHandler.post {
            ExpressionManager.getInstance().setPupilOffset(multiSmoothedX, multiSmoothedY)
        }
    }

    /**
     * 距离感知瞳孔缩放
     */
    private fun updatePupilSizeByDistance(faceSizeRatio: Float) {
        val normalizedDist = (faceSizeRatio - DISTANCE_REFERENCE_SIZE) / DISTANCE_REFERENCE_SIZE
        val targetScale = 1f - normalizedDist * 0.15f
        smoothedDistanceScale += (targetScale - smoothedDistanceScale) * DISTANCE_SMOOTH_FACTOR
        val finalScale = smoothedDistanceScale.coerceIn(DISTANCE_PUPIL_MIN_SCALE, DISTANCE_PUPIL_MAX_SCALE)
        mainHandler.post {
            ExpressionManager.getInstance().setDistancePupilScale(finalScale)
        }
    }

    // ========== 表情分类检测 ==========

    private fun processFaceClassification(face: Face) {
        val now = System.currentTimeMillis()
        val smileProb = face.smilingProbability ?: -1f

        if (smileProb < 0f) return

        val smoothSmile = getSmoothValue(smileProbBuffer, smileProb)

        detectSmile(smoothSmile, now)
    }

    private fun detectSmile(smileProb: Float, now: Long) {
        if (smileProb >= SMILE_THRESHOLD) {
            smileConsecutiveCount++
            if (smileConsecutiveCount >= SMILE_FRAME_COUNT
                && now - lastSmileTriggerTime > SMILE_COOLDOWN_MS
            ) {
                lastSmileTriggerTime = now
                smileConsecutiveCount = 0
                Log.d(TAG, "检测到微笑 → 开心表情")
                mainHandler.post {
                    ExpressionManager.getInstance().requestExpression(ExpressionType.HAPPY)
                }
                stateCallback?.onUserSmile(smileProb)
            }
        } else {
            if (smileConsecutiveCount > 0) smileConsecutiveCount = 0
        }
    }

    private fun handleFaceAbsent() {
        if (hasFace && !faceLostPending) {
            // 第一次检测到丢脸：进入 500ms 滞留期，保持最后电机目标不跳变
            faceLostPending = true
            // 滞留期间不改变 hasFace / currentMode / pendingFaceCount，
            // 避免触发模式过渡与重复打招呼；500ms 后仍无脸再正式切 NO_FACE
            stateCallback?.onFaceLost()
            startFaceLostStayTimer()
            resetSleepTimer()
            mainHandler.post {
                ExpressionManager.getInstance().setDistancePupilScale(1f)
            }
            // 不发送电机命令，保持 lastStableMotorX/Y
            // 不清空 cachedFacePositions / multiSmoothed，可能马上恢复
        }
        // faceLostPending 已为 true 时：什么都不做，继续保持最后目标
    }

    private fun getSmoothValue(buffer: ArrayDeque<Float>, newValue: Float): Float {
        if (buffer.size >= SMOOTH_FILTER_COUNT) buffer.removeFirst()
        buffer.addLast(newValue)
        return buffer.average().toFloat()
    }

    private fun startFaceLostStayTimer() {
        faceLostStayJob?.cancel()
        faceLostStayJob = scope.launch {
            delay(FACE_LOST_STAY_TIME)
            // 500ms 后仍处于丢脸滞留：正式进入无人脸状态，启动缓慢回中
            if (faceLostPending) {
                hasFace = false
                faceLostPending = false
                // 视觉跟踪丢失，恢复待机微动作
                ExpressionManager.getInstance().setVisionTrackingActive(false)
                currentMode = TrackMode.NO_FACE
                lockedTrackingId = null
                lockedFaceLostTime = 0L
                transitionJob?.cancel()
                transitionJob = null
                isTransitioning = false
                cachedFacePositions = emptyList()
                clearSmoothBuffer()
                startReturnToCenter()
            }
        }
    }

    private fun cancelFaceLostStayJob() {
        faceLostStayJob?.cancel()
        faceLostStayJob = null
    }

    /**
     * 缓慢回中协程：从 lastStableMotorX/Y 指数平滑到 (0,0)，耗时约 300ms
     * 可被新检测到的人脸取消（cancel returnToCenterJob）
     */
    private fun startReturnToCenter() {
        returnToCenterJob?.cancel()
        returnToCenterJob = scope.launch {
            var curX = lastStableMotorX
            var curY = lastStableMotorY
            val stepMs = 16L
            val totalSteps = 20 // ~320ms
            val factor = 0.2f // 指数平滑系数，20步后残余约 0.8^20 ≈ 1.15%
            repeat(totalSteps) {
                curX += (0f - curX) * factor
                curY += (0f - curY) * factor
                mainHandler.post {
                    ExpressionManager.getInstance().setPupilOffset(curX, curY)
                }
                setMotorTarget(curX, curY, 0.3f)
                delay(stepMs)
            }
            // 最终归零
            mainHandler.post {
                ExpressionManager.getInstance().setPupilOffset(0f, 0f)
            }
            setMotorTarget(0f, 0f, 0f)
            multiSmoothedX = 0f
            multiSmoothedY = 0f
            returnToCenterJob = null
        }
    }

    private fun resetSleepTimer() {
        sleepTimeoutJob?.cancel()
        sleepTimeoutJob = scope.launch {
            delay(NO_FACE_SLEEP_DELAY)
            if (isDetecting && !hasFace) {
                mainHandler.post {
                    isSleepMode = true
                    hasTriggeredGreeting = false
                    ExpressionManager.getInstance().requestExpression(ExpressionType.SLEEPING, force = true)
                    ExpressionManager.getInstance().enterSleepWithDream()
                    Log.d(TAG, "无人脸9秒 → 休眠")
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
        isStopping = true
        isDetecting = false
        hasFace = false
        ExpressionManager.getInstance().setVisionTrackingActive(false)
        isSleepMode = false
        isTransitioning = false
        transitionJob?.cancel()
        transitionJob = null
        faceLostPending = false
        returnToCenterJob?.cancel()
        returnToCenterJob = null

        // 停止电机发送协程，强制发送一次归零（绕过 pending 机制，确保立即生效）
        stopMotorSenderLoop()
        headControlManager.sendFaceOffset(0f, 0f, 0f)
        lastSentMotorX = 0f
        lastSentMotorY = 0f
        lastSentMotorConfidence = 0f
        pendingMotorX = 0f
        pendingMotorY = 0f
        pendingMotorConfidence = 0f
        motorFilterX.reset()
        motorFilterY.reset()
        lastMotorFilterTime = 0L
        confirmedMotorX = 0f
        confirmedMotorY = 0f
        lastPendingMotorX = 0f
        lastPendingMotorY = 0f
        stableFrameCount = 0

        cancelSleepTimer()
        cancelFaceLostStayJob()
        clearSmoothBuffer()
        clearExpressionBuffer()
        multiSmoothedX = 0f
        multiSmoothedY = 0f
        lastStableMotorX = 0f
        lastStableMotorY = 0f

        mainHandler.post {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "解绑相机失败", e)
            } finally {
                cameraProvider = null
                analysisExecutor?.shutdownNow()
                analysisExecutor = null
                isStopping = false
                stateCallback?.onStop()
                Log.d(TAG, "已停止人脸检测")
            }
        }
    }

    fun isDetecting(): Boolean = isDetecting

    fun release() {
        stopFaceDetect()
        faceDetector.close()
        stateCallback = null
    }

    // ========== 人脸识别辅助方法 ==========

    /**
     * 从NV21字节数组转Bitmap（带旋转+前置镜像）
     * 不依赖Image对象生命周期，可以在imageProxy.close()之后安全调用
     */
    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int): Bitmap? {
        return try {
            // NV21 -> JPEG -> Bitmap
            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width,
                height,
                null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

            // 处理旋转+前置镜像
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)

            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "NV21 to Bitmap failed", e)
            null
        }
    }

}
