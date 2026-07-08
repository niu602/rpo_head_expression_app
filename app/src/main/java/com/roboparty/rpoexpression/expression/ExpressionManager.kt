/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 表情管理器 - 核心状态机
 * 负责表情状态的优先级调度、自动眨眼、状态回落、平滑过渡
 *
 * 设计要点：
 * 1. 基于优先级的状态抢占机制
 * 2. 待机状态下随机眨眼
 * 3. 临时表情（应答、眨眼）自动回落
 * 4. 协程驱动的状态更新，低延迟响应
 */
class ExpressionManager private constructor() {

    companion object {
        private const val TAG = "ExpressionManager"
        private const val BLINK_MIN_INTERVAL = 3000L  // 眨眼最小间隔3秒
        private const val BLINK_MAX_INTERVAL = 8000L  // 眨眼最大间隔8秒
        private const val TRANSITION_DURATION = 100L  // 表情切换过渡时长100ms，满足≤100ms要求
        private const val DEBOUNCE_INTERVAL = 100L  // 防抖间隔100ms，短时间重复指令忽略

        @Volatile
        private var instance: ExpressionManager? = null

        fun getInstance(): ExpressionManager {
            return instance ?: synchronized(this) {
                instance ?: ExpressionManager().also { instance = it }
            }
        }
    }

    // 上次表情切换时间，用于防抖
    private var lastSwitchTime = 0L

    // 表情状态流 - UI层订阅此流进行渲染
    private val _expressionState = MutableStateFlow(ExpressionState(type = ExpressionType.IDLE))
    val expressionState: StateFlow<ExpressionState> = _expressionState.asStateFlow()

    // 上一个表情状态，用于过渡动画
    private var previousState: ExpressionState? = null

    // 管理器运行状态
    private val isRunning = AtomicBoolean(false)
    private var managerJob: Job? = null
    private var blinkJob: Job? = null
    private var autoFallbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 待执行的表情序列（Demo演示用）
    private val expressionSequence = mutableListOf<ExpressionType>()
    private var isPlayingSequence = false

    /**
     * 启动表情管理器
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "ExpressionManager started")
            startStateUpdateLoop()
            scheduleNextBlink()
        }
    }

    /**
     * 停止表情管理器
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.d(TAG, "ExpressionManager stopped")
            managerJob?.cancel()
            blinkJob?.cancel()
            scope.coroutineContext.cancelChildren()
        }
    }

    /**
     * 请求切换表情 - 外部模块调用入口
     *
     * @param type 目标表情类型
     * @param duration 自定义持续时长，null则使用默认时长
     * @param force 是否强制切换（忽略优先级）
     * @return 是否切换成功
     */
    fun requestExpression(
        type: ExpressionType,
        duration: Long? = null,
        force: Boolean = false
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        val currentState = _expressionState.value

        // 防抖检查：非强制切换，100ms内重复请求直接忽略
        if (!force && currentTime - lastSwitchTime < DEBOUNCE_INTERVAL) {
            Log.d(TAG, "Expression $type debounced")
            return false
        }

        // 优先级检查：非强制模式下，低优先级不能抢占高优先级
        if (!force && type.priority < currentState.type.priority) {
            Log.d(TAG, "Expression $type rejected, target priority: \${type.priority},current priority higher: ${currentState.type}")
            return false
        }
        // 相同表情不重复触发（眨眼除外）
        if (type == currentState.type && type != ExpressionType.BLINK) {
            return true
        }

        // 更新切换时间
        lastSwitchTime = currentTime

        Log.d(TAG, "Switching expression: ${currentState.type} -> $type")

        // 保存上一状态用于过渡
        previousState = currentState

        // 应用新状态
        val actualDuration = duration ?: type.defaultDuration
        val newState = ExpressionState(
            type = type,
            startTime = System.currentTimeMillis(),
            duration = actualDuration,
            isLooping = !type.isAutoFallback,
            transitionProgress = 0f
        )

        _expressionState.value = newState

        // 如果是应答反馈等临时状态，启动回落计时
        if (type.isAutoFallback && type != ExpressionType.BLINK) {
            // 取消之前的回落协程，避免多个回落任务冲突
            autoFallbackJob?.cancel()
            autoFallbackJob = scope.launch {
                delay(actualDuration)
                // 只有当前表情还是这个临时表情，才回落到IDLE，避免覆盖新切换的表情
                if (_expressionState.value.type == type) {
                    fallbackToIdle()
                }
            }
        } else {
            // 切换到常驻表情时，取消自动回落
            autoFallbackJob?.cancel()
        }

        return true
    }

    /**
     * 播放表情序列 - Demo演示流程使用
     * 按顺序执行一系列表情，每个表情使用默认时长
     */
    fun playExpressionSequence(sequence: List<ExpressionType>) {
        if (isPlayingSequence) {
            Log.w(TAG, "Already playing sequence, ignoring new request")
            return
        }

        scope.launch {
            isPlayingSequence = true
            expressionSequence.clear()
            expressionSequence.addAll(sequence)

            for (exprType in sequence) {
                requestExpression(exprType, force = true)
                val waitTime = if (exprType.isAutoFallback) {
                    exprType.defaultDuration
                } else {
                    // 非自动回落表情等待1.5秒
                    1500L
                }
                delay(waitTime)
            }

            isPlayingSequence = false
            fallbackToIdle()
        }
    }

    /**
     * 停止当前表情，回落到待机状态
     */
    fun clearExpression() {
        isPlayingSequence = false
        expressionSequence.clear()
        fallbackToIdle()
    }

    /**
     * 状态更新主循环
     * 60fps更新动画进度和过渡效果
     */
    private fun startStateUpdateLoop() {
        managerJob = scope.launch {
            while (isActive && isRunning.get()) {
                val currentState = _expressionState.value

                // 更新过渡进度
                val elapsed = System.currentTimeMillis() - currentState.startTime
                val transitionProgress = (elapsed.toFloat() / TRANSITION_DURATION).coerceIn(0f, 1f)

                // 更新动画进度
                val updatedState = currentState
                    .updateProgress()
                    .copy(transitionProgress = transitionProgress)

                _expressionState.value = updatedState

                // 检查眨眼是否结束
                if (currentState.type == ExpressionType.BLINK && currentState.isExpired()) {
                    fallbackToIdle()
                }

                // 60fps
                delay(16L)
            }
        }
    }

    /**
     * 调度下一次随机眨眼
     */
    private fun scheduleNextBlink() {
        blinkJob = scope.launch {
            while (isActive && isRunning.get()) {
                // 随机间隔
                val interval = Random.nextLong(BLINK_MIN_INTERVAL, BLINK_MAX_INTERVAL)
                delay(interval)

                // 只有在待机状态才眨眼
                if (_expressionState.value.type == ExpressionType.IDLE) {
                    requestExpression(ExpressionType.BLINK)
                }
            }
        }
    }

    /**
     * 回落到待机状态
     */
    private fun fallbackToIdle() {
        if (_expressionState.value.type != ExpressionType.IDLE) {
            Log.d(TAG, "Falling back to IDLE")
            previousState = _expressionState.value
            _expressionState.value = ExpressionState(
                type = ExpressionType.IDLE,
                startTime = System.currentTimeMillis(),
                transitionProgress = 0f
            )
        }
    }
}
