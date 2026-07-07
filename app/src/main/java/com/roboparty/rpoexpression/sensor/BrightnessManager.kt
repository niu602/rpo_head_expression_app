/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.sensor

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * 亮度管理器
 * 使用光照传感器实现屏幕亮度自适应调节
 *
 * 亮度映射：
 * - 暗光环境(<10lux): 亮度30%
 * - 室内环境(10~500lux): 亮度30%~80%线性映射
 * - 亮光环境(>500lux): 亮度100%
 */
class BrightnessManager private constructor(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "BrightnessManager"
        private const val MIN_BRIGHTNESS = 0.3f
        private const val MAX_BRIGHTNESS = 1.0f
        private const val DARK_THRESHOLD = 10f
        private const val BRIGHT_THRESHOLD = 500f
        private const val SENSOR_DELAY = 1000L  // 传感器采样间隔
        private const val BRIGHTNESS_SMOOTHING = 0.1f  // 亮度平滑系数

        @Volatile
        private var instance: BrightnessManager? = null

        fun getInstance(context: Context): BrightnessManager {
            return instance ?: synchronized(this) {
                instance ?: BrightnessManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _brightness = MutableStateFlow(0.8f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private var targetBrightness = 0.8f
    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 启动亮度监听
     */
    fun start() {
        lightSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            startBrightnessUpdateLoop()
            Log.d(TAG, "Light sensor registered")
        } ?: run {
            Log.w(TAG, "No light sensor available, using default brightness")
            _brightness.value = 0.8f
        }
    }

    /**
     * 停止亮度监听
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        updateJob?.cancel()
        Log.d(TAG, "Light sensor unregistered")
    }

    /**
     * 手动设置亮度
     */
    fun setBrightness(activity: Activity, brightness: Float) {
        val clamped = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        targetBrightness = clamped

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
            // 系统级亮度（需要权限）
            val layoutParams = activity.window.attributes
            layoutParams.screenBrightness = clamped
            activity.window.attributes = layoutParams
        } else {
            // 应用窗口亮度
            val layoutParams = activity.window.attributes
            layoutParams.screenBrightness = clamped
            activity.window.attributes = layoutParams
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_LIGHT) {
                val lux = it.values[0]
                targetBrightness = calculateBrightness(lux)
                Log.d(TAG, "Light level: $lux lux, target brightness: $targetBrightness")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化无需处理
    }

    /**
     * 根据光照强度计算目标亮度
     */
    private fun calculateBrightness(lux: Float): Float {
        return when {
            lux < DARK_THRESHOLD -> MIN_BRIGHTNESS
            lux > BRIGHT_THRESHOLD -> MAX_BRIGHTNESS
            else -> {
                val ratio = (lux - DARK_THRESHOLD) / (BRIGHT_THRESHOLD - DARK_THRESHOLD)
                MIN_BRIGHTNESS + ratio * (MAX_BRIGHTNESS - MIN_BRIGHTNESS)
            }
        }
    }

    /**
     * 亮度平滑更新循环，避免亮度突变
     */
    private fun startBrightnessUpdateLoop() {
        updateJob = scope.launch {
            while (isActive) {
                val current = _brightness.value
                val diff = targetBrightness - current
                if (Math.abs(diff) > 0.01f) {
                    _brightness.value = current + diff * BRIGHTNESS_SMOOTHING
                }
                delay(50L)
            }
        }
    }
}
