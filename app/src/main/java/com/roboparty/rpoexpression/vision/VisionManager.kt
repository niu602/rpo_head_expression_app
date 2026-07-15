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
 * 视觉管理器（中端顺滑精准版）
 * 1. 基于双眼关键点实现真实视线跟随，不再依赖人脸框
 * 2. 均值滤波防抖，眼球移动沉稳无抖动
 * 3. 人脸短暂丢失滞留防抖，交互更自然
 * 4. 远近人脸兼容更好，侧脸容错提升
 * 5. 保留休眠唤醒、首次见面问候原有业务逻辑
 */
class VisionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VisionManager-Mid"
        private const val NO_FACE_SLEEP_DELAY = 9000L       // 无人脸9秒自动休眠
        private const val FACE_LOST_STAY_TIME = 500L        // 人脸丢失滞留500ms再复位眼睛
        private const val SMOOTH_FILTER_COUNT = 5           // 5帧均值平滑滤波

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

    // 降低跳帧，提升流畅度
    private val FRAME_SKIP = 1
    private var frameSkipCounter = 0

    // 平滑滤波缓存队列
    private val offsetXBuffer = ArrayDeque<Float>(SMOOTH_FILTER_COUNT)
    private val offsetYBuffer = ArrayDeque<Float>(SMOOTH_FILTER_COUNT)

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
    }

    private var stateCallback: VisionStateCallback? = null

    // 中端配置：精准模式 + 仅双眼关键点 + 远距离人脸
    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    fun init() {
        Log.d(TAG, "VisionManager 中端精准版初始化完成")
        clearSmoothBuffer()
    }

    private fun clearSmoothBuffer() {
        offsetXBuffer.clear()
        offsetYBuffer.clear()
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
        clearSmoothBuffer()

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
        if (frameSkipCounter < FRAME_SKIP) {
            imageProxy.close()
            return
        }
        frameSkipCounter = 0

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    cancelFaceLostStayJob()
                    handleFacePresent(face, mediaImage.width.toFloat(), mediaImage.height.toFloat())
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
            if (!hasTriggeredGreeting) {
                hasTriggeredGreeting = true
                mainHandler.post {
                    ExpressionManager.getInstance().requestExpression(ExpressionType.RESPONSE, 1000)
                }
                mainHandler.postDelayed({
                    ExpressionManager.getInstance().requestExpression(ExpressionType.IDLE)
                }, 1100)
                Log.d(TAG, "首次检测到人眼 -> 应答打招呼")
            }
        }
        stateCallback?.onFaceDetected(smoothX, smoothY, faceSize)
    }

    private fun handleFaceAbsent() {
        if (hasFace) {
            hasFace = false
            stateCallback?.onFaceLost()
            startFaceLostStayTimer()
            resetSleepTimer()
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
                    ExpressionManager.getInstance().requestExpression(ExpressionType.SLEEPING, force = true)
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
        cancelSleepTimer()
        cancelFaceLostStayJob()
        clearSmoothBuffer()

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