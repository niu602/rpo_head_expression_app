/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 演示序列管理器
 * 处理手柄按键触发后的完整表情演示时序
 */
class DemoSequenceManager(
    private val expressionManager: ExpressionManager
) {
    companion object {
        private const val TAG = "DemoSequenceManager"
        
        // 标准演示序列：待机 -> 思考 -> 识别中 -> 应答 -> 说话 -> 待机
        val STANDARD_DEMO_SEQUENCE = listOf(
            ExpressionType.THINKING,
            ExpressionType.RECOGNIZING,
            ExpressionType.RESPONSE,
            ExpressionType.SPEAKING
        )
        
        // 每个表情停留时长（毫秒）
        private const val THINKING_DURATION = 1500L
        private const val RECOGNIZING_DURATION = 2000L
        private const val RESPONSE_DURATION = 1000L
        private const val SPEAKING_DURATION = 3000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var demoJob: Job? = null
    private var isDemoRunning = false

    /**
     * 触发标准演示序列（手柄按键触发时调用）
     */
    fun triggerStandardDemo() {
        if (isDemoRunning) {
            Log.w(TAG, "Demo already running, ignoring trigger")
            return
        }

        demoJob = scope.launch {
            isDemoRunning = true
            Log.d(TAG, "Starting standard demo sequence")

            try {
                // 1. 思考表情
                expressionManager.requestExpression(
                    ExpressionType.THINKING,
                    duration = THINKING_DURATION,
                    force = true
                )
                delay(THINKING_DURATION)

                // 2. 识别中表情
                expressionManager.requestExpression(
                    ExpressionType.RECOGNIZING,
                    duration = RECOGNIZING_DURATION,
                    force = true
                )
                delay(RECOGNIZING_DURATION)

                // 3. 应答表情
                expressionManager.requestExpression(
                    ExpressionType.RESPONSE,
                    duration = RESPONSE_DURATION,
                    force = true
                )
                delay(RESPONSE_DURATION)

                // 4. 说话表情
                expressionManager.requestExpression(
                    ExpressionType.SPEAKING,
                    duration = SPEAKING_DURATION,
                    force = true
                )
                delay(SPEAKING_DURATION)

            } finally {
                // 结束后回到待机
                expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                isDemoRunning = false
                Log.d(TAG, "Demo sequence finished")
            }
        }
    }

    /**
     * 停止当前演示序列
     */
    fun stopDemo() {
        demoJob?.cancel()
        isDemoRunning = false
        expressionManager.clearExpression()
        Log.d(TAG, "Demo stopped")
    }

    /**
     * 演示是否正在运行
     */
    fun isRunning(): Boolean = isDemoRunning
}
