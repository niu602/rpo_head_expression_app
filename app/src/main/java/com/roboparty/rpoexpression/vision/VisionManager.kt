/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.roboparty.rpoexpression.integration.ModuleStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 视觉管理器
 * 基于ML Kit实现实时人脸检测，自动联动表情状态
 */
class VisionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VisionManager"
        private const val FACE_DETECT_TIMEOUT = 7000L // 人脸检测超时时间7秒

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
    private var timeoutJob: Job? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var isDetecting = false
    private var hasFace = false

    /**
     * 人脸检测状态回调
     */
    interface VisionStateCallback {
        fun onStart() {} // 开始检测
        fun onFaceDetected() {} // 检测到人脸
        fun onFaceLost() {} // 人脸离开
        fun onTimeout() {} // 检测超时（7秒未检测到人脸）
        fun onError(errorMsg: String) {} // 检测错误
        fun onStop() {} // 停止检测
    }

    private var stateCallback: VisionStateCallback? = null

    // 帧计数，用于打日志
    private var frameCount = 0

    // ML Kit人脸检测器，配置快速检测模式
    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST) // 快速模式，低延迟
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE) // 不检测关键点，提高速度
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE) // 不检测表情分类
            .setMinFaceSize(0.1f) // 最小人脸大小10%，支持更远距离检测
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * 初始化视觉模块
     */
    fun init() {
        Log.d(TAG, "VisionManager initialized")
    }

    /**
     * 开始人脸检测
     * @param lifecycleOwner 生命周期所有者，用于绑定CameraX
     * @param callback 状态回调
     */
    fun startFaceDetect(lifecycleOwner: LifecycleOwner, callback: VisionStateCallback? = null) {
        if (isDetecting) {
            Log.w(TAG, "Face detect already running")
            return
        }
        this.stateCallback = callback
        isDetecting = true
        hasFace = false

        // 开始检测，联动表情
        ModuleStateManager.onFaceDetectStart()
        stateCallback?.onStart()
        Log.d(TAG, "Start face detection")

        // 初始化分析线程
        analysisExecutor = Executors.newSingleThreadExecutor()

        // 绑定相机
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // 预览用例（不需要显示，仅用于获取帧）
                val preview = Preview.Builder().build()

                // 图像分析用例
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只保留最新帧，降低延迟
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor!!) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }

                // 选择前置摄像头
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // 解绑所有用例，重新绑定
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                // 相机启动成功，启动超时计时：7秒未检测到人脸触发超时
                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(FACE_DETECT_TIMEOUT)
                    if (isDetecting && !hasFace) {
                        Log.d(TAG, "⚠️ Face detect timeout, 7s no face detected")
                        // 超时回调切主线程，停止检测和TTS播放由回调处理
                        mainHandler.post {
                            stateCallback?.onTimeout()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start face detect failed", e)
                isDetecting = false
                ModuleStateManager.onFaceDetectFailed()
                stateCallback?.onError(e.message ?: "相机启动失败")
                stateCallback?.onStop()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 处理相机帧，进行人脸检测
     */
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        frameCount++
        if (mediaImage != null) {
            val rotation = imageProxy.imageInfo.rotationDegrees
            // 每10帧打一次调试日志，方便排查
            if (frameCount % 10 == 0) {
                Log.d(TAG, "Process frame #$frameCount, rotation=$rotation, size=${mediaImage.width}x${mediaImage.height}")
            }
            val image = InputImage.fromMediaImage(mediaImage, rotation)
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    // 每10帧打一次检测结果
                    if (frameCount % 10 == 0) {
                        Log.d(TAG, "Frame #$frameCount detect result: face count=${faces.size}")
                    }
                    val faceDetected = faces.isNotEmpty()
                    if (faceDetected && !hasFace) {
                        // 首次检测到人脸，取消超时计时
                        timeoutJob?.cancel()
                        hasFace = true
                        Log.d(TAG, "✅ Face detected, confidence=${faces[0].headEulerAngleY}")
                        ModuleStateManager.onFaceDetectSuccess()
                        stateCallback?.onFaceDetected()
                    } else if (!faceDetected && hasFace) {
                        // 人脸离开
                        hasFace = false
                        Log.d(TAG, "❌ Face lost")
                        ModuleStateManager.onFaceDetectStart() // 回到识别中状态
                        stateCallback?.onFaceLost()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Face detect process failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            Log.w(TAG, "Frame #$frameCount mediaImage is null")
            imageProxy.close()
        }
    }

    /**
     * 停止人脸检测
     */
    fun stopFaceDetect() {
        if (!isDetecting) return
        isDetecting = false
        hasFace = false
        frameCount = 0
        timeoutJob?.cancel()
        timeoutJob = null

        // CameraX操作必须在主线程执行
        mainHandler.post {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "Unbind camera failed", e)
            }
            cameraProvider = null
            analysisExecutor?.shutdown()
            analysisExecutor = null

            // 停止检测，回到待机
            ModuleStateManager.onFaceDetectFailed()
            stateCallback?.onStop()
            Log.d(TAG, "Stop face detection success")
        }
    }

    /**
     * 是否正在检测
     */
    fun isDetecting(): Boolean = isDetecting

    /**
     * 释放相机和检测器资源
     */
    fun release() {
        stopFaceDetect()
        faceDetector.close()
        stateCallback = null
        Log.d(TAG, "VisionManager released")
    }
}
