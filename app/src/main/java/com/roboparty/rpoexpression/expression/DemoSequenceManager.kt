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
import kotlinx.coroutines.isActive
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
    /**
     * 触发完整演示序列（手柄Select键/ADB广播触发）
     * 循环展示所有表情、特效、风格，直到手动停止
     */
    fun triggerStandardDemo() {
        if (isDemoRunning) {
            Log.w(TAG, "Demo already running, ignoring trigger")
            return
        }

        // 确保开机状态
        expressionManager.powerOn()

        demoJob = scope.launch {
            isDemoRunning = true
            Log.d(TAG, "Starting full demo sequence")

            try {
                // 先回到待机
                expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                delay(800)

                // 循环演示
                while (isActive && isDemoRunning) {
                    // ========== 第一部分：所有表情+对应特效 ==========
                    // 1. 思考表情
                    expressionManager.requestExpression(ExpressionType.THINKING, duration = 1500, force = true)
                    delay(1800)
                    if (!isDemoRunning) break

                    // 2. 识别中（扫描线特效）
                    expressionManager.requestExpression(ExpressionType.RECOGNIZING, duration = 2500, force = true)
                    delay(2800)
                    if (!isDemoRunning) break

                    // 3. 应答（月牙+星光特效）
                    expressionManager.requestExpression(ExpressionType.RESPONSE, duration = 1500, force = true)
                    delay(1800)
                    if (!isDemoRunning) break

                    // 4. 说话表情
                    expressionManager.requestExpression(ExpressionType.SPEAKING, duration = 2500, force = true)
                    delay(2800)
                    if (!isDemoRunning) break

                    // 5. 开心（星星特效）
                    expressionManager.requestExpression(ExpressionType.HAPPY, duration = 1800, force = true)
                    delay(2100)
                    if (!isDemoRunning) break

                    // 6. 疑惑（问号特效+瞳孔上移）
                    expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 1800, force = true)
                    delay(2100)
                    if (!isDemoRunning) break

                    // 7. 害羞（粉色描边特效）
                    expressionManager.requestExpression(ExpressionType.SHY, duration = 1500, force = true)
                    delay(1800)
                    if (!isDemoRunning) break

                    // 8. 告警（故障块特效）
                    expressionManager.requestExpression(ExpressionType.ALERT, duration = 2000, force = true)
                    delay(2300)
                    if (!isDemoRunning) break

                    // 回到待机
                    expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                    delay(800)
                    if (!isDemoRunning) break

                    // ========== 第二部分：自动眨眼演示 ==========
                    expressionManager.performBlink()
                    delay(600)
                    expressionManager.performBlink()
                    delay(800)
                    if (!isDemoRunning) break

                    // ========== 第三部分：风格切换展示 ==========
                    // 男款风格
                    expressionManager.setStyle(ExpressionStyle.MALE)
                    expressionManager.requestExpression(ExpressionType.HAPPY, duration = 1500, force = true)
                    delay(1800)
                    if (!isDemoRunning) break

                    // 女款风格（默认）
                    expressionManager.setStyle(ExpressionStyle.FEMALE)
                    expressionManager.requestExpression(ExpressionType.HAPPY, duration = 1500, force = true)
                    delay(1800)
                    if (!isDemoRunning) break

                    // 回到待机，开始下一轮循环
                    expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                    delay(1000)
                }

            } finally {
                // 结束后回到女款默认风格和待机
                expressionManager.setStyle(ExpressionStyle.FEMALE)
                expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                isDemoRunning = false
                Log.d(TAG, "Demo sequence finished")
            }
        }
    }

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
