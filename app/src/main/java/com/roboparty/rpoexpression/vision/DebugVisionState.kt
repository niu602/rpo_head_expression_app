/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 调试可视化状态持有者
 *
 * 在 VisionManager / FaceIdentifier 和 Compose UI 之间传递调试数据。
 * 使用 StateFlow 保证线程安全，UI 可以直接 collect。
 *
 * 数据流：
 * - VisionManager.processImageProxy → 更新 frameBitmap + detectedFaces
 * - FaceIdentifier.onFrameResult     → 更新 recognitionInfo
 * - DebugVisionOverlay (Compose)     → collect 以上状态实时渲染
 */
object DebugVisionState {

    /** 调试窗口是否显示（默认隐藏，点击屏幕按钮或手柄L3键开启） */
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    fun toggleVisible() {
        _visible.value = !_visible.value
    }

    fun setVisible(v: Boolean) {
        _visible.value = v
    }

    // ========== 摄像头帧数据 ==========

    data class FrameData(
        val bitmap: Bitmap? = null,
        val faceCount: Int = 0,
        val timestamp: Long = 0L,
        // 坐标转换参数：原始帧宽高、旋转角度、是否前置镜像
        val srcWidth: Int = 0,
        val srcHeight: Int = 0,
        val rotationDegrees: Int = 0,
        val mirrorX: Boolean = true
    )

    private val _frame = MutableStateFlow(FrameData())
    val frame: StateFlow<FrameData> = _frame

    /**
     * 由 VisionManager 调用：更新最新摄像头帧
     * 注意：bitmap 不在此处回收，由 UI 渲染完成后管理
     */
    fun updateFrame(
        bitmap: Bitmap?,
        faceCount: Int,
        srcWidth: Int = 0,
        srcHeight: Int = 0,
        rotationDegrees: Int = 0,
        mirrorX: Boolean = true
    ) {
        // 限制更新频率到 ~15fps，避免高频 Bitmap 拷贝拖慢性能
        val now = System.currentTimeMillis()
        if (now - _frame.value.timestamp < 66) {
            // 节流丢弃的帧：立即回收，避免泄漏
            bitmap?.let { b ->
                if (!b.isRecycled) {
                    try { b.recycle() } catch (_: Exception) {}
                }
            }
            return
        }

        val old = _frame.value.bitmap
        _frame.value = FrameData(bitmap, faceCount, now, srcWidth, srcHeight, rotationDegrees, mirrorX)

        // 回收上一帧（避免内存泄漏）
        old?.let { b ->
            if (b !== bitmap && !b.isRecycled) {
                mainHandler.post { try { b.recycle() } catch (_: Exception) {} }
            }
        }
    }

    // ========== 人脸检测详情 ==========

    data class FaceInfo(
        val faces: List<Face> = emptyList(),
        val imgWidth: Float = 0f,
        val imgHeight: Float = 0f,
        val timestamp: Long = 0L
    )

    private val _faceInfo = MutableStateFlow(FaceInfo())
    val faceInfo: StateFlow<FaceInfo> = _faceInfo

    fun updateFaceInfo(faces: List<Face>, imgWidth: Float, imgHeight: Float) {
        _faceInfo.value = FaceInfo(faces, imgWidth, imgHeight, System.currentTimeMillis())
    }

    // ========== 人脸识别结果 ==========

    data class RecognitionInfo(
        val resultText: String = "",
        val similarity: Float = 0f,
        val qualityText: String = "",
        val timestamp: Long = 0L
    )

    private val _recognition = MutableStateFlow(RecognitionInfo())
    val recognition: StateFlow<RecognitionInfo> = _recognition

    fun updateRecognition(resultText: String, similarity: Float, qualityText: String) {
        _recognition.value = RecognitionInfo(resultText, similarity, qualityText, System.currentTimeMillis())
    }

    // ========== 注册进度 ==========

    data class RegisterInfo(
        val isRegistering: Boolean = false,
        val name: String = "",
        val collected: Int = 0,
        val total: Int = 0
    )

    private val _register = MutableStateFlow(RegisterInfo())
    val register: StateFlow<RegisterInfo> = _register

    fun updateRegister(isRegistering: Boolean, name: String = "", collected: Int = 0, total: Int = 0) {
        _register.value = RegisterInfo(isRegistering, name, collected, total)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
}
