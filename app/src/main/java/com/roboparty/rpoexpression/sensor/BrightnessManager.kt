/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.sensor

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 亮度管理器
 * 改为通过额头区域上下滑动手势调节亮度，替代光线传感器自动调节
 *
 * 功能：
 * 1. 单指上滑 → 逐级提高亮度，眼睛高光同步变亮
 * 2. 单指下滑 → 逐级降低亮度，眼睛高光同步变暗
 * 3. 滑到最低/最高亮度时，触发快速眨眼提示边界
 * 4. 亮度调节实时生效，自动保存默认值
 */
class BrightnessManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BrightnessManager"
        private const val PREFS_NAME = "rpo_brightness_prefs"
        private const val KEY_BRIGHTNESS = "saved_brightness"

        // 亮度范围
        private const val MIN_BRIGHTNESS = 0.2f  // 最低20%
        private const val MAX_BRIGHTNESS = 1.0f  // 最高100%
        private const val BRIGHTNESS_STEP = 0.08f // 每级调节步长

        // 滑动灵敏度：每滑动多少像素调节一级
        private const val SWIPE_SENSITIVITY = 30f

        @Volatile
        private var instance: BrightnessManager? = null

        fun getInstance(context: Context): BrightnessManager {
            return instance ?: synchronized(this) {
                instance ?: BrightnessManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 当前亮度值
    private val _brightness = MutableStateFlow(loadSavedBrightness())
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    // 眼睛高光强度（与亮度同步，作为视觉反馈）
    private val _highlightIntensity = MutableStateFlow(1.0f)
    val highlightIntensity: StateFlow<Float> = _highlightIntensity.asStateFlow()

    // 边界眨眼回调
    var onBoundaryReached: (() -> Unit)? = null

    // 滑动过程中的累积偏移
    private var swipeAccumulator = 0f

    // 关机前保存的亮度，开机时恢复
    private var brightnessBeforePowerOff = 0.8f

    // 亮度动画Job
    private var brightnessAnimationJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 保存当前Activity引用（用于设置窗口亮度）
    private var currentActivity: Activity? = null

    /**
     * 启动（不再启动传感器，只应用保存的亮度）
     */
    fun start() {
        applyBrightnessToScreen(_brightness.value)
        updateHighlightIntensity(_brightness.value)
        Log.d(TAG, "Brightness manager started, saved brightness: ${_brightness.value}")
    }

    /**
     * 停止
     */
    fun stop() {
        saveBrightness(_brightness.value)
    }

    /**
     * 绑定Activity
     */
    fun attachActivity(activity: Activity) {
        currentActivity = activity
        applyBrightnessToScreen(_brightness.value, activity)
    }

    /**
     * 解绑Activity
     */
    fun detachActivity() {
        currentActivity = null
    }

    /**
     * 处理滑动手势
     * @param deltaY Y轴滑动偏移，负值=上滑（增亮），正值=下滑（变暗）
     * @return 是否到达边界
     */
    fun onSwipe(deltaY: Float): Boolean {
        swipeAccumulator += -deltaY // 反转：上滑为正，增加亮度

        var reachedBoundary = false

        while (Math.abs(swipeAccumulator) >= SWIPE_SENSITIVITY) {
            if (swipeAccumulator > 0) {
                // 上滑：增加亮度
                if (_brightness.value >= MAX_BRIGHTNESS - 0.01f) {
                    reachedBoundary = true
                    break
                }
                swipeAccumulator -= SWIPE_SENSITIVITY
                adjustBrightness(BRIGHTNESS_STEP)
            } else {
                // 下滑：降低亮度
                if (_brightness.value <= MIN_BRIGHTNESS + 0.01f) {
                    reachedBoundary = true
                    break
                }
                swipeAccumulator += SWIPE_SENSITIVITY
                adjustBrightness(-BRIGHTNESS_STEP)
            }
        }

        // 到达边界时重置累积器并触发回调
        if (reachedBoundary) {
            swipeAccumulator = 0f
            scope.launch {
                onBoundaryReached?.invoke()
            }
        }

        return reachedBoundary
    }

    /**
     * 滑动结束，重置累积器并保存亮度
     */
    fun onSwipeEnd() {
        swipeAccumulator = 0f
        saveBrightness(_brightness.value)
    }

    /**
     * 调节亮度
     */
    private fun adjustBrightness(delta: Float) {
        // 滑动调节限制最低MIN_BRIGHTNESS，防止全黑看不见
        val newBrightness = (_brightness.value + delta).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        _brightness.value = newBrightness
        applyBrightnessToScreen(newBrightness)
        updateHighlightIntensity(newBrightness)
        Log.d(TAG, "Brightness adjusted to: $newBrightness")
    }

    /**
     * 直接设置亮度
     */
    fun setBrightness(activity: Activity, brightness: Float) {
        // 允许0亮度（关机黑屏用），滑动调节在onSwipe里已经限制最低MIN_BRIGHTNESS
        val clamped = brightness.coerceIn(0f, MAX_BRIGHTNESS)
        _brightness.value = clamped
        applyBrightnessToScreen(clamped, activity)
        // 0亮度时不更新高光、不保存
        if (clamped >= MIN_BRIGHTNESS) {
            updateHighlightIntensity(clamped)
            saveBrightness(clamped)
        } else {
            _highlightIntensity.value = 0f
        }
    }

    /**
     * 平滑动画过渡到目标亮度（支持到0，用于关机黑屏）
     * @param targetBrightness 目标亮度 0.0-1.0
     * @param durationMs 动画时长
     * @param onComplete 动画完成回调
     */
    fun animateTo(targetBrightness: Float, durationMs: Long = 800L, onComplete: (() -> Unit)? = null) {
        brightnessAnimationJob?.cancel()
        val startBrightness = _brightness.value
        val startTime = System.currentTimeMillis()

        brightnessAnimationJob = scope.launch {
            var elapsed = 0L
            while (elapsed < durationMs && isActive) {
                delay(16L)
                elapsed = System.currentTimeMillis() - startTime
                val t = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                // easeInOutQuad 平滑曲线
                val progress = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) / 2
                val current = startBrightness + (targetBrightness - startBrightness) * progress

                _brightness.value = current.coerceIn(0f, 1f)
                applyBrightnessToScreen(current.coerceIn(0f, 1f))
                updateHighlightIntensity(current.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS))
            }
            // 确保最终到目标值
            _brightness.value = targetBrightness.coerceIn(0f, 1f)
            applyBrightnessToScreen(targetBrightness.coerceIn(0f, 1f))
            if (targetBrightness >= MIN_BRIGHTNESS) {
                updateHighlightIntensity(targetBrightness)
            } else {
                _highlightIntensity.value = 0f
            }
            onComplete?.invoke()
        }
    }

    /**
     * 关机：保存当前亮度，然后动画到黑屏
     */
    fun animateToPowerOff(durationMs: Long = 800L, onComplete: (() -> Unit)? = null) {
        brightnessBeforePowerOff = _brightness.value
        // 关机不调到完全0，保留极微弱背光（0.03），让顶部电源呼吸灯可见
        animateTo(0.03f, durationMs, onComplete)
    }

    /**
     * 开机：从黑屏动画恢复到关机前的亮度
     */
    fun animateToPowerOn(durationMs: Long = 600L, onComplete: (() -> Unit)? = null) {
        animateTo(brightnessBeforePowerOff.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS), durationMs, onComplete)
    }

    /**
     * 应用亮度到屏幕（支持0亮度黑屏）
     */
    private fun applyBrightnessToScreen(brightness: Float, activity: Activity? = null) {
        try {
            val targetActivity = activity ?: currentActivity
            targetActivity?.let { act ->
                val layoutParams = act.window.attributes
                layoutParams.screenBrightness = brightness.coerceIn(0f, 1f)
                act.window.attributes = layoutParams
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply screen brightness", e)
        }
    }

    /**
     * 更新眼睛高光强度（亮度越低高光越弱，亮度越高高光越强）
     * 映射关系：
     * - 20%亮度 → 高光0.4（柔和）
     * - 100%亮度 → 高光1.2（明亮）
     */
    private fun updateHighlightIntensity(brightness: Float) {
        val t = (brightness - MIN_BRIGHTNESS) / (MAX_BRIGHTNESS - MIN_BRIGHTNESS)
        _highlightIntensity.value = 0.4f + t * 0.8f
    }

    /**
     * 保存亮度到SharedPreferences
     */
    private fun saveBrightness(brightness: Float) {
        try {
            prefs.edit().putFloat(KEY_BRIGHTNESS, brightness).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save brightness", e)
        }
    }

    /**
     * 加载保存的亮度，默认80%
     */
    private fun loadSavedBrightness(): Float {
        return prefs.getFloat(KEY_BRIGHTNESS, 0.8f).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }
}


