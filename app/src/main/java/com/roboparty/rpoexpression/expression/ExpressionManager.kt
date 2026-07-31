/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.expression

import android.util.Log
import com.roboparty.rpoexpression.RpoExpressionApp
import com.roboparty.rpoexpression.animation.MicroAnimationLayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 表情管理器 - 核心状态机
 * 纯眼部表情系统，负责表情切换、自动眨眼、状态管理
 */
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
class ExpressionManager private constructor() {

    companion object {
        private const val TAG = "ExpressionManager"
        private const val TRANSITION_DURATION = 600L
        private const val DEBOUNCE_INTERVAL = 100L
        private const val BLINK_INTERVAL_MIN = 2000L
        private const val BLINK_INTERVAL_MAX = 5000L

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
    private var isGenderSwitching = false
    private var genderSwitchOverlay = EyeParameters()

    // 风格变更回调列表
    private val styleChangeCallbacks = mutableListOf<(ExpressionStyle) -> Unit>()
    // 电源状态回调
    private val powerStateCallbacks = mutableListOf<(Boolean) -> Unit>()
    // 表情变化回调（用于联动头部电机等外部模块）
    private val expressionChangeCallbacks = mutableListOf<(ExpressionType) -> Unit>()
    private var isPoweredOff = false
    private var powerOffOverlay = EyeParameters()
    private var longPressOverlay = EyeParameters()

    // 眨眼相关
    private var blinkJob: Job? = null
    private var isBlinking = false
    internal var blinkProgress = 0f
    internal var microPupilOffsetX = 0f
    internal var microPupilOffsetY = 0f
    internal var tempPupilOffsetY = 0f
    internal var smoothedPupilX = 0f
    internal var smoothedPupilY = 0f
    internal var distancePupilScale = 1f

    // ==================== 眼内特效状态 ====================
    private val _currentEffect = MutableStateFlow(EyeEffectType.NONE)
    val currentEffect: StateFlow<EyeEffectType> = _currentEffect.asStateFlow()
    private var effectFallbackJob: Job? = null
    // Demo演示模式
    private var demoJob: Job? = null
    private var isDemoRunning = false

    // 自动回退
    private var autoFallbackJob: Job? = null

    // 当前全局表情强度 0-100
    private var currentIntensity = 100

    // 表情状态流
    private val _expressionState = MutableStateFlow(
        ExpressionState(
            type = ExpressionType.IDLE,
            style = currentStyle
        )
    )
    val expressionState: StateFlow<ExpressionState> = _expressionState.asStateFlow()

    // ==================== 表情渲染模式 ====================
    private val _currentMode = MutableStateFlow(ExpressionMode.TRADITIONAL)
    val currentMode: StateFlow<ExpressionMode> = _currentMode.asStateFlow()

    // 眼内特效状态

    enum class IdleBatteryState {
        NORMAL, LOW_BATTERY, CHARGING
    }

    private var idleBatteryState = IdleBatteryState.NORMAL

    fun getIdleBatteryState(): IdleBatteryState = idleBatteryState

    // 管理器运行状态
    private val isRunning = AtomicBoolean(false)
    private var managerJob: Job? = null
    internal val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 瞳孔偏移（低通滤波后的最终值，每帧渲染读取）
    @Volatile var pupilOffsetX = 0f
    @Volatile var pupilOffsetY = 0f
    // 瞳孔目标值（原始输入，低通滤波源）
    private var pupilTargetX = 0f
    private var pupilTargetY = 0f
    
    internal var tempPupilShakeX = 0f
    internal var tempEyelidOffset = 0f
    internal var touchPupilOffsetX = 0f
    internal var touchPupilOffsetY = 0f

    enum class BlinkType {
        NORMAL, FAST, DOUBLE
    }
    fun setIdleBatteryState(state: IdleBatteryState) {
        idleBatteryState = state
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        startStateUpdateLoop()
        startAutoBlink()
        startIdleMicroMotion()
        Log.i(TAG, "ExpressionManager started")
    }

    fun stop() {
        if (!isRunning.get()) return
        isRunning.set(false)
        managerJob?.cancel()
        blinkJob?.cancel()
        autoFallbackJob?.cancel()
        effectFallbackJob?.cancel()
        demoJob?.cancel()
        isDemoRunning = false
        MicroAnimationLayer.getInstance().stop()
        Log.i(TAG, "ExpressionManager stopped")
    }

    fun getCurrentStyle(): ExpressionStyle = currentStyle
    fun isPoweredOff(): Boolean = isPoweredOff
    fun isGenderSwitching(): Boolean = isGenderSwitching

    /**
     * 获取当前渲染模式
     */
    fun getCurrentMode(): ExpressionMode = _currentMode.value

    /**
     * 切换渲染模式（传统眼部 / 能量面部）
     * 线程安全：通过 StateFlow 原子更新，UI 层通过 collectAsState 自动响应
     */
    fun setMode(mode: ExpressionMode) {
        if (_currentMode.value == mode) return
        _currentMode.value = mode
        Log.i(TAG, "Expression mode switched to: $mode")
    }

    /**
     * 快捷切换：传统 ↔ 能量
     */
    fun toggleMode() {
        val newMode = if (_currentMode.value == ExpressionMode.TRADITIONAL) {
            ExpressionMode.ENERGY
        } else {
            ExpressionMode.TRADITIONAL
        }
        setMode(newMode)
    }

    fun getBlinkOverlay(): EyeParameters = EyeParameters(eyelidOpenness = 1f - blinkProgress, blinkProgress = blinkProgress)
    fun getGenderSwitchOverlay(): EyeParameters = genderSwitchOverlay
    fun getLongPressOverlay(): EyeParameters = longPressOverlay
    fun getPowerOffOverlay(): EyeParameters = powerOffOverlay

    fun addStyleChangeCallback(callback: (ExpressionStyle) -> Unit) {
        styleChangeCallbacks.add(callback)
    }

    fun addPowerStateCallback(callback: (Boolean) -> Unit) {
        powerStateCallbacks.add(callback)
    }

    /**
     * 注册表情变化回调
     * 用于联动头部电机、外部设备等模块
     * @param callback 回调参数为新的表情类型
     */
    fun addExpressionChangeCallback(callback: (ExpressionType) -> Unit) {
        expressionChangeCallbacks.add(callback)
    }

    /**
     * 设置眼内特效（瞳孔区域特效）
     * @param effect 特效类型
     * @param duration 持续时长，null表示持续直到被清除
    **/
    fun setStyle(style: ExpressionStyle) {
        currentStyle = style
        _expressionState.value = _expressionState.value.copy(style = style)
    }

    fun setLongPressProgress(progress: Float) {
        if (isPoweredOff) {
            powerOffOverlay = EyeParameters(
                eyelidOpenness = progress,
                eyeHeightRatio = progress * 0.7f,
                pupilSize = progress * 0.6f
            )
            return
        }
        longPressOverlay = EyeParameters(
            eyelidOpenness = 1f - progress * 0.3f,
            pupilSize = 1f - progress * 0.4f
        )
    }

    fun clearLongPressProgress() {
        longPressOverlay = EyeParameters()
    }
    fun setPupilOffset(x: Float, y: Float) {
        // 仅设置目标值，实际低通滤波在 updatePupilSmoothing() 中每帧执行一次
        // 避免同一帧多次调用导致滤波系数叠加（VisionManager + MainActivity 回调重复调用）
        pupilTargetX = x.coerceIn(-1f, 1f)
        pupilTargetY = y.coerceIn(-1f, 1f)
    }

    /**
     * 每帧调用一次的低通滤波，将 pupilOffset 平滑逼近 pupilTarget
     * 由状态更新循环统一调用，确保每帧只滤波一次
     */
    internal fun updatePupilSmoothing() {
        pupilOffsetX += (pupilTargetX - pupilOffsetX) * 0.3f
        pupilOffsetY += (pupilTargetY - pupilOffsetY) * 0.3f
    }
    fun setPupilScale(scale: Float) {
        distancePupilScale = scale.coerceIn(0.5f, 1.2f)
    }

    fun setDistancePupilScale(scale: Float) {
        setPupilScale(scale)
    }
    fun setTempPupilShakeX(x: Float) {
        tempPupilShakeX = x.coerceIn(-1f, 1f)
    }

    fun setTempEyelidOffset(offset: Float) {
        tempEyelidOffset = offset.coerceIn(-1f, 1f)
    }
    fun setTouchPupilOffset(x: Float, y: Float) {
        touchPupilOffsetX = x.coerceIn(-1f, 1f)
        touchPupilOffsetY = y.coerceIn(-1f, 1f)
    }
    fun updateSmoothedPupilX(target: Float): Float {
        smoothedPupilX += (target - smoothedPupilX) * 0.2f
        return smoothedPupilX
    }

    fun updateSmoothedPupilY(target: Float): Float {
        smoothedPupilY += (target - smoothedPupilY) * 0.2f
        return smoothedPupilY
    }

    fun wakeFromSleep() {
        resetSleepState()
        startIdleMicroMotion()
        requestExpression(ExpressionType.IDLE, force = true)
    }

    fun enterSleepWithDream() {
        requestExpression(ExpressionType.SLEEPING, force = true)
        startSleepCycle()
    }

    fun toggleGender() {
        if (isPoweredOff || isGenderSwitching) return
        isGenderSwitching = true
        autoFallbackJob?.cancel()
        blinkJob?.cancel()

        scope.launch {
            // 阶段1：收缩
            val shrinkDuration = 200L
            val start = System.currentTimeMillis()
            var elapsed = 0L
            while (elapsed < shrinkDuration && isActive) {
                val t = (elapsed.toFloat() / shrinkDuration).coerceIn(0f, 1f)
                val p = t * t
                genderSwitchOverlay = EyeParameters(
                    eyelidOpenness = 1f - p,
                    eyeHeightRatio = 0.7f - 0.6f * p,
                    pupilSize = 0.6f - 0.5f * p,
                    glowIntensity = 0.3f - 0.3f * p
                )
                delay(16L)
                elapsed = System.currentTimeMillis() - start
            }

            // 切换性别
            val newStyle = if (currentStyle == ExpressionStyle.MALE) ExpressionStyle.FEMALE else ExpressionStyle.MALE
            setStyle(newStyle)
            styleChangeCallbacks.forEach { it(newStyle) }
            delay(100L)

            // 阶段3：展开
            val expandDuration = 250L
            val expandStart = System.currentTimeMillis()
            elapsed = 0L
            val s = 1.70158f
            while (elapsed < expandDuration && isActive) {
                val t = (elapsed.toFloat() / expandDuration).coerceIn(0f, 1f)
                val p = 1f + (s + 1f) * (t - 1f) * (t - 1f) * (t - 1f) + s * (t - 1f) * (t - 1f)
                genderSwitchOverlay = EyeParameters(
                    eyelidOpenness = p.coerceIn(0f, 1f),
                    eyeHeightRatio = 0.1f + 0.6f * p,
                    pupilSize = 0.1f + 0.5f * p,
                    glowIntensity = 0.3f * p
                )
                delay(16L)
                elapsed = System.currentTimeMillis() - expandStart
            }

            // 阶段4：高光闪烁
            val blinkDuration = 150L
            val blinkStart = System.currentTimeMillis()
            elapsed = 0L
            while (elapsed < blinkDuration && isActive) {
                val t = (elapsed.toFloat() / blinkDuration).coerceIn(0f, 1f)
                val p = kotlin.math.sin(t * Math.PI.toFloat())
                genderSwitchOverlay = EyeParameters(
                    glowIntensity = 0.3f + 0.5f * p,
                    glowBlink = p > 0.5f
                )
                delay(16L)
                elapsed = System.currentTimeMillis() - blinkStart
            }

            genderSwitchOverlay = EyeParameters()
            isGenderSwitching = false
            startAutoBlink()
        }
    }

    fun powerOff() {
        if (isPoweredOff) return
        val brightnessManager = RpoExpressionApp.instance.brightnessManager
        scope.launch {
            // 眼睛闭合动画 800ms
            val duration = 800L
            val start = System.currentTimeMillis()
            var elapsed = 0L
            while (elapsed < duration && isActive) {
                val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val p = t * t
                powerOffOverlay = EyeParameters(
                    eyelidOpenness = 1f - p,
                    eyeHeightRatio = 0.7f - 0.7f * p,
                    pupilSize = 0.6f - 0.6f * p,
                    glowIntensity = 0.3f - 0.3f * p
                )
                delay(16L)
                elapsed = System.currentTimeMillis() - start
            }
            // 亮度动画
            brightnessManager.animateToPowerOff(800L) {
                isPoweredOff = true
                autoFallbackJob?.cancel()
                blinkJob?.cancel()
                powerStateCallbacks.forEach { it(true) }
                Log.i(TAG, "Power off complete")
            }
        }
    }

    fun powerOn() {
        if (!isPoweredOff) return
        val brightnessManager = RpoExpressionApp.instance.brightnessManager
        isPoweredOff = false
        powerStateCallbacks.forEach { it(false) }
        scope.launch {
            // 眼睛睁开动画 600ms
            val duration = 600L
            val start = System.currentTimeMillis()
            var elapsed = 0L
            while (elapsed < duration && isActive) {
                val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val s = 1.70158f
                val p = 1f + (s + 1f) * (t - 1f) * (t - 1f) * (t - 1f) + s * (t - 1f) * (t - 1f)
                powerOffOverlay = EyeParameters(
                    eyelidOpenness = p.coerceIn(0f, 1f),
                    eyeHeightRatio = 0.7f * p,
                    pupilSize = 0.6f * p,
                    glowIntensity = 0.3f * p
                )
                delay(16L)
                elapsed = System.currentTimeMillis() - start
            }
            powerOffOverlay = EyeParameters()
            // 亮度恢复动画
            brightnessManager.animateToPowerOn(600L) {
                startAutoBlink()
                performBlinkInternal(BlinkType.NORMAL)
                Log.i(TAG, "Power on complete")
            }
        }
    }

    fun performBlink(type: BlinkType = BlinkType.NORMAL) = performBlinkInternal(type)

    private fun performBlinkInternal(blinkType: BlinkType = BlinkType.NORMAL) {
        if (isPoweredOff || isGenderSwitching || isBlinking) return
        isBlinking = true
        blinkJob?.cancel()
        blinkJob = scope.launch {
            val duration = when (blinkType) {
                BlinkType.FAST -> 80L
                BlinkType.DOUBLE -> 300L
                else -> 150L
            }
            val start = System.currentTimeMillis()
            var elapsed = 0L
            while (elapsed < duration && isActive) {
                val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val p = EyeParameters.blinkEasing(t)
                blinkProgress = p
                delay(16L)
                elapsed = System.currentTimeMillis() - start
            }
            blinkProgress = 0f
            isBlinking = false
            if (blinkType == BlinkType.DOUBLE) {
                delay(100L)
                performBlinkInternal(BlinkType.FAST)
            }
        }
    }

    private fun startAutoBlink() {
        if (isPoweredOff) return
        blinkJob?.cancel()
        blinkJob = scope.launch {
            while (isActive && !isPoweredOff) {
                delay(Random.nextLong(BLINK_INTERVAL_MIN, BLINK_INTERVAL_MAX))
                if (!isPoweredOff && !isGenderSwitching) performBlinkInternal()
            }
        }
    }

    fun requestExpression(
        type: ExpressionType,
        intensity: Int = 100,
        duration: Long? = null,
        force: Boolean = false
    ): Boolean {
        if (isPoweredOff) return false
        if (type == ExpressionType.BLINK) {
            performBlinkInternal()
            return true
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastSwitchTime < DEBOUNCE_INTERVAL) return false
        if (!force && type.priority < _expressionState.value.type.priority) return false

        lastSwitchTime = now
        autoFallbackJob?.cancel()

        _expressionState.value = _expressionState.value.copy(
            type = type,
            intensity = intensity.coerceIn(0, 100),
            startTime = now,
            transitionProgress = 0f
        )

        val actualDur = duration ?: type.defaultDuration
        if (actualDur != null && actualDur > 0) {
            autoFallbackJob = scope.launch {
                delay(actualDur)
                if (_expressionState.value.type == type) {
                    _expressionState.value = _expressionState.value.copy(type = ExpressionType.IDLE, startTime = System.currentTimeMillis(), transitionProgress = 0f)
                }
            }
        }

        // 根据表情类型自动触发对应眼内特效
        when (type) {
            ExpressionType.HAPPY -> setEyeEffect(EyeEffectType.HAPPY, duration = actualDur)
            ExpressionType.CONFUSED -> setEyeEffect(EyeEffectType.CONFUSED, duration = actualDur)
            ExpressionType.SHY -> setEyeEffect(EyeEffectType.SHY, duration = actualDur)
            ExpressionType.RECOGNIZING -> setEyeEffect(EyeEffectType.RECOGNIZING)
            ExpressionType.ALERT -> setEyeEffect(EyeEffectType.SYSTEM_ERROR)
            ExpressionType.RESPONSE -> setEyeEffect(EyeEffectType.RESPONSE, duration = actualDur)
            else -> clearEyeEffect()
        }

        // 通知所有外部模块：表情已变化
        expressionChangeCallbacks.forEach { callback ->
            try {
                callback(type)
            } catch (e: Exception) {
                Log.e(TAG, "Expression change callback error", e)
            }
        }

        return true
    }

    fun clearExpression() {
        if (isPoweredOff) return
        autoFallbackJob?.cancel()
                    _expressionState.value = _expressionState.value.copy(type = ExpressionType.IDLE, startTime = System.currentTimeMillis(), transitionProgress = 0f)
    }

    /**
     * 设置眼内特效
     * @param effect 特效类型
     * @param duration 特效持续时间，null表示不自动清除
     */
    fun setEyeEffect(effect: EyeEffectType, duration: Long? = null) {
        _currentEffect.value = effect
        effectFallbackJob?.cancel()
        if (duration != null && duration > 0 && effect != EyeEffectType.NONE) {
            effectFallbackJob = scope.launch {
                delay(duration)
                if (_currentEffect.value == effect) {
                    _currentEffect.value = EyeEffectType.NONE
                }
            }
        }
    }

    /**
     * 清除眼内特效
     */
    fun clearEyeEffect() {
        _currentEffect.value = EyeEffectType.NONE
        effectFallbackJob?.cancel()
    }

    private fun startSleepCycle() {
        // 睡眠周期：打哈欠→浅眠→慢慢闭眼→深眠
        scope.launch {
            delay(1000)
            requestExpression(ExpressionType.SLEEPY, force = true)
            delay(2000)
            if (isPoweredOff) return@launch
            // 慢慢闭眼动画，1秒完成
            val closeDuration = 1000L
            val start = System.currentTimeMillis()
            var elapsed = 0L
            while (elapsed < closeDuration && isActive && !isPoweredOff) {
                val t = (elapsed.toFloat() / closeDuration).coerceIn(0f, 1f)
                blinkProgress = t // 0→1，完全闭眼
                delay(16L)
                elapsed = System.currentTimeMillis() - start
            }
            blinkProgress = 1f // 保持完全闭眼
        }
    }
    private fun resetSleepState() {
        blinkProgress = 0f
                    _expressionState.value = _expressionState.value.copy(type = ExpressionType.IDLE, startTime = System.currentTimeMillis(), transitionProgress = 0f)
    }
    private fun startStateUpdateLoop() {
        // 启动60fps动画帧循环，驱动Canvas持续重绘
        // 所有动态效果（眨眼、瞳孔跟随、微动画、特效）都依赖此循环触发重组
        managerJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(16L) // ~60fps
                // 递增帧计数器，触发expressionState变化，驱动Compose重绘
                // 每帧更新表情过渡进度（200ms平滑过渡）
                _expressionState.value = _expressionState.value.updateProgress(200L)
                val currentFrame = _expressionState.value.animFrame + 1
                _expressionState.value = _expressionState.value.copy(animFrame = currentFrame)
                // 每帧执行一次瞳孔低通滤波（统一入口，避免同一帧多次滤波）
                updatePupilSmoothing()
            }
        }
        // 启动微动画层（呼吸缩放、说话晃动）
        MicroAnimationLayer.getInstance().start()
    }
    private fun startIdleMicroMotion() {
        // 待机微动作：随机小幅度眼球移动，恢复自动眨眼
        startAutoBlink()
        scope.launch {
            while (isActive && !isPoweredOff && _expressionState.value.type == ExpressionType.IDLE) {
                delay(Random.nextLong(3000, 8000))
                if (_expressionState.value.type != ExpressionType.IDLE) continue
                val targetX = Random.nextFloat() * 0.3f - 0.15f
                val targetY = Random.nextFloat() * 0.2f - 0.1f
                val duration = 500L
                val startX = pupilTargetX
                val startY = pupilTargetY
                val start = System.currentTimeMillis()
                var elapsed = 0L
                while (elapsed < duration && isActive && _expressionState.value.type == ExpressionType.IDLE) {
                    val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                    pupilTargetX = startX + (targetX - startX) * t
                    pupilTargetY = startY + (targetY - startY) * t
                    delay(16L)
                    elapsed = System.currentTimeMillis() - start
                }
                delay(Random.nextLong(1000, 3000))
                // 回到中心
                val backStart = System.currentTimeMillis()
                elapsed = 0L
                while (elapsed < duration && isActive && _expressionState.value.type == ExpressionType.IDLE) {
                    val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                    pupilTargetX = targetX + (0f - targetX) * t
                    pupilTargetY = targetY + (0f - targetY) * t
                    delay(16L)
                    elapsed = System.currentTimeMillis() - backStart
                }
                pupilTargetX = 0f
                pupilTargetY = 0f
            }
        }
    }
    /**
     * 启动Demo演示模式：自动循环展示所有表情、特效、风格
     */
    fun startDemo() {
        if (isDemoRunning) return
        isDemoRunning = true
        powerOn() // 确保开机状态

        demoJob = scope.launch {
            // 先回到IDLE
            requestExpression(ExpressionType.IDLE, force = true)
            delay(1000)

            while (isActive && isDemoRunning) {
                // 1. 依次展示所有基础表情+特效
                val demoExpressions = listOf(
                    ExpressionType.HAPPY to 2000L,
                    ExpressionType.CONFUSED to 2000L,
                    ExpressionType.RECOGNIZING to 2500L,
                    ExpressionType.SHY to 1800L,
                    ExpressionType.RESPONSE to 1800L,
                    ExpressionType.ALERT to 2000L,
                    ExpressionType.IDLE to 1000L
                )

                for ((type, duration) in demoExpressions) {
                    if (!isDemoRunning) return@launch
                    requestExpression(type, duration = duration, force = true)
                    delay(duration + 300) // 多留300ms过渡
                }

                // 2. 自动眨眼演示
                delay(500)
                performBlinkInternal()
                delay(800)
                performBlinkInternal()
                delay(500)

                // 3. 切换男/女风格展示
                setStyle(ExpressionStyle.MALE)
                requestExpression(ExpressionType.HAPPY, duration = 1500, force = true)
                delay(1800)
                if (!isDemoRunning) return@launch
                
                setStyle(ExpressionStyle.FEMALE)
                requestExpression(ExpressionType.HAPPY, duration = 1500, force = true)
                delay(1800)
                if (!isDemoRunning) return@launch

                // 4. 回到待机
                requestExpression(ExpressionType.IDLE, force = true)
                delay(1000)
            }
        }
        Log.i(TAG, "Demo演示模式已启动")
    }

    fun isDemoRunning(): Boolean = isDemoRunning
    /**
     * 软重置：不重启APP，重置所有状态到初始待机
     */
    /**
     * 软重置：不重启APP，重置所有状态到初始待机
     */
    fun softReset() {
        Log.i(TAG, "Performing soft reset...")
        // 取消所有运行中的协程
        demoJob?.cancel()
        isDemoRunning = false
        effectFallbackJob?.cancel()
        blinkJob?.cancel()
        autoFallbackJob?.cancel()

        // 重置所有状态
        clearEyeEffect()
        setLongPressProgress(0f)
        setTouchPupilOffset(0f, 0f)
        isPoweredOff = false

        // 回到女款默认风格，IDLE状态
        setStyle(ExpressionStyle.FEMALE)
        requestExpression(ExpressionType.IDLE, force = true)

        // 重启基础动画
        startAutoBlink()
        startIdleMicroMotion()

        Log.i(TAG, "Soft reset complete")
    }
        
}














