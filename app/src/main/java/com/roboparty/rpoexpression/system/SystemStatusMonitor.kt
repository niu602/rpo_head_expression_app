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

/**
 * 系统状态监控器
 * 监听系统电量、充电状态等变化，以待机修饰方式反映到眼部视觉
 *
 * 【设计原则】不改变表情类型，仅在IDLE待机状态下叠加视觉效果
 * - 保持人脸识别结束后强制待机的原有逻辑完全不变
 * - 不干扰说话、思考、识别等任何业务表情
 * - 仅通过待机状态的颜色/闪烁/呼吸效果反映电量状态
 *
 * 电量状态映射（仅IDLE时生效）：
 * - 正常电量(>20%)：无修饰，默认待机外观
 * - 低电量(<20%)：橙黄色光晕 + 缓慢脉冲闪烁
 * - 充电中：绿色光晕 + 呼吸效果
 */
class SystemStatusMonitor private constructor() {

    companion object {
        private const val TAG = "SystemStatusMonitor"

        // 低电量阈值（百分比）
        private const val LOW_BATTERY_THRESHOLD = 20

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
    private var currentBatteryState = ExpressionManager.IdleBatteryState.NORMAL

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
        Log.d(TAG, "SystemStatusMonitor initialized (idle overlay mode)")
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

            // 启动后立即获取一次当前电量状态
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
            // 停止时恢复正常状态
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

        currentBatteryLevel = batteryPercent
        isCharging = charging

        // 计算目标电量修饰状态
        val targetState = determineBatteryState()

        // 状态变化时才更新
        if (targetState != currentBatteryState) {
            currentBatteryState = targetState
            // 关键：仅设置待机修饰状态，不切换表情类型
            // 表情类型保持不变，渲染器会在IDLE时自动叠加效果
            expressionManager.setIdleBatteryState(targetState)
            Log.d(TAG, "Battery state updated: level=$batteryPercent%, charging=$charging, overlay=$targetState")
        }
    }

    /**
     * 根据当前系统状态判断待机修饰类型
     * 优先级：充电中 > 低电量 > 正常
     */
    private fun determineBatteryState(): ExpressionManager.IdleBatteryState {
        return when {
            // 充电中：绿色呼吸效果
            isCharging -> ExpressionManager.IdleBatteryState.CHARGING
            // 低电量：橙黄闪烁
            currentBatteryLevel < LOW_BATTERY_THRESHOLD -> ExpressionManager.IdleBatteryState.LOW_BATTERY
            // 正常：无修饰
            else -> ExpressionManager.IdleBatteryState.NORMAL
        }
    }

    /**
     * 获取当前电量百分比
     */
    fun getBatteryLevel(): Int = currentBatteryLevel

    /**
     * 获取当前是否在充电
     */
    fun isCharging(): Boolean = isCharging

    /**
     * 手动刷新状态
     */
    fun refreshStatus() {
        val targetState = determineBatteryState()
        if (targetState != currentBatteryState) {
            currentBatteryState = targetState
            expressionManager.setIdleBatteryState(targetState)
        }
    }
}
