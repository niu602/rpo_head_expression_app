/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionMode
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.system.SystemStatusMonitor
import com.roboparty.rpoexpression.util.PersonaSwitchManager

/**
 * 表情控制广播接收器
 * 支持通过ADB指令远程控制表情，方便电脑端测试，无需操作手机屏幕
 *
 * ADB指令示例：
 * 切换表情：adb shell am broadcast -a com.roboparty.rpoexpression.ACTION_SET_EXPRESSION --es type happy
 * 清空表情：adb shell am broadcast -a com.roboparty.rpoexpression.ACTION_CLEAR_EXPRESSION
 */
class ExpressionControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ExpressionControl"
        const val ACTION_SET_EXPRESSION = "com.roboparty.rpoexpression.ACTION_SET_EXPRESSION"
        const val ACTION_CLEAR_EXPRESSION = "com.roboparty.rpoexpression.ACTION_CLEAR_EXPRESSION"
        const val ACTION_SET_STYLE = "com.roboparty.rpoexpression.ACTION_SET_STYLE"
        const val ACTION_SET_INTENSITY = "com.roboparty.rpoexpression.ACTION_SET_INTENSITY"
        // ===== 系统状态测试专用 Action =====
        const val ACTION_TEST_LOW_BATTERY = "com.roboparty.rpoexpression.ACTION_TEST_LOW_BATTERY"
        const val ACTION_TEST_CHARGING = "com.roboparty.rpoexpression.ACTION_TEST_CHARGING"
        const val ACTION_TEST_ALERT = "com.roboparty.rpoexpression.ACTION_TEST_ALERT"
        const val ACTION_TEST_SLEEPING = "com.roboparty.rpoexpression.ACTION_TEST_SLEEPING"
        const val ACTION_REFRESH_SYSTEM_STATUS = "com.roboparty.rpoexpression.ACTION_REFRESH_SYSTEM_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val expressionManager = ExpressionManager.getInstance()
        when (intent.action) {
            ACTION_SET_EXPRESSION -> {
                val typeStr = intent.getStringExtra("type") ?: return
                val duration = intent.getLongExtra("duration", -1L)
                val force = intent.getBooleanExtra("force", false)

                // ===== 模式切换指令解析（优先处理，不进入表情类型查找） =====
                when (typeStr.lowercase().trim()) {
                    "mode:energy", "能量模式" -> {
                        Log.d(TAG, "Switch to ENERGY mode")
                        expressionManager.setMode(ExpressionMode.ENERGY)
                        return
                    }
                    "mode:traditional", "传统模式" -> {
                        Log.d(TAG, "Switch to TRADITIONAL mode")
                        expressionManager.setMode(ExpressionMode.TRADITIONAL)
                        return
                    }
                    "mode:toggle", "切换模式" -> {
                        Log.d(TAG, "Toggle expression mode")
                        expressionManager.toggleMode()
                        return
                    }
                }

                val type = ExpressionType.fromCommand(typeStr)
                if (type == null) {
                    Log.e(TAG, "Unknown expression type: $typeStr")
                    return
                }

                val intensity = intent.getIntExtra("intensity", 100).coerceIn(0, 100)
                Log.d(TAG, "Set expression: $type, duration: $duration, force: $force, intensity: $intensity")
                if (duration > 0) {
                    expressionManager.requestExpression(type, intensity = intensity, duration = duration, force = force)
                } else {
                    expressionManager.requestExpression(type, intensity = intensity, force = force)
                }
            }
            ACTION_CLEAR_EXPRESSION -> {
                Log.d(TAG, "Clear expression, back to idle")
                expressionManager.clearExpression()
            }
            ACTION_SET_STYLE -> {
                val styleStr = intent.getStringExtra("style")?.lowercase()
                val style = when (styleStr) {
                    "male", "man", "boy", "0", "男", "男款" -> ExpressionStyle.MALE
                    "female", "woman", "girl", "1", "女", "女款" -> ExpressionStyle.FEMALE
                    else -> {
                        Log.e(TAG, "Unknown style: $styleStr")
                        return
                    }
                }
                Log.d(TAG, "Set style: $style")
                // 申请切换锁，确保切换过程原子性
                if (PersonaSwitchManager.tryStartSwitch("tcpSetStyle")) {
                    try {
                        expressionManager.setStyle(style)
                    } finally {
                        PersonaSwitchManager.finishSwitch("tcpSetStyle")
                    }
                } else {
                    Log.w(TAG, "tcpSetStyle: switch in progress, drop request")
                }
            }
            ACTION_SET_INTENSITY -> {
                val intensity = intent.getIntExtra("intensity", 100).coerceIn(0, 100)
                Log.d(TAG, "Set global intensity: $intensity")
                val currentState = expressionManager.expressionState.value
                expressionManager.requestExpression(
                    type = currentState.type,
                    intensity = intensity,
                    force = true
                )
            }
            // ===== 系统状态测试专用 Action =====
            ACTION_TEST_LOW_BATTERY -> {
                Log.d(TAG, "Test: trigger LOW_BATTERY expression")
                expressionManager.requestExpression(ExpressionType.LOW_BATTERY, force = true)
            }
            ACTION_TEST_CHARGING -> {
                Log.d(TAG, "Test: trigger CHARGING expression")
                expressionManager.requestExpression(ExpressionType.CHARGING, force = true)
            }
            ACTION_TEST_ALERT -> {
                Log.d(TAG, "Test: trigger ALERT expression")
                expressionManager.requestExpression(ExpressionType.ALERT, force = true)
            }
            ACTION_TEST_SLEEPING -> {
                Log.d(TAG, "Test: trigger SLEEPING expression")
                expressionManager.requestExpression(ExpressionType.SLEEPING, force = true)
            }
            ACTION_REFRESH_SYSTEM_STATUS -> {
                Log.d(TAG, "Refresh system status monitor")
                SystemStatusMonitor.getInstance().refreshStatus()
            }
        }
    }
}
