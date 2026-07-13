/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

import android.util.Log
import com.roboparty.rpoexpression.animation.MicroAnimationLayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 表情管理器 - 核心状态机
 * 负责表情状态的优先级调度、状态回落、平滑过渡、自动眨眼
 *
 * 设计要点：
 * 1. 基于优先级的状态抢占机制
 * 2. 临时表情自动回落
 * 3. 协程驱动的状态更新，低延迟响应
 * 4. 支持0-100强度控制，无级渐变
 * 5. 支持多风格动态切换
 * 6. 待机状态自动眨眼
 */
class ExpressionManager private constructor() {

    companion object {
        private const val TAG = "ExpressionManager"
        private const val TRANSITION_DURATION = 400L  // 表情切换过渡时长400ms，柔和自然
        private const val DEBOUNCE_INTERVAL = 100L  // 防抖间隔100ms，短时间重复指令忽略
        private const val BLINK_INTERVAL_MIN = 2000L  // 最小眨眼间隔2秒
        private const val BLINK_INTERVAL_MAX = 5000L  // 最大眨眼间隔5秒

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

    // 当前渲染风格
    private var currentStyle = ExpressionStyle.CARTOON

    // 表情状态流 - UI层订阅此流进行渲染
    private val _expressionState = MutableStateFlow(
        ExpressionState(
            type = ExpressionType.IDLE,
            style = currentStyle
        )
    )
    val expressionState: StateFlow<ExpressionState> = _expressionState.asStateFlow()

    // 管理器运行状态
    private val isRunning = AtomicBoolean(false)
    private var managerJob: Job? = null
    private var autoFallbackJob: Job? = null
    private var blinkJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 待执行的表情序列（Demo演示用）
    private val expressionSequence = mutableListOf<ExpressionType>()
    private var isPlayingSequence = false

    // 临时表情自动回落目标
    private var temporaryFallbackTarget: ExpressionType = ExpressionType.IDLE
    private var temporaryFallbackIntensity: Int = 100

    // 是否正在眨眼
    private var isBlinking = false

    // 瞳孔偏移（视线跟踪）
    internal var pupilOffsetX = 0f
    internal var pupilOffsetY = 0f

    // 语音微动任务
    private var speechMicroMotionJob: Job? = null
    // 待机自主微动作任务
    private var idleMicroMotionJob: Job? = null
    // 瞳孔微动偏移（叠加在视线跟踪上）
    internal var microPupilOffsetX = 0f
    internal var microPupilOffsetY = 0f

    // 当前眼内特效
    private val _currentEffect = MutableStateFlow(EyeEffectType.NONE)
    val currentEffect: StateFlow<EyeEffectType> = _currentEffect.asStateFlow()

    /**
     * 根据表情自动匹配对应特效
     */
    private fun updateEffectForExpression(type: ExpressionType) {
        _currentEffect.value = when (type) {
            ExpressionType.THINKING, ExpressionType.RECOGNIZING -> EyeEffectType.THINKING_LOADING
            ExpressionType.RESPONSE -> EyeEffectType.RECOGNIZE_SUCCESS
            ExpressionType.HAPPY -> EyeEffectType.HAPPY_STARLIGHT
            ExpressionType.ANGRY, ExpressionType.ALERT, ExpressionType.SAD -> EyeEffectType.ERROR_PULSE
            else -> EyeEffectType.NONE
        }
    }

    /**
     * 设置瞳孔偏移，用于视线跟踪
     * @param offsetX X轴偏移 -1到1
     * @param offsetY Y轴偏移 -1到1
     */
    fun setPupilOffset(offsetX: Float, offsetY: Float) {
        pupilOffsetX = offsetX.coerceIn(-1f, 1f)
        pupilOffsetY = offsetY.coerceIn(-1f, 1f)
    }

    /**
     * 启动表情管理器
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "ExpressionManager started")
            startStateUpdateLoop()
            // 原自动眨眼已整合到自主微动作中，不再单独启动
            // startBlinkLoop()
            startIdleMicroMotion() // 启动时默认IDLE，开启自主微动作
            MicroAnimationLayer.getInstance().start()
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
            speechMicroMotionJob?.cancel()
            idleMicroMotionJob?.cancel()
            scope.coroutineContext.cancelChildren()
            MicroAnimationLayer.getInstance().stop()
        }
    }

    /**
     * 设置全局渲染风格
     */
    fun setStyle(style: ExpressionStyle) {
        currentStyle = style
        val currentState = _expressionState.value
        _expressionState.value = currentState.copy(style = style)
        Log.d(TAG, "Expression style changed to: $style")
    }

    /**
     * 获取当前风格
     */
    fun getCurrentStyle(): ExpressionStyle = currentStyle

    /**
     * 请求切换表情 - 外部模块调用入口
     *
     * @param type 目标表情类型
     * @param intensity 表情强度 0-100
     * @param duration 自定义持续时长，null则使用默认时长
     * @param force 是否强制切换（忽略优先级）
     * @return 是否切换成功
     */
    fun requestExpression(
        type: ExpressionType,
        intensity: Int = 100,
        duration: Long? = null,
        force: Boolean = false
    ): Boolean {
        // 眨眼是内部状态，外部不直接调用
        if (type == ExpressionType.BLINK) {
            return performBlink()
        }

        val currentTime = System.currentTimeMillis()
        val currentState = _expressionState.value

        // 防抖检查：非强制切换，100ms内重复请求直接忽略
        if (!force && currentTime - lastSwitchTime < DEBOUNCE_INTERVAL) {
            Log.d(TAG, "Expression $type debounced")
            return false
        }

        // 优先级检查：非强制模式下，低优先级不能抢占高优先级
        if (!force && type.priority < currentState.type.priority) {
            Log.d(TAG, "Expression $type rejected, target priority: ${type.priority}, current priority higher: ${currentState.type}")
            return false
        }
        // 相同表情不重复触发，但可以更新强度
        if (type == currentState.type) {
            _expressionState.value = currentState.copy(intensity = intensity.coerceIn(0, 100))
            return true
        }

        // 更新切换时间
        lastSwitchTime = currentTime

        Log.d(TAG, "Switching expression: ${currentState.type} -> $type, intensity: $intensity")

        // 应用新状态
        val actualDuration = duration ?: type.defaultDuration
        val newState = ExpressionState(
            type = type,
            intensity = intensity.coerceIn(0, 100),
            style = currentStyle,
            startTime = System.currentTimeMillis(),
            duration = actualDuration,
            transitionProgress = 0f
        )

        _expressionState.value = newState
        // 更新对应眼内特效
        updateEffectForExpression(type)

        // 启动/停止对应微动作任务
        when (type) {
            ExpressionType.SPEAKING -> {
                idleMicroMotionJob?.cancel()
                startSpeechMicroMotion()
            }
            ExpressionType.IDLE -> {
                speechMicroMotionJob?.cancel()
                startIdleMicroMotion()
            }
            else -> {
                speechMicroMotionJob?.cancel()
                idleMicroMotionJob?.cancel()
                // 重置微瞳孔偏移
                microPupilOffsetX = 0f
                microPupilOffsetY = 0f
            }
        }

        // 如果是临时状态，启动回落计时
        if (type.isAutoFallback) {
            // 取消之前的回落协程，避免多个回落任务冲突
            autoFallbackJob?.cancel()
            // 保存回落目标为设置临时表情前的表情
            temporaryFallbackTarget = currentState.type
            temporaryFallbackIntensity = currentState.intensity
            autoFallbackJob = scope.launch {
                delay(actualDuration)
                // 只有当前表情还是这个临时表情，才回落，避免覆盖新切换的表情
                if (_expressionState.value.type == type) {
                    Log.d(TAG, "Auto fallback from $type to $temporaryFallbackTarget")
                    requestExpression(temporaryFallbackTarget, temporaryFallbackIntensity, force = true)
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

    enum class BlinkType(val duration: Long) {
        NORMAL(150L),    // 正常眨眼
        SLOW(300L),      // 慢眨眼（困倦）
        FAST(80L)        // 快眨眼（说话快/紧张）
    }

    // 眨眼叠加值，0=不眨眼，1=完全闭眼
    internal var blinkOverlay = 0f

    /**
     * 执行眨眼动画（在当前表情基础上叠加眼睑闭合，不改变其他参数）
     */
    private fun performBlink(type: BlinkType = BlinkType.NORMAL): Boolean {
        if (isBlinking) return false
        val currentState = _expressionState.value

        // 高优先级表情（说话、告警等）不眨眼
        if (currentState.type.priority >= 5) return false
        // 休眠/低电量状态不眨眼
        if (currentState.type == ExpressionType.SLEEPING ||
            currentState.type == ExpressionType.LOW_BATTERY) return false

        isBlinking = true
        val blinkDuration = type.duration

        scope.launch {
            val startTime = System.currentTimeMillis()
            var elapsed = 0L

            // 逐帧更新眨眼进度，使用快闭慢睁物理曲线
            while (elapsed < blinkDuration) {
                val progress = (elapsed.toFloat() / blinkDuration).coerceIn(0f, 1f)
                blinkOverlay = EyeParameters.blinkEasing(progress)
                delay(16L) // ~60fps
                elapsed = System.currentTimeMillis() - startTime
            }

            // 动画结束自然到0，不需要强制设置
            isBlinking = false
        }
        return true
    }

    /**
     * 启动说话时语音节奏驱动微动作
     */
    private fun startSpeechMicroMotion() {
        speechMicroMotionJob?.cancel()
        speechMicroMotionJob = scope.launch {
            while (isActive && _expressionState.value.type == ExpressionType.SPEAKING) {
                // 1. 语音停顿触发快眨眼，间隔300-800ms随机，降低频率
                val blinkInterval = Random.nextLong(300, 800)
                delay(blinkInterval)
                if (isActive && !isBlinking) {
                    performBlink(BlinkType.FAST)
                }

                // 2. 瞳孔微小跳动，幅度极小，速度极慢，几乎不可察觉
                val targetX = (Random.nextFloat() - 0.5f) * 0.05f
                val targetY = (Random.nextFloat() - 0.5f) * 0.03f
                // 平滑过渡到目标偏移，速度非常慢
                val smoothTime = 200L
                val startTime = System.currentTimeMillis()
                val startX = microPupilOffsetX
                val startY = microPupilOffsetY
                var elapsed = 0L
                while (elapsed < smoothTime && isActive) {
                    val t = (elapsed.toFloat() / smoothTime).coerceIn(0f, 1f)
                    microPupilOffsetX = startX + (targetX - startX) * t
                    microPupilOffsetY = startY + (targetY - startY) * t
                    delay(16)
                    elapsed = System.currentTimeMillis() - startTime
                }
                delay(300 + Random.nextLong(300))
            }
            // 退出说话状态，极慢平滑回到中心
            val smoothTime = 300L
            val startTime = System.currentTimeMillis()
            val startX = microPupilOffsetX
            val startY = microPupilOffsetY
            var elapsed = 0L
            while (elapsed < smoothTime && isActive) {
                val t = (elapsed.toFloat() / smoothTime).coerceIn(0f, 1f)
                microPupilOffsetX = startX * (1 - t)
                microPupilOffsetY = startY * (1 - t)
                delay(16)
                elapsed = System.currentTimeMillis() - startTime
            }
            microPupilOffsetX = 0f
            microPupilOffsetY = 0f
        }
    }

    /**
     * 启动待机时自主微动作
     * 包含：随机扫视、多模式眨眼、微注视、凝神聚焦
     */
    private fun startIdleMicroMotion() {
        idleMicroMotionJob?.cancel()
        idleMicroMotionJob = scope.launch {
            while (isActive && _expressionState.value.type == ExpressionType.IDLE) {
                // 随机选择微动作类型，概率分布
                when (Random.nextInt(100)) {
                    // 20%概率：随机扫视
                    in 0..19 -> {
                        // 瞳孔缓慢移动到随机位置
                        val targetX = (Random.nextFloat() - 0.5f) * 0.6f
                        val targetY = (Random.nextFloat() - 0.5f) * 0.2f
                        val moveTime = Random.nextLong(1200, 2000) // 移动非常慢，非常柔和
                        val startTime = System.currentTimeMillis()
                        val startX = microPupilOffsetX
                        val startY = microPupilOffsetY
                        var elapsed = 0L

                        // 缓慢移动过去
                        while (elapsed < moveTime && isActive) {
                            val t = (elapsed.toFloat() / moveTime).coerceIn(0f, 1f)
                            val easedT = EyeParameters.decelerateEasing(t)
                            microPupilOffsetX = startX + (targetX - startX) * easedT
                            microPupilOffsetY = startY + (targetY - startY) * easedT
                            delay(16)
                            elapsed = System.currentTimeMillis() - startTime
                        }

                        // 停留2-3秒
                        delay(Random.nextLong(2000, 3000))

                        // 极慢移回中心
                        val backTime = Random.nextLong(1000, 1500)
                        val backStart = System.currentTimeMillis()
                        elapsed = 0L
                        while (elapsed < backTime && isActive) {
                            val t = (elapsed.toFloat() / backTime).coerceIn(0f, 1f)
                            val easedT = EyeParameters.decelerateEasing(t)
                            microPupilOffsetX = targetX + (0f - targetX) * easedT
                            microPupilOffsetY = targetY + (0f - targetY) * easedT
                            delay(16)
                            elapsed = System.currentTimeMillis() - backStart
                        }
                        microPupilOffsetX = 0f
                        microPupilOffsetY = 0f
                    }
                    // 15%概率：微注视
                    in 20..34 -> {
                        // 微注视幅度极小，几乎不可察觉
                        val targetX = (Random.nextFloat() - 0.5f) * 0.03f
                        val targetY = (Random.nextFloat() - 0.5f) * 0.02f
                        val smoothTime = 300L
                        val startTime = System.currentTimeMillis()
                        val startX = microPupilOffsetX
                        val startY = microPupilOffsetY
                        var elapsed = 0L
                        while (elapsed < smoothTime && isActive) {
                            val t = (elapsed.toFloat() / smoothTime).coerceIn(0f, 1f)
                            microPupilOffsetX = startX + (targetX - startX) * t
                            microPupilOffsetY = startY + (targetY - startY) * t
                            delay(16)
                            elapsed = System.currentTimeMillis() - startTime
                        }
                        delay(Random.nextLong(2500, 4000))
                        // 极慢平滑回到中心
                        val backTime = 300L
                        val backStart = System.currentTimeMillis()
                        elapsed = 0L
                        while (elapsed < backTime && isActive) {
                            val t = (elapsed.toFloat() / backTime).coerceIn(0f, 1f)
                            microPupilOffsetX = targetX * (1 - t)
                            microPupilOffsetY = targetY * (1 - t)
                            delay(16)
                            elapsed = System.currentTimeMillis() - backStart
                        }
                        microPupilOffsetX = 0f
                        microPupilOffsetY = 0f
                    }
                    // 10%概率：凝神聚焦
                    in 35..44 -> {
                        delay(Random.nextLong(1500, 2500))
                    }
                    // 55%概率：多模式眨眼
                    else -> {
                        val blinkType = when (Random.nextInt(100)) {
                            in 0..80 -> BlinkType.NORMAL  // 80%正常眨眼
                            in 81..95 -> BlinkType.SLOW   // 15%慢眨眼
                            else -> BlinkType.FAST        // 5%快眨眼
                        }
                        if (!isBlinking) {
                            performBlink(blinkType)
                        }
                        delay(Random.nextLong(4000, 7000)) // 眨眼间隔拉长，非常自然
                    }
                }
            }
            // 退出待机，重置微偏移
            microPupilOffsetX = 0f
            microPupilOffsetY = 0f
        }
    }

    /**
     * 自动眨眼循环
     */
    private fun startBlinkLoop() {
        blinkJob?.cancel()
        blinkJob = scope.launch {
            while (isActive && isRunning.get()) {
                // 随机间隔眨眼
                val interval = Random.nextLong(BLINK_INTERVAL_MIN, BLINK_INTERVAL_MAX)
                delay(interval)

                if (isRunning.get() && !isBlinking) {
                    performBlink()
                }
            }
        }
    }

    /**
     * 状态更新主循环
     * 60fps更新过渡动画进度
     */
    private fun startStateUpdateLoop() {
        managerJob = scope.launch {
            while (isActive && isRunning.get()) {
                val currentState = _expressionState.value
                val updatedState = currentState.updateProgress(TRANSITION_DURATION)
                _expressionState.value = updatedState

                // 60fps
                delay(16L)
            }
        }
    }

    /**
     * 回落到待机状态
     */
    private fun fallbackToIdle() {
        if (_expressionState.value.type != ExpressionType.IDLE) {
            Log.d(TAG, "Falling back to IDLE")
            _expressionState.value = ExpressionState(
                type = ExpressionType.IDLE,
                style = currentStyle,
                startTime = System.currentTimeMillis(),
                transitionProgress = 0f
            )
            // 回到待机，清除特效
            updateEffectForExpression(ExpressionType.IDLE)
            // 回到待机，启动自主微动作
            speechMicroMotionJob?.cancel()
            startIdleMicroMotion()
        }
    }
}
