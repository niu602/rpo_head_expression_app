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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IMU传感器管理器 - 预留接口
 * 支持后续头部姿态联动表情的加分项开发
 * 当前仅预留接口和数据结构，后续可实现姿态识别、摇头/点头表情联动
 */
class ImuManager private constructor(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ImuManager"

        @Volatile
        private var instance: ImuManager? = null

        fun getInstance(context: Context): ImuManager {
            return instance ?: synchronized(this) {
                instance ?: ImuManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 头部姿态数据类
     */
    data class HeadPose(
        val pitch: Float = 0f, // 俯仰角（点头），单位度
        val roll: Float = 0f,  // 翻滚角（歪头），单位度
        val yaw: Float = 0f,   // 偏航角（摇头），单位度
        val isShaking: Boolean = false, // 是否摇头
        val isNodding: Boolean = false  // 是否点头
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // 姿态数据流，后续表情模块可订阅此流实现姿态联动
    private val _headPose = MutableStateFlow(HeadPose())
    val headPose: StateFlow<HeadPose> = _headPose.asStateFlow()

    // 姿态变化回调，后续可注册回调触发表情
    private var poseListener: ((HeadPose) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isRunning = false

    /**
     * 注册姿态变化监听器
     */
    fun setPoseListener(listener: (HeadPose) -> Unit) {
        this.poseListener = listener
    }

    /**
     * 启动IMU监听（预留，当前默认不启动，后续功能开发时调用）
     */
    fun start() {
        if (isRunning) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        isRunning = true
        Log.d(TAG, "IMU sensor started (reserved interface)")
    }

    /**
     * 停止IMU监听
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        isRunning = false
        Log.d(TAG, "IMU sensor stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            // TODO: 后续实现姿态解算，计算pitch/roll/yaw，识别点头/摇头动作
            // 当前仅预留接口，不做具体计算
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // 加速度数据预留
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // 陀螺仪数据预留
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化无需处理
    }
}
