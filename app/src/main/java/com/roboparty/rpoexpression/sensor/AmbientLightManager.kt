/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import android.content.ContentValues.TAG
/**
 * 环境光管理器
 * 监听光线传感器，自动适配亮度、高光、色温，暗光下开启夜光模式
 */
class AmbientLightManager private constructor(context: Context) : SensorEventListener {

    data class LightConfig(
        val brightness: Float,    // 整体亮度 0.2-1.0
        val highlight: Float,     // 高光强度 0.3-1.2
        val colorTemp: Int,       // 色温 2700K(暖) - 6500K(冷)
        val isNightMode: Boolean  // 是否夜光模式
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val tempSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)

    private val _lightConfig = MutableStateFlow(DEFAULT_CONFIG)
    val lightConfig: StateFlow<LightConfig> = _lightConfig.asStateFlow()

    // 色温缓存，有色温传感器就用，否则自动推断
    private var sensorColorTemp = 5000f

    // 平滑滤波相关变量
    private var smoothedLux = 500f // 平滑后的光照值，初始正常室内光
    private var currentBrightness = 0.8f
    private var currentHighlight = 1.0f
    private var currentColorTemp = 5000
    private var currentIsNightMode = false

    // 迟滞阈值，大幅拉大避免临界点来回跳变
    // 调低进入阈值：头部安装后传感器被遮挡，正常室内光也会偏低，避免误判为暗光
    private val LOW_LIGHT_ENTER = 30f   // 低于30lux才进入暗光（原60）
    private val LOW_LIGHT_EXIT = 80f   // 高于80lux才退出暗光（原150）
    private val HIGH_LIGHT_ENTER = 1500f // 高于1500lux才进入强光
    private val HIGH_LIGHT_EXIT = 600f   // 低于600lux才退出强光

    // 更新节流，避免频繁计算
    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 100L // 最快100ms更新一次

    fun start() {
        try {
            lightSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            tempSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ambient light sensor start failed, skip light adaptation", e)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 节流：100ms内只处理一次，减少计算量
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return
        lastUpdateTime = now

        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val lux = event.values[0]
                updateLightConfig(lux)
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                // 色温传感器单位是开尔文
                sensorColorTemp = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateLightConfig(lux: Float) {
        // 1. 光照值低通平滑滤波，系数0.03，极慢响应完全无抖动
        smoothedLux = smoothedLux * 0.97f + lux * 0.03f

        // 2. 带迟滞阈值判断光照区间，避免临界点来回跳变
        val targetBrightness: Float
        val targetHighlight: Float
        val targetColorTemp: Int
        val targetIsNightMode: Boolean

        when {
            // 强光区间：进入阈值1200，退出阈值800
            smoothedLux > HIGH_LIGHT_ENTER || (smoothedLux > HIGH_LIGHT_EXIT && currentIsNightMode.not() && currentHighlight < 0.6f) -> {
                targetBrightness = 1.0f
                targetHighlight = 0.5f
                targetColorTemp = if (tempSensor != null) sensorColorTemp.toInt() else 6500
                targetIsNightMode = false
            }
            // 暗光区间：进入阈值30，退出阈值80
            smoothedLux < LOW_LIGHT_ENTER || (smoothedLux < LOW_LIGHT_EXIT && currentIsNightMode) -> {
                targetBrightness = 0.5f  // 暗光下也保持50%亮度，避免太暗像困倦
                targetHighlight = 0.7f
                targetColorTemp = if (tempSensor != null) sensorColorTemp.toInt() else 3000
                targetIsNightMode = true
            }
            // 正常室内光
            else -> {
                targetBrightness = 0.8f
                targetHighlight = 1.0f
                targetColorTemp = if (tempSensor != null) sensorColorTemp.toInt() else 5000
                targetIsNightMode = false
            }
        }

        // 3. 参数平滑插值过渡，每次只变化1.5%，极致柔和无任何突变
        currentBrightness += (targetBrightness - currentBrightness) * 0.015f
        currentHighlight += (targetHighlight - currentHighlight) * 0.015f
        currentColorTemp += ((targetColorTemp - currentColorTemp) * 0.015f).toInt()
        currentIsNightMode = targetIsNightMode

        // 4. 更新配置
        _lightConfig.value = LightConfig(
            brightness = currentBrightness.coerceIn(0.2f, 1.0f),
            highlight = currentHighlight.coerceIn(0.3f, 1.2f),
            colorTemp = currentColorTemp.coerceIn(2700, 6500),
            isNightMode = currentIsNightMode
        )
    }

    companion object {
        private val DEFAULT_CONFIG = LightConfig(
            brightness = 0.8f,
            highlight = 1.0f,
            colorTemp = 5000,
            isNightMode = false
        )

        @Volatile
        private var instance: AmbientLightManager? = null

        fun getInstance(context: Context): AmbientLightManager {
            return instance ?: synchronized(this) {
                instance ?: AmbientLightManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
