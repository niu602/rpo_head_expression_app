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
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.expression.ExpressionType

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
    }

    override fun onReceive(context: Context, intent: Intent) {
        val expressionManager = ExpressionManager.getInstance()
        when (intent.action) {
            ACTION_SET_EXPRESSION -> {
                val typeStr = intent.getStringExtra("type") ?: return
                val duration = intent.getLongExtra("duration", -1L)
                val force = intent.getBooleanExtra("force", false)

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
                    "cartoon", "0" -> ExpressionStyle.CARTOON
                    "cyberpunk", "cyber", "1" -> ExpressionStyle.CYBERPUNK
                    "minimal", "line", "2" -> ExpressionStyle.MINIMAL
                    "pixel", "8bit", "3" -> ExpressionStyle.PIXEL
                    else -> {
                        Log.e(TAG, "Unknown style: $styleStr")
                        return
                    }
                }
                Log.d(TAG, "Set style: $style")
                expressionManager.setStyle(style)
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
        }
    }
}
