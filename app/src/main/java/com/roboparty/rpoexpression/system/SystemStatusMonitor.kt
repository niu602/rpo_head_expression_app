/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统状态监控器
 * 监听系统电量、温度、充电状态等变化，自动触发对应系统表情
 *
 * 触发规则：
 * 【IDLE修饰层（不抢占业务表情）】
 * - 正常电量(>20%)：无修饰，默认待机外观
 * - 低电量(5%~20%)：IDLE时橙黄色光晕 + 缓慢脉冲闪烁
 * - 充电中：IDLE时绿色光晕 + 呼吸效果
 *
 * 【ALERT告警表情（最高优先级，抢占所有表情）】
 * - 电量极低(<5%)且未充电：红色闪烁告警，提示即将关机
 * - 电池温度过高(>45℃)：红色闪烁告警，提示过热
 * - 告警条件解除后自动恢复IDLE
 */
class SystemStatusMonitor private constructor() {

    companion object {
        private const val TAG = "SystemStatusMonitor"

        // 低电量修饰阈值（IDLE下黄光）
        private const val LOW_BATTERY_THRESHOLD = 20
        // 极低电量告警阈值（触发ALERT）
        private const val CRITICAL_BATTERY_THRESHOLD = 5
        // 电池高温告警阈值（摄氏度）
        private const val OVER_TEMPERATURE_THRESHOLD = 45f
        // 告警冷却时间（避免反复触发）
        private const val ALERT_COOLDOWN_MS = 10000L

        @Volatile
        private var instance: SystemStatusMonitor? = null

        fun getInstance(): SystemStatusMonitor {
            return instance ?: synchronized(this) {
                instance ?: SystemStatusMonitor().also { instance = it }
            }
        }
    }

    private lateinit var context: Context
    private lateinit var expressionManager: ExpressionManager
    private var isMonitoring = false

    // 当前系统状态
    private var currentBatteryLevel = 100    // 当前电量百分比
    private var isCharging = false           // 是否正在充电
    private var batteryTemperature = 25f     // 电池温度（℃）
    private var currentBatteryState = ExpressionManager.IdleBatteryState.NORMAL

    // 告警状态
    private val isAlertActive = AtomicBoolean(false)
    private var lastAlertTime = 0L
    private var currentAlertReason = ""

    // 电量变化广播接收器
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                handleBatteryChanged(intent)
            }
        }
    }

    /**
     * 初始化监控器
     * @param context 应用上下文
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        this.expressionManager = ExpressionManager.getInstance()
        Log.d(TAG, "SystemStatusMonitor initialized (battery overlay + alert triggers)")
    }

    /**
     * 开始监控系统状态
     */
    fun start() {
        if (isMonitoring) {
            Log.w(TAG, "Already monitoring, skip start")
            return
        }

        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
            isMonitoring = true
            Log.d(TAG, "System status monitoring started")

            // 启动后立即获取一次当前状态
            val currentBatteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            currentBatteryIntent?.let { handleBatteryChanged(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring", e)
        }
    }

    /**
     * 停止监控系统状态
     */
    fun stop() {
        if (!isMonitoring) return

        try {
            context.unregisterReceiver(batteryReceiver)
            isMonitoring = false
            clearAlert()
            expressionManager.setIdleBatteryState(ExpressionManager.IdleBatteryState.NORMAL)
            currentBatteryState = ExpressionManager.IdleBatteryState.NORMAL
            Log.d(TAG, "System status monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop monitoring", e)
        }
    }

    /**
     * 处理电量变化广播
     */
    private fun handleBatteryChanged(intent: Intent) {
        // 获取电量等级
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPercent = if (level != -1 && scale != -1) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            100
        }

        // 获取充电状态
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }

        // 获取电池温度（单位是1/10摄氏度）
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10f

        currentBatteryLevel = batteryPercent
        isCharging = charging
        batteryTemperature = temperature

        // 1. 先检查是否需要触发/解除ALERT告警（高优先级）
        checkAndTriggerAlert()

        // 2. 非告警状态下更新IDLE修饰层
        if (!isAlertActive.get()) {
            updateBatteryOverlay()
        }

        Log.d(TAG, "Battery update: level=$batteryPercent%, charging=$charging, temp=$temperature℃, alert=${isAlertActive.get()}")
    }

    /**
     * 检查并触发ALERT告警
     * 触发条件（满足任一即触发）：
     * 1. 电量<5% 且未充电
     * 2. 电池温度>45℃
     * 解除条件：所有告警条件都不满足
     */
    private fun checkAndTriggerAlert() {
        val needCriticalBatteryAlert = !isCharging && currentBatteryLevel <= CRITICAL_BATTERY_THRESHOLD
        val needOverheatAlert = batteryTemperature >= OVER_TEMPERATURE_THRESHOLD
        val needAlert = needCriticalBatteryAlert || needOverheatAlert

        when {
            // 需要触发告警
            needAlert && !isAlertActive.get() -> {
                val now = System.currentTimeMillis()
                if (now - lastAlertTime < ALERT_COOLDOWN_MS) return

                isAlertActive.set(true)
                lastAlertTime = now
                currentAlertReason = when {
                    needOverheatAlert -> "OVERHEAT"
                    needCriticalBatteryAlert -> "CRITICAL_BATTERY"
                    else -> "UNKNOWN"
                }

                // 触发ALERT表情（最高优先级，force=true抢占所有表情）
                expressionManager.requestExpression(
                    type = ExpressionType.ALERT,
                    intensity = 100,
                    force = true
                )
                Log.w(TAG, "⚠️ ALERT triggered! reason=$currentAlertReason, level=$currentBatteryLevel%, temp=$batteryTemperature℃")
            }

            // 告警条件解除，清除告警
            !needAlert && isAlertActive.get() -> {
                clearAlert()
                Log.i(TAG, "✅ Alert cleared, conditions recovered")
            }
        }
    }

    /**
     * 清除告警，恢复IDLE
     */
    private fun clearAlert() {
        if (isAlertActive.compareAndSet(true, false)) {
            currentAlertReason = ""
            // 告警解除后回到IDLE
            expressionManager.requestExpression(
                type = ExpressionType.IDLE,
                force = true
            )
            // 更新IDLE修饰状态
            updateBatteryOverlay()
        }
    }

    /**
     * 更新IDLE状态下的电量修饰层
     * 优先级：充电中 > 低电量 > 正常
     */
    private fun updateBatteryOverlay() {
        val targetState = when {
            isCharging -> ExpressionManager.IdleBatteryState.CHARGING
            currentBatteryLevel < LOW_BATTERY_THRESHOLD -> ExpressionManager.IdleBatteryState.LOW_BATTERY
            else -> ExpressionManager.IdleBatteryState.NORMAL
        }

        if (targetState != currentBatteryState) {
            currentBatteryState = targetState
            expressionManager.setIdleBatteryState(targetState)
            Log.d(TAG, "Battery overlay updated: $targetState")
        }
    }

    /**
     * 外部触发：通信链路全部断开告警
     */
    fun triggerCommunicationLostAlert() {
        if (isAlertActive.get()) return
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < ALERT_COOLDOWN_MS) return

        isAlertActive.set(true)
        lastAlertTime = now
        currentAlertReason = "COMM_LOST"
        expressionManager.requestExpression(ExpressionType.ALERT, 100, force = true)
        Log.w(TAG, "⚠️ ALERT triggered: All communication links lost!")
    }

    /**
     * 外部触发：进入升级状态
     */
    fun enterUpdatingState() {
        isAlertActive.set(false) // 升级状态优先级同ALERT，互斥
        expressionManager.requestExpression(ExpressionType.UPDATING, 100, force = true)
        Log.i(TAG, "🔄 Entering UPDATING state")
    }

    /**
     * 外部触发：升级完成，恢复IDLE
     */
    fun exitUpdatingState() {
        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
        updateBatteryOverlay()
        Log.i(TAG, "✅ UPDATING finished, back to IDLE")
    }

    /**
     * 通信恢复，清除通信告警
     */
    fun clearCommunicationAlert() {
        if (currentAlertReason == "COMM_LOST") {
            clearAlert()
        }
    }

    /**
     * 获取当前电量百分比
     */
    fun getBatteryLevel(): Int = currentBatteryLevel

    /**
     * 获取当前电池温度（℃）
     */
    fun getBatteryTemperature(): Float = batteryTemperature

    /**
     * 获取当前是否在充电
     */
    fun isCharging(): Boolean = isCharging

    /**
     * 是否处于告警状态
     */
    fun isAlertActive(): Boolean = isAlertActive.get()

    /**
     * 获取当前告警原因
     */
    fun getAlertReason(): String = currentAlertReason

    /**
     * 手动刷新状态
     */
    fun refreshStatus() {
        if (!isAlertActive.get()) {
            updateBatteryOverlay()
        }
        checkAndTriggerAlert()
    }
}
