/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

import android.util.Log
import com.roboparty.rpoexpression.animation.MicroAnimationLayer
import com.roboparty.rpoexpression.sensor.ImuManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
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
        private const val TRANSITION_DURATION = 600L  // 表情切换过渡时长600ms，极致丝滑无抖动
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

    // 当前渲染风格（默认女款）
    private var currentStyle = ExpressionStyle.FEMALE

    // 风格变更回调列表（用于联动 TTS 音色等外部模块）
    private val styleChangeCallbacks = mutableListOf<(ExpressionStyle) -> Unit>()

    // 当前全局表情强度 0-100
    private var currentIntensity = 100

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
    internal val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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

    // 瞳孔平滑滤波状态（修复 data class 拷贝导致状态丢失的 BUG）
    private var smoothedPupilX = 0f
    private var smoothedPupilY = 0f
    private val PUPIL_SMOOTH_FACTOR = 0.15f  // 平滑系数，越大跟随越快，0.15兼顾灵敏与平滑
    private val PUPIL_DEAD_ZONE = 0.005f     // 死区阈值，小于此值直接对齐，过滤微抖

    /**
     * 更新并返回平滑后的瞳孔X偏移（低通滤波）
     */
    internal fun updateSmoothedPupilX(target: Float): Float {
        val diff = target - smoothedPupilX
        if (kotlin.math.abs(diff) < PUPIL_DEAD_ZONE) {
            smoothedPupilX = target
        } else {
            smoothedPupilX += diff * PUPIL_SMOOTH_FACTOR
        }
        return smoothedPupilX
    }

    /**
     * 更新并返回平滑后的瞳孔Y偏移（低通滤波）
     */
    internal fun updateSmoothedPupilY(target: Float): Float {
        val diff = target - smoothedPupilY
        if (kotlin.math.abs(diff) < PUPIL_DEAD_ZONE) {
            smoothedPupilY = target
        } else {
            smoothedPupilY += diff * PUPIL_SMOOTH_FACTOR
        }
        return smoothedPupilY
    }

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

    // 待机状态电量修饰（不改变表情类型，仅在IDLE时叠加视觉效果）
    // 用于在不干扰业务逻辑的前提下，通过待机状态的颜色/闪烁反映电量状态
    enum class IdleBatteryState {
        NORMAL,     // 正常电量，无修饰
        LOW_BATTERY, // 低电量，橙黄闪烁
        CHARGING     // 充电中，绿色呼吸
    }
    private val _idleBatteryState = MutableStateFlow(IdleBatteryState.NORMAL)
    val idleBatteryState: StateFlow<IdleBatteryState> = _idleBatteryState.asStateFlow()

    // IMU临时效果偏移
    internal var tempEyelidOffset = 0f // 眼睑临时偏移：-1全闭，1睁大
    internal var tempPupilShakeX = 0f // 瞳孔临时晃动偏移
    // 距离感知瞳孔缩放：1.0为基准，<1缩小（走近对焦），>1放大（走远放松）
    internal var distancePupilScale = 1f

    // ==================== 休眠梦境系统 ====================
    /**
     * 睡眠子状态：外部统一使用 SLEEPING 表情，内部分阶段
     */
    enum class SleepStage {
        AWAKE,          // 清醒（非睡眠状态）
        YAWNING,        // 打哈欠：入睡前过渡，闭合→微睁→闭合
        LIGHT_SLEEP,    // 浅眠：眼睛半闭，偶尔微动
        DEEP_SLEEP,     // 深眠：低亮度安静状态
        WAKING          // 唤醒：快速睁眼
    }

    private var sleepStage = SleepStage.AWAKE
    private var sleepJob: Job? = null

    // 睡眠参数配置
    private val YAWNING_DURATION_MS = 2500L      // 打哈欠总时长
    private val LIGHT_SLEEP_DURATION_MS = 15000L  // 浅眠持续约15秒后进入深眠
    private val LIGHT_SLEEP_EYELID_OPEN = 0.45f   // 浅眠眼睑开合度（半睁状态，清晰可见）
    private val DEEP_SLEEP_EYELID_OPEN = 0.12f    // 深眠眼睑开合度（低亮度，安静休息）
    private val WAKING_DURATION_MS = 300L         // 快速唤醒时长（快速睁眼，不阻塞交互）

    // 浅眠微动参数
    private val LIGHT_MICRO_INTERVAL_MS = 1500L   // 浅眠微动间隔
    private val LIGHT_MICRO_AMPLITUDE = 0.12f     // 浅眠微动幅度
    // 视觉模仿：用户眼睛开合度
    internal var mimicEyelidOpenness = 1f
    // 音频情绪眼型：-1柔和弯月，0正常，1锐利上扬
    internal var audioEyeShape = 0f
    // 声源方向瞳孔偏移
    internal var soundPupilOffsetX = 0f
    // 触摸临时偏移（优先级最高，不会被人脸识别覆盖）
    internal var touchPupilOffsetX = 0f
    internal var touchPupilOffsetY = 0f

    fun setAudioEyeShape(shape: Float) { audioEyeShape = shape.coerceIn(-1f, 1f) }
    fun setSoundPupilOffset(offset: Float) { soundPupilOffsetX = offset.coerceIn(-1f, 1f) }
    fun setTouchPupilOffset(x: Float, y: Float) {
        touchPupilOffsetX = x.coerceIn(-1f, 1f)
        touchPupilOffsetY = y.coerceIn(-1f, 1f)
    }

    // 公开方法供外部调用
    fun setMimicEyelidOpenness(openness: Float) { 
        mimicEyelidOpenness = openness.coerceIn(0.2f, 1f) 
    }

    /**
     * 设置待机状态电量修饰
     * 仅在IDLE状态下生效，不改变表情类型，仅叠加视觉效果
     * 用于反映电量状态而不干扰业务逻辑
     */
    fun setIdleBatteryState(state: IdleBatteryState) {
        if (_idleBatteryState.value != state) {
            _idleBatteryState.value = state
            Log.d(TAG, "Idle battery state changed: $state")
        }
    }

    fun getIdleBatteryState(): IdleBatteryState = _idleBatteryState.value
    fun isBlinking() = isBlinking
    fun performBlink(type: BlinkType = BlinkType.NORMAL) {
        if (!isBlinking) {
            performBlinkInternal(type)
        }
    }
    fun setTempEyelidOffset(offset: Float) { tempEyelidOffset = offset }
    fun setTempPupilShakeX(offset: Float) { tempPupilShakeX = offset }

    /**
     * 设置距离感知瞳孔缩放系数
     * @param scale 1.0为基准，<1缩小（走近对焦感），>1放大（走远放松感）
     *              有效范围 0.85 ~ 1.15
     */
    fun setDistancePupilScale(scale: Float) {
        distancePupilScale = scale.coerceIn(0.85f, 1.15f)
    }

    // ==================== 休眠梦境系统公开方法 ====================

    /**
     * 进入睡眠模式（带打哈欠过渡动画）
     * 自动流转：打哈欠 → 浅眠 → 深眠
     */
    fun enterSleepWithDream() {
        if (sleepStage != SleepStage.AWAKE) return
        Log.d(TAG, "进入睡眠梦境模式")
        startSleepCycle()
    }

    /**
     * 从睡眠中立即唤醒（无过渡动画）
     * 检测到人脸后直接重置状态，优先响应高优先级反馈表情
     */
    fun wakeFromSleep(): Long {
        if (sleepStage == SleepStage.AWAKE) return 0L
        Log.d(TAG, "从睡眠中立即唤醒，阶段=$sleepStage")
        sleepJob?.cancel()
        resetSleepState()
        return 0L
    }

    /**
     * 获取当前睡眠子阶段
     */
    fun getCurrentSleepStage(): SleepStage = sleepStage

    /**
     * 是否处于睡眠状态（任意睡眠子阶段）
     */
    fun isSleeping(): Boolean = sleepStage != SleepStage.AWAKE
//    fun setPupilOffset(x: Float, y: Float) {
//        pupilOffsetX = x.coerceIn(-1f, 1f)
//        pupilOffsetY = y.coerceIn(-1f, 1f)
//    }

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
        if (currentStyle == style) return
        currentStyle = style
        val currentState = _expressionState.value
        _expressionState.value = currentState.copy(style = style)
        Log.d(TAG, "Expression style changed to: $style")
        // 触发风格变更回调，联动 TTS 音色等外部模块
        styleChangeCallbacks.forEach { it(style) }
    }

    /**
     * 注册风格变更回调
     * @param callback 回调函数，参数为新风格
     */
    fun addStyleChangeCallback(callback: (ExpressionStyle) -> Unit) {
        styleChangeCallbacks.add(callback)
    }

    /**
     * 移除风格变更回调
     */
    fun removeStyleChangeCallback(callback: (ExpressionStyle) -> Unit) {
        styleChangeCallbacks.remove(callback)
    }

    /**
     * 获取当前风格
     */
    fun getCurrentStyle(): ExpressionStyle = currentStyle

    /**
     * 设置全局表情强度 0-100
     */
    fun setIntensity(intensity: Int) {
        currentIntensity = intensity.coerceIn(0, 100)
        Log.d(TAG, "Expression intensity changed to: $currentIntensity")
    }

    /**
     * 获取当前表情类型
     */
    fun getCurrentExpression(): ExpressionType = _expressionState.value.type

    /**
     * 获取当前全局强度
     */
    fun getCurrentIntensity(): Int = currentIntensity

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
            return performBlinkInternal()
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
            temporaryFallbackTarget = ExpressionType.IDLE
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
        NORMAL(240L),    // 正常眨眼，放慢避免抖动感
        SLOW(400L),      // 慢眨眼（困倦）
        FAST(120L)       // 快眨眼（说话快/紧张）
    }

    // 眨眼叠加值，0=不眨眼，1=完全闭眼
    internal var blinkOverlay = 0f

    /**
     * 执行眨眼动画（在当前表情基础上叠加眼睑闭合，不改变其他参数）
     */
    private fun performBlinkInternal(type: BlinkType = BlinkType.NORMAL): Boolean {
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
                    performBlinkInternal(BlinkType.FAST)
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
                    delay(33) // 30fps足够，微动作不需要60fps
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
                delay(33) // 30fps
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
                            delay(33) // 30fps足够，扫视是慢动作
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
                            delay(33) // 30fps
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
                            delay(33) // 30fps
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
                            delay(33) // 30fps
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
                            performBlinkInternal(blinkType)
                        }
                        delay(Random.nextLong(5000, 9000)) // 眨眼间隔5-9秒，自然不频繁
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
                    performBlinkInternal()
                }
            }
        }
    }

    /**
     * 状态更新主循环
     * 50fps更新过渡动画进度，同时递增帧计数器驱动持续重绘
     * 瞳孔跟随、眨眼、微动作等动态效果都依赖每帧重绘
     */
    private fun startStateUpdateLoop() {
        managerJob = scope.launch {
            var frameCount = 0L
            while (isActive && isRunning.get()) {
                val currentState = _expressionState.value
                val updatedState = currentState.updateProgress(TRANSITION_DURATION)
                // 每帧递增动画帧计数器，强制驱动重绘，确保瞳孔跟随/眨眼/微动作实时更新
                _expressionState.value = updatedState.copy(animFrame = frameCount++)
                // 50fps：眼部动画足够流畅，减少调度开销
                delay(20L)
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

    // ==================== 休眠梦境系统内部实现 ====================

    /**
     * 启动完整睡眠周期：打哈欠 → 浅眠 → 深眠
     */
    private fun startSleepCycle() {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            try {
                // 阶段1：打哈欠过渡
                playYawningAnimation()

                // 阶段2：浅眠
                sleepStage = SleepStage.LIGHT_SLEEP
                playLightSleep()

                // 阶段3：深眠（持续直到被唤醒）
                sleepStage = SleepStage.DEEP_SLEEP
                playDeepSleep()
            } catch (e: Exception) {
                Log.e(TAG, "睡眠周期异常", e)
                resetSleepState()
            }
        }
    }

    /**
     * 打哈欠动画：缓慢闭合 → 微睁一下 → 再缓慢闭合
     * 总时长约2.5秒，模拟真实打哈欠的眼部动作
     */
    private suspend fun playYawningAnimation() {
        sleepStage = SleepStage.YAWNING
        val startTime = System.currentTimeMillis()
        val totalDuration = YAWNING_DURATION_MS

        while (coroutineContext.isActive && sleepStage == SleepStage.YAWNING) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= totalDuration) break

            val progress = (elapsed.toFloat() / totalDuration).coerceIn(0f, 1f)

            // 打哈欠曲线：0→闭合(40%)→微睁(60%)→闭合(100%)
            // 第一段：0~40% 眼睛慢慢闭上（打哈欠开始）
            // 第二段：40%~60% 眼睛微微睁开一下（哈欠顶点）
            // 第三段：60%~100% 眼睛再次闭上，进入睡眠
            val eyelidProgress = when {
                progress < 0.4f -> {
                    // 闭合阶段：从正常到全闭
                    progress / 0.4f  // 0 → 1
                }
                progress < 0.6f -> {
                    // 微睁阶段：从全闭到半睁再回到全闭
                    val subProgress = (progress - 0.4f) / 0.2f  // 0 → 1
                    // 正弦波：0 → 1 → 0，形成一个小凸起
                    val wave = Math.sin(subProgress * Math.PI).toFloat()
                    1f - wave * 0.3f  // 全闭状态下睁开30%
                }
                else -> {
                    // 再次闭合：从微睁到全闭
                    val subProgress = (progress - 0.6f) / 0.4f  // 0 → 1
                    0.7f + subProgress * 0.3f  // 70%闭合 → 100%闭合
                }
            }

            // tempEyelidOffset：负值表示闭合，SLEEPING基础是0（全闭）
            // 这里用offset控制睁开程度：0 = 全闭，1 = 全睁
            // SLEEPING基础eyelidOpenness=0，叠加tempEyelidOffset控制睁开
            tempEyelidOffset = (1f - eyelidProgress) * 0.8f

            delay(16) // ~60fps
        }

        // 确保最终完全闭合
        tempEyelidOffset = 0f
    }

    /**
     * 浅眠阶段：眼睛半闭，偶尔轻微微动
     * 持续约15秒，然后自动进入深眠
     */
    private suspend fun playLightSleep() {
        val startTime = System.currentTimeMillis()
        var lastMicroMoveTime = 0L

        while (coroutineContext.isActive && sleepStage == SleepStage.LIGHT_SLEEP) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= LIGHT_SLEEP_DURATION_MS) break

            // 浅眠眼睑：半睁状态，带明显呼吸波动，与深眠形成显著视觉差异
            val breathPhase = (elapsed % 3000L) / 3000f
            val breathWave = Math.sin(breathPhase * Math.PI * 2).toFloat() * 0.05f
            tempEyelidOffset = LIGHT_SLEEP_EYELID_OPEN + breathWave

            // 偶尔轻微微动（每2秒左右一次小幅度移动）
            val now = System.currentTimeMillis()
            if (now - lastMicroMoveTime > LIGHT_MICRO_INTERVAL_MS) {
                lastMicroMoveTime = now
                // 随机小幅度偏移
                val targetX = (Random.nextFloat() - 0.5f) * 2 * LIGHT_MICRO_AMPLITUDE
                val targetY = (Random.nextFloat() - 0.5f) * 2 * LIGHT_MICRO_AMPLITUDE

                // 缓慢移动过去
                val moveDuration = 600L
                val moveStart = System.currentTimeMillis()
                val startX = microPupilOffsetX
                val startY = microPupilOffsetY

                while (coroutineContext.isActive && sleepStage == SleepStage.LIGHT_SLEEP) {
                    val moveElapsed = System.currentTimeMillis() - moveStart
                    if (moveElapsed >= moveDuration) break
                    val t = (moveElapsed.toFloat() / moveDuration).coerceIn(0f, 1f)
                    val eased = EyeParameters.decelerateEasing(t)
                    microPupilOffsetX = startX + (targetX - startX) * eased
                    microPupilOffsetY = startY + (targetY - startY) * eased
                    delay(16)
                }
            }

            delay(16)
        }

        // 进入深眠前，瞳孔归位
        microPupilOffsetX = 0f
        microPupilOffsetY = 0f
    }

    /**
     * 深眠阶段：简单低亮度安静状态
     * 保持微开的细缝，带极微弱呼吸，无复杂动效
     */
    private suspend fun playDeepSleep() {
        var breathStartTime = System.currentTimeMillis()

        while (coroutineContext.isActive && sleepStage == SleepStage.DEEP_SLEEP) {
            val elapsed = System.currentTimeMillis() - breathStartTime

            // 深眠：低亮度（0.12），极微弱呼吸感，安静休息
            val breathPhase = (elapsed % 4000L) / 4000f
            val breathWave = Math.sin(breathPhase * Math.PI * 2).toFloat() * 0.02f
            tempEyelidOffset = DEEP_SLEEP_EYELID_OPEN + breathWave

            delay(33) // 30fps，深眠降低刷新率省电
        }
    }

    /**
     * 快速唤醒：300ms快速睁眼，不阻塞后续交互
     * 检测到人脸后立即唤醒，优先响应人脸识别反馈表情
     */
    private fun startWakeTransition() {
        sleepJob?.cancel()
        sleepStage = SleepStage.WAKING

        sleepJob = scope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val totalDuration = WAKING_DURATION_MS
                val startEyelid = tempEyelidOffset

                // 快速睁眼：300ms内从当前睡眠亮度线性睁开到1.0
                // 不做复杂动画，不阻塞后续高优先级表情（如问候、微笑回应）
                while (coroutineContext.isActive && sleepStage == SleepStage.WAKING) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= totalDuration) break

                    val progress = (elapsed.toFloat() / totalDuration).coerceIn(0f, 1f)
                    val eased = EyeParameters.fastOutSlowInEasing(progress)
                    tempEyelidOffset = startEyelid + (1f - startEyelid) * eased

                    delay(16)
                }

                // 唤醒完成，重置状态
                resetSleepState()
            } catch (e: Exception) {
                Log.e(TAG, "唤醒过渡异常", e)
                resetSleepState()
            }
        }
    }

    /**
     * 重置睡眠状态到清醒
     * 注意：不重置 tempEyelidOffset，避免 SLEEPING 表情下瞬间全闭造成闪烁
     * 切到 IDLE 后 tempEyelidOffset 残留值不影响最终亮度（IDLE 基础 1.0，叠加后 clamp 仍为 1.0）
     */
    private fun resetSleepState() {
        sleepStage = SleepStage.AWAKE
        microPupilOffsetX = 0f
        microPupilOffsetY = 0f
        sleepJob = null
    }
}
