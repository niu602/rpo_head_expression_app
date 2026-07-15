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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * IMU传感器管理器（安装校准优化版）
 * 支持头部姿态联动表情，自动抵消安装倾角，抬头低头对称触发
 */
class ImuManager private constructor(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ImuManager"

        enum class ScreenOrientation {
            PORTRAIT,
            LANDSCAPE
        }

        @Volatile
        private var instance: ImuManager? = null

        fun getInstance(context: Context): ImuManager {
            return instance ?: synchronized(this) {
                instance ?: ImuManager(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class HeadAction {
        NONE,
        NOD,
        BOW_DOWN,
        LOOK_UP,
        SHAKE,
        SHAKE_HARD
    }

    data class HeadPose(
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val yaw: Float = 0f,
        val currentAction: HeadAction = HeadAction.NONE
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _headPose = MutableStateFlow(HeadPose())
    val headPose: StateFlow<HeadPose> = _headPose.asStateFlow()

    private var poseListener: ((HeadPose) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isRunning = false

    // ========== 原始传感器数据 ==========
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    private var lastTimestamp = 0L

    // ========== 原始角度 ==========
    private var rawPitch = 0f
    private var rawRoll = 0f
    private var rawYaw = 0f

    // ========== 零点校准基准值 ==========
    private var basePitch = 0f
    private var baseRoll = 0f
    private var isCalibrated = false
    private var calibrateSampleCount = 0
    private val CALIBRATE_SAMPLES = 20 // 启动后采集20帧做平均校准

    // ========== 互补滤波权重（加快回正） ==========
    private val FILTER_ALPHA = 0.92f

    // ========== 更新节流 ==========
    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 80L

    // ========== 动作阈值（对称化+滞回） ==========
    private val NOD_GYRO_THRESHOLD = 2.0f
    private val SHAKE_GYRO_THRESHOLD = 2.8f
    private val SHAKE_HARD_THRESHOLD = 2f

    // 抬头/低头 触发阈值（对称，相对于校准零点）
    private val BOW_TRIGGER = 10f      // 低头触发
    private val BOW_RELEASE = 6f       // 低头解除
    private val LOOK_UP_TRIGGER = -10f // 抬头触发
    private val LOOK_UP_RELEASE = -6f  // 抬头解除

    // ========== 动作防抖与状态 ==========
    private var lastActionTime = 0L
    private val ACTION_COOLDOWN = 400L
    private val ACTION_MIN_DURATION = 150L // 动作最短保持时间
    private var lastActionStartTime = 0L
    private var lastStaticAction = HeadAction.NONE

    // ========== 静止检测 ==========
    private val STATIC_GYRO_THRESHOLD = 0.8f
    private var staticStartTime = 0L

    // ========== 屏幕方向 ==========
    private var screenOrientation = ScreenOrientation.LANDSCAPE

    fun setScreenOrientation(orientation: ScreenOrientation) {
        screenOrientation = orientation
        isCalibrated = false
        calibrateSampleCount = 0
        Log.d(TAG, "屏幕方向已切换，等待重新校准零点")
    }

    fun setPoseListener(listener: (HeadPose) -> Unit) {
        this.poseListener = listener
    }

    fun start() {
        if (isRunning) return
        try {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            isRunning = true
            isCalibrated = false
            calibrateSampleCount = 0
            Log.d(TAG, "IMU启动，开始自动校准零点")
        } catch (e: Exception) {
            Log.e(TAG, "IMU启动失败", e)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isRunning = false
        isCalibrated = false
        Log.d(TAG, "IMU已停止")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return
        lastUpdateTime = now

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val rawX = event.values[0]
                val rawY = event.values[1]
                val rawZ = event.values[2]
                when (screenOrientation) {
                    ScreenOrientation.LANDSCAPE -> {
                        accelX = rawY
                        accelY = -rawX
                        accelZ = rawZ
                    }
                    ScreenOrientation.PORTRAIT -> {
                        accelX = rawX
                        accelY = rawY
                        accelZ = rawZ
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val rawX = event.values[0]
                val rawY = event.values[1]
                val rawZ = event.values[2]
                when (screenOrientation) {
                    ScreenOrientation.LANDSCAPE -> {
                        gyroX = -rawX
                        gyroY = rawY
                        gyroZ = rawZ
                    }
                    ScreenOrientation.PORTRAIT -> {
                        gyroX = rawY
                        gyroY = rawZ
                        gyroZ = rawX
                    }
                }
            }
        }

        if (lastTimestamp == 0L) {
            lastTimestamp = event.timestamp
            return
        }

        val dt = (event.timestamp - lastTimestamp) / 1000000000f
        lastTimestamp = event.timestamp

        // 加速度计算原始角度
        val accelPitch = atan2(accelY.toDouble(), sqrt((accelX*accelX + accelZ*accelZ).toDouble())).toFloat() * 57.2958f
        val accelRoll = atan2(-accelX.toDouble(), sqrt((accelY*accelY + accelZ*accelZ).toDouble())).toFloat() * 57.2958f

        // 陀螺仪积分
        rawPitch += gyroX * dt
        rawRoll += gyroY * dt
        rawYaw += gyroZ * dt

        // 互补滤波融合
        rawPitch = FILTER_ALPHA * rawPitch + (1 - FILTER_ALPHA) * accelPitch
        rawRoll = FILTER_ALPHA * rawRoll + (1 - FILTER_ALPHA) * accelRoll

        // ===== 自动校准零点：启动前20帧取平均作为基准 =====
        if (!isCalibrated) {
            basePitch += rawPitch
            baseRoll += rawRoll
            calibrateSampleCount++
            if (calibrateSampleCount >= CALIBRATE_SAMPLES) {
                basePitch /= CALIBRATE_SAMPLES
                baseRoll /= CALIBRATE_SAMPLES
                isCalibrated = true
                Log.d(TAG, "零点校准完成，基准pitch=%.1f°".format(basePitch))
            }
            return
        }

        // 相对于基准零点的最终角度
        val pitch = rawPitch - basePitch
        val roll = rawRoll - baseRoll

        // 静止检测与自动收敛
        val isStatic = abs(gyroX) < STATIC_GYRO_THRESHOLD
                && abs(gyroY) < STATIC_GYRO_THRESHOLD
                && abs(gyroZ) < STATIC_GYRO_THRESHOLD

        if (isStatic) {
            if (staticStartTime == 0L) staticStartTime = now
            if (now - staticStartTime > 400) {
                // 静止超过400ms，角度缓慢向零点收敛，消除零漂
                rawPitch = basePitch + (rawPitch - basePitch) * 0.85f
                rawRoll = baseRoll + (rawRoll - baseRoll) * 0.85f
            }
        } else {
            staticStartTime = 0L
        }

        // 动作检测
        detectAction(pitch, roll, rawYaw, isStatic, now)
    }

    private fun detectAction(pitch: Float, roll: Float, yaw: Float, isStatic: Boolean, now: Long) {
        val inCooldown = now - lastActionTime < ACTION_COOLDOWN
        val inMinDuration = now - lastActionStartTime < ACTION_MIN_DURATION

        // 最短保持时间内不重新判定
        if (inMinDuration && _headPose.value.currentAction != HeadAction.NONE) {
            return
        }

        var action = HeadAction.NONE
        val accelMag = sqrt(accelX*accelX + accelY*accelY + accelZ*accelZ) / 9.8f

        when {
            // 1. 剧烈晃动
            accelMag > SHAKE_HARD_THRESHOLD + 1f -> {
                action = HeadAction.SHAKE_HARD
            }
            // 2. 摇头
            abs(gyroZ) > SHAKE_GYRO_THRESHOLD && !inCooldown -> {
                action = HeadAction.SHAKE
                rawYaw = 0f
            }
            // 3. 点头
            abs(gyroX) > NOD_GYRO_THRESHOLD && abs(pitch) < 20f && !inCooldown -> {
                action = HeadAction.NOD
            }
            // 4. 持续低头（滞回）
            pitch > BOW_TRIGGER || (pitch > BOW_RELEASE && lastStaticAction == HeadAction.BOW_DOWN) -> {
                action = HeadAction.BOW_DOWN
            }
            // 5. 持续抬头（滞回，对称阈值）
            pitch < LOOK_UP_TRIGGER || (pitch < LOOK_UP_RELEASE && lastStaticAction == HeadAction.LOOK_UP) -> {
                action = HeadAction.LOOK_UP
            }
        }

        // 静态姿态边沿触发：状态不变时不重复触发
        val isStaticAction = action == HeadAction.BOW_DOWN || action == HeadAction.LOOK_UP
        if (isStaticAction && action == lastStaticAction) {
            action = HeadAction.NONE
        }

        // 静止且过了最短保持时间，立刻复位
        if (isStatic && !inMinDuration) {
            action = HeadAction.NONE
        }

        if (action != HeadAction.NONE) {
            lastActionTime = now
            lastActionStartTime = now
            if (isStaticAction) lastStaticAction = action

            val pose = HeadPose(pitch, roll, yaw, action)
            _headPose.value = pose
            poseListener?.invoke(pose)

            // 最长保持1秒，超时自动复位
            scope.launch {
                delay(1000)
                _headPose.value = HeadPose(pitch, roll, yaw, HeadAction.NONE)
            }
        } else if (isStatic && _headPose.value.currentAction != HeadAction.NONE) {
            // 动作停止，立刻复位
            _headPose.value = HeadPose(pitch, roll, yaw, HeadAction.NONE)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}