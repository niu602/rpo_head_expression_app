package com.eyebot.status

import com.eyebot.protocol.EyeBotProto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

/**
 * 系统状态眼部映射管理器
 *
 * 功能: 将整机底层状态转化为可视化眼部表现
 * 覆盖: 电池电量、充电状态、系统错误、固件升级 四类状态
 * 特性: 状态优先级叠加、50ms内快速切换、分级告警闪烁
 */
class SystemStatusMapper {

    // 当前输出的眼部参数
    private val _outputParams = MutableStateFlow(EyeParams())
    val outputParams: StateFlow<EyeParams> = _outputParams

    // 各状态源
    private var batteryLevel: Int = 100           // 电量 0-100
    private var chargingState: ChargingState = ChargingState.NOT_CHARGING
    private var currentError: ErrorInfo? = null   // 当前最高优先级错误
    private var updateState: UpdateState? = null  // 升级状态
    private var updateProgress: Float = 0f        // 升级进度 0-1

    // 动画状态
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var animationJob: Job? = null
    private var animationTime: Long = 0L

    // 状态优先级 (数值越大优先级越高)
    private enum class StatusPriority(val level: Int) {
        NORMAL(0),
        BATTERY(10),
        CHARGING(20),
        ERROR_LV1(30),
        ERROR_LV2(40),
        ERROR_LV3(50),
        UPDATE(60),
        ERROR_LV4(100)
    }

    // ============================================================
    //  状态设置接口
    // ============================================================

    /**
     * 更新电池电量
     * @param level 电量百分比 0-100
     */
    fun setBatteryLevel(level: Int) {
        val clamped = level.coerceIn(0, 100)
        if (batteryLevel == clamped) return
        batteryLevel = clamped
        triggerUpdate(fast = false)
    }

    /**
     * 更新充电状态
     */
    fun setChargingState(state: ChargingState) {
        if (chargingState == state) return
        chargingState = state
        triggerUpdate(fast = false)
    }

    /**
     * 上报系统错误
     * @param errorCode 错误码
     * @param level 告警等级 1-4
     */
    fun reportError(errorCode: String, level: Int) {
        val errorInfo = ErrorInfo(
            code = errorCode,
            level = level.coerceIn(1, 4),
            timestamp = System.currentTimeMillis()
        )
        // 只保留最高等级错误
        if (currentError == null || errorInfo.level >= currentError!!.level) {
            currentError = errorInfo
            triggerUpdate(fast = level >= 3) // 严重错误快速切换
        }
    }

    /**
     * 清除错误
     */
    fun clearError(errorCode: String? = null) {
        if (errorCode == null || currentError?.code == errorCode) {
            currentError = null
            triggerUpdate(fast = false)
        }
    }

    /**
     * 设置固件升级状态
     */
    fun setUpdateState(state: UpdateState?, progress: Float = 0f) {
        updateState = state
        updateProgress = progress.coerceIn(0f, 1f)
        triggerUpdate(fast = state != null)
    }

    /**
     * 更新升级进度
     */
    fun setUpdateProgress(progress: Float) {
        updateProgress = progress.coerceIn(0f, 1f)
        // 进度更新不需要触发完整重算，动画循环内自动处理
    }

    // ============================================================
    //  核心映射逻辑
    // ============================================================

    /**
     * 计算当前最高优先级状态并生成眼部参数
     */
    private fun calculateEyeParams(time: Long): EyeParams {
        // 按优先级从高到低判断
        return when {
            // Lv4 致命错误 - 最高优先级
            currentError?.level == 4 -> mapErrorLv4(time)
            // 固件升级
            updateState != null -> mapUpdateState(time)
            // Lv3 严重警告
            currentError?.level == 3 -> mapErrorLv3(time)
            // Lv2 警告
            currentError?.level == 2 -> mapErrorLv2(time)
            // Lv1 提示
            currentError?.level == 1 -> mapErrorLv1(time)
            // 充电状态
            chargingState != ChargingState.NOT_CHARGING -> mapChargingState(time)
            // 默认: 电池电量
            else -> mapBatteryState(time)
        }
    }

    // ---------- 电池电量映射 ----------

    private fun mapBatteryState(time: Long): EyeParams {
        val level = batteryLevel
        return when {
            level >= 80 -> EyeParams(
                eyelidOpen = 1.0f,
                pupilSize = 0.7f,
                eyeShape = EyeShape.NORMAL,
                glowColor = 0xFF00FF88.toInt(),
                glowIntensity = 0.3f + 0.1f * breathe(time, 2000)
            )
            level >= 50 -> EyeParams(
                eyelidOpen = 0.85f,
                pupilSize = 0.65f,
                eyeShape = EyeShape.NORMAL,
                glowColor = 0xFF00FF88.toInt(),
                glowIntensity = 0.25f + 0.08f * breathe(time, 2000)
            )
            level >= 20 -> EyeParams(
                eyelidOpen = 0.7f,
                pupilSize = 0.6f,
                eyeShape = EyeShape.SLEEPY,
                glowColor = 0xFFFFDD00.toInt(),
                glowIntensity = 0.3f + 0.1f * breathe(time, 3000)
            )
            level >= 10 -> EyeParams(
                eyelidOpen = 0.5f * blink(time, 1000),
                pupilSize = 0.5f,
                eyeShape = EyeShape.SLEEPY,
                glowColor = 0xFFFFAA00.toInt(),
                glowIntensity = 0.5f * blink(time, 1000)
            )
            level >= 5 -> EyeParams(
                eyelidOpen = 0.3f * blink(time, 500),
                pupilSize = 0.45f,
                eyeShape = EyeShape.SAD,
                glowColor = 0xFFFF6600.toInt(),
                glowIntensity = 0.7f * blink(time, 500)
            )
            else -> EyeParams(
                eyelidOpen = 0.15f * blink(time, 333),
                pupilSize = 0.4f,
                eyeShape = EyeShape.SAD,
                glowColor = 0xFFFF3333.toInt(),
                glowIntensity = 0.9f * blink(time, 333)
            )
        }
    }

    // ---------- 充电状态映射 ----------

    private fun mapChargingState(time: Long): EyeParams {
        return when (chargingState) {
            ChargingState.CHARGING -> {
                val wave = (sin(time / 1500.0 * Math.PI * 2) * 0.5 + 0.5).toFloat()
                EyeParams(
                    eyelidOpen = 0.7f + wave * 0.2f,
                    pupilSize = 0.75f,
                    eyeShape = EyeShape.HAPPY,
                    glowColor = 0xFF00DDFF.toInt(),
                    glowIntensity = 0.4f + wave * 0.2f
                )
            }
            ChargingState.FAST_CHARGING -> {
                val pulse = (sin(time / 800.0 * Math.PI * 2) * 0.5 + 0.5).toFloat()
                EyeParams(
                    eyelidOpen = 0.85f + pulse * 0.15f,
                    pupilSize = 0.8f,
                    eyeShape = EyeShape.HAPPY,
                    glowColor = 0xFF00AAFF.toInt(),
                    glowIntensity = 0.6f + pulse * 0.2f
                )
            }
            ChargingState.FULL -> EyeParams(
                eyelidOpen = 1.0f,
                pupilSize = 0.7f,
                eyeShape = EyeShape.HAPPY,
                glowColor = 0xFF00FF88.toInt(),
                glowIntensity = 0.5f + 0.1f * breathe(time, 2500)
            )
            ChargingState.ERROR -> EyeParams(
                eyelidOpen = 0.6f * blink(time, 1000),
                pupilSize = 0.5f,
                eyeShape = EyeShape.SAD,
                glowColor = 0xFFFF6600.toInt(),
                glowIntensity = 0.6f * blink(time, 1000)
            )
            else -> mapBatteryState(time)
        }
    }

    // ---------- 错误等级映射 ----------

    private fun mapErrorLv1(time: Long): EyeParams {
        // Lv1: 蓝色慢闪
        val blinkVal = blink(time, 2000)
        return EyeParams(
            eyelidOpen = 0.9f,
            pupilSize = 0.6f,
            eyeShape = EyeShape.NORMAL,
            glowColor = 0xFF00AAFF.toInt(),
            glowIntensity = 0.3f + 0.2f * blinkVal,
            overlayBorder = true
        )
    }

    private fun mapErrorLv2(time: Long): EyeParams {
        // Lv2: 黄色 1Hz 闪烁
        val blinkVal = blink(time, 1000)
        return EyeParams(
            eyelidOpen = 0.95f,
            pupilSize = 0.7f,
            eyeShape = EyeShape.NORMAL,
            glowColor = 0xFFFFDD00.toInt(),
            glowIntensity = 0.4f + 0.3f * blinkVal
        )
    }

    private fun mapErrorLv3(time: Long): EyeParams {
        // Lv3: 橙色 2Hz 快速闪
        val blinkVal = blink(time, 500)
        return EyeParams(
            eyelidOpen = 1.0f * blinkVal,
            pupilSize = 0.8f,
            eyeShape = EyeShape.ANGRY,
            glowColor = 0xFFFF8800.toInt(),
            glowIntensity = 0.5f + 0.3f * blinkVal
        )
    }

    private fun mapErrorLv4(time: Long): EyeParams {
        // Lv4: 红色 4Hz 急促闪
        val blinkVal = blink(time, 250)
        return EyeParams(
            eyelidOpen = 0.5f + 0.5f * blinkVal,
            pupilSize = 0.6f + 0.3f * blinkVal,
            eyeShape = EyeShape.ANGRY,
            glowColor = 0xFFFF2222.toInt(),
            glowIntensity = 0.7f + 0.3f * blinkVal,
            shakeIntensity = 0.2f * blinkVal
        )
    }

    // ---------- 固件升级映射 ----------

    private fun mapUpdateState(time: Long): EyeParams {
        return when (updateState) {
            UpdateState.PREPARING -> EyeParams(
                eyelidOpen = 0.8f,
                pupilSize = 0.6f,
                eyeShape = EyeShape.NORMAL,
                glowColor = 0xFFAA88FF.toInt(),
                glowIntensity = 0.4f + 0.1f * breathe(time, 2000)
            )
            UpdateState.DOWNLOADING -> {
                // 进度条式眼睑填充
                val progress = updateProgress
                EyeParams(
                    eyelidOpen = 0.5f + progress * 0.35f,
                    pupilSize = 0.65f,
                    eyeShape = EyeShape.NORMAL,
                    glowColor = 0xFF6666FF.toInt(),
                    glowIntensity = 0.6f,
                    progressIndicator = progress
                )
            }
            UpdateState.VERIFYING -> {
                // 瞳孔旋转效果
                val angle = (time / 20f) % 360
                EyeParams(
                    eyelidOpen = 0.7f,
                    pupilSize = 0.5f,
                    pupilOffsetX = sin(Math.toRadians(angle.toDouble())).toFloat() * 0.15f,
                    pupilOffsetY = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat() * 0.15f,
                    eyeShape = EyeShape.NORMAL,
                    glowColor = 0xFFFFAA00.toInt(),
                    glowIntensity = 0.5f
                )
            }
            UpdateState.INSTALLING -> {
                // 波浪式眼睑运动
                val wave = (sin(time / 600.0 * Math.PI * 2) * 0.5 + 0.5).toFloat()
                EyeParams(
                    eyelidOpen = 0.45f + wave * 0.15f,
                    pupilSize = 0.55f,
                    eyeShape = EyeShape.SLEEPY,
                    glowColor = 0xFFFF8800.toInt(),
                    glowIntensity = 0.7f
                )
            }
            UpdateState.REBOOTING -> {
                // 闭眼到睁眼开机动画
                val t = (time % 2000) / 2000f
                EyeParams(
                    eyelidOpen = t,
                    pupilSize = 0.7f * t,
                    eyeShape = EyeShape.NORMAL,
                    glowColor = 0xFFFFFFFF.toInt(),
                    glowIntensity = 0.8f * t
                )
            }
            UpdateState.SUCCESS -> {
                // 闪3次绿光
                val flash = if ((time / 400) % 2 == 0L) 1f else 0.3f
                EyeParams(
                    eyelidOpen = 1.0f,
                    pupilSize = 0.75f,
                    eyeShape = EyeShape.HAPPY,
                    glowColor = 0xFF00FF88.toInt(),
                    glowIntensity = 0.7f * flash
                )
            }
            UpdateState.FAILED -> EyeParams(
                eyelidOpen = 0.4f * blink(time, 1500),
                pupilSize = 0.5f,
                eyeShape = EyeShape.SAD,
                glowColor = 0xFFFF3333.toInt(),
                glowIntensity = 0.8f * blink(time, 1500)
            )
            null -> mapBatteryState(time)
        }
    }

    // ============================================================
    //  动画工具函数
    // ============================================================

    /**
     * 呼吸曲线 (正弦波 0~1)
     */
    private fun breathe(time: Long, periodMs: Long): Float {
        return (sin(time.toDouble() / periodMs * Math.PI * 2) * 0.5 + 0.5).toFloat()
    }

    /**
     * 方波闪烁 0~1
     */
    private fun blink(time: Long, periodMs: Long): Float {
        val phase = (time % periodMs).toFloat() / periodMs
        return if (phase < 0.5f) 1f else 0.3f
    }

    // ============================================================
    //  状态切换控制
    // ============================================================

    private fun triggerUpdate(fast: Boolean) {
        // 立即计算一帧，保证 50ms 内响应
        val immediateParams = calculateEyeParams(animationTime)
        _outputParams.value = immediateParams

        // 确保动画循环在运行
        ensureAnimationRunning()
    }

    private fun ensureAnimationRunning() {
        if (animationJob?.isActive == true) return
        animationJob = scope.launch {
            val targetFps = 60
            val frameTime = 1000L / targetFps
            val startTime = System.currentTimeMillis()

            while (isActive) {
                animationTime = System.currentTimeMillis() - startTime
                val params = calculateEyeParams(animationTime)
                _outputParams.value = params
                delay(frameTime)
            }
        }
    }

    /**
     * 获取当前静态参数 (用于快速切换测试)
     */
    fun getCurrentStaticParams(): EyeParams {
        return calculateEyeParams(0)
    }

    fun release() {
        animationJob?.cancel()
        scope.cancel()
    }

    // ============================================================
    //  数据类定义
    // ============================================================

    /**
     * 眼部参数输出
     */
    data class EyeParams(
        val eyelidOpen: Float = 1.0f,
        val pupilSize: Float = 0.7f,
        val pupilOffsetX: Float = 0f,
        val pupilOffsetY: Float = 0f,
        val eyeShape: EyeShape = EyeShape.NORMAL,
        val glowIntensity: Float = 0f,
        val glowColor: Int = 0xFF00FF88.toInt(),
        val overlayBorder: Boolean = false,
        val shakeIntensity: Float = 0f,
        val progressIndicator: Float = 0f
    )

    enum class EyeShape {
        NORMAL, HAPPY, SAD, ANGRY, SURPRISED, SLEEPY
    }

    enum class ChargingState {
        NOT_CHARGING,
        CHARGING,
        FAST_CHARGING,
        FULL,
        ERROR
    }

    enum class UpdateState {
        PREPARING,
        DOWNLOADING,
        VERIFYING,
        INSTALLING,
        REBOOTING,
        SUCCESS,
        FAILED
    }

    data class ErrorInfo(
        val code: String,
        val level: Int,
        val timestamp: Long
    )
}

// ============================================================
//  状态采集适配器
// ============================================================

/**
 * 系统状态采集器
 * 监听Android系统广播，自动更新状态映射
 */
class SystemStatusCollector(
    private val mapper: SystemStatusMapper
) {
    // 模拟电池状态监听
    fun onBatteryChanged(level: Int, isCharging: Boolean, isFast: Boolean = false) {
        mapper.setBatteryLevel(level)
        val chargingState = when {
            !isCharging -> SystemStatusMapper.ChargingState.NOT_CHARGING
            isFast -> SystemStatusMapper.ChargingState.FAST_CHARGING
            else -> SystemStatusMapper.ChargingState.CHARGING
        }
        mapper.setChargingState(chargingState)
    }

    // 模拟系统错误上报
    fun onSystemError(errorCode: String, level: Int) {
        mapper.reportError(errorCode, level)
    }

    fun onErrorResolved(errorCode: String) {
        mapper.clearError(errorCode)
    }

    // 模拟升级状态
    fun onUpdateStart() {
        mapper.setUpdateState(SystemStatusMapper.UpdateState.PREPARING)
    }

    fun onUpdateDownload(progress: Float) {
        mapper.setUpdateState(SystemStatusMapper.UpdateState.DOWNLOADING, progress)
        mapper.setUpdateProgress(progress)
    }

    fun onUpdateVerify() {
        mapper.setUpdateState(SystemStatusMapper.UpdateState.VERIFYING)
    }

    fun onUpdateInstall() {
        mapper.setUpdateState(SystemStatusMapper.UpdateState.INSTALLING)
    }

    fun onUpdateSuccess() {
        mapper.setUpdateState(SystemStatusMapper.UpdateState.SUCCESS)
        // 3秒后清除成功状态
        // Handler(Looper.getMainLooper()).postDelayed({
        //     mapper.setUpdateState(null)
        // }, 3000)
    }

    fun onUpdateFailed() {
        mapper.setUpdateState(SystemStatusMapper.UpdateState.FAILED)
    }
}
