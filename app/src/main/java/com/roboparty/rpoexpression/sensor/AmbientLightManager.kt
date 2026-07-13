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
import kotlin.math.max
import kotlin.math.min

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
    private var currentColorTemp = 5000f

    fun start() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        tempSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val lux = event.values[0]
                updateLightConfig(lux)
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                // 色温传感器单位是开尔文
                currentColorTemp = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateLightConfig(lux: Float) {
        val config = when {
            // 强光环境 >1000lux：提高对比度，降低高光，避免过曝
            lux > 1000f -> LightConfig(
                brightness = 1.0f,
                highlight = 0.5f,
                colorTemp = if (tempSensor != null) currentColorTemp.toInt() else 6500,
                isNightMode = false
            )
            // 暗光环境 <100lux：降低亮度，开启夜光模式，暖色调低蓝光
            lux < 100f -> LightConfig(
                brightness = 0.3f,
                highlight = 0.7f,
                colorTemp = if (tempSensor != null) currentColorTemp.toInt() else 3000,
                isNightMode = true
            )
            // 正常室内光 100-1000lux：正常显示
            else -> LightConfig(
                brightness = 0.8f,
                highlight = 1.0f,
                colorTemp = if (tempSensor != null) currentColorTemp.toInt() else 5000,
                isNightMode = false
            )
        }
        _lightConfig.value = config
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
