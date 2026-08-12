package com.roboparty.rpoexpression.vision

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro Filter —— 自适应低通滤波器
 *
 * 核心特性：静止时强滤波抑制噪声，快速移动时自动降低延迟
 *
 * 原理：
 *   截止频率 fc = minCutoff + beta * |dx_hat|
 *   静止时  → |dx_hat|≈0 → fc = minCutoff（低截止 = 强滤波 = 噪声被抑制）
 *   快速移动 → |dx_hat|大  → fc 升高    （高截止 = 弱滤波 = 低延迟）
 *
 * 参数说明：
 *   minCutoff : 基础截止频率(Hz)，越小滤波越强，推荐 0.5~1.0
 *   beta      : 速度系数，越大快速移动时滤波越弱，推荐 0.007~0.02
 *   dCutoff   : 导数滤波截止频率(Hz)，通常固定 1.0
 *
 * 与 5 帧均值滤波的区别：
 *   - 均值滤波是固定窗口，延迟恒定（约 2.5 帧）
 *   - One Euro 是自适应的，静止时延迟大但噪声小，运动时延迟小
 *   - 适合电机控制：静止不抖，运动跟得上
 */
class OneEuroFilter(
    var minCutoff: Float = 1.0f,
    var beta: Float = 0.01f,
    var dCutoff: Float = 1.0f
) {
    private var xPrev: Float = 0f
    private var dxPrev: Float = 0f
    private var initialized: Boolean = false

    /**
     * 运行时更新滤波器参数（支持配置热更新）
     */
    fun setParameters(minCutoff: Float, beta: Float, dCutoff: Float) {
        this.minCutoff = minCutoff
        this.beta = beta
        this.dCutoff = dCutoff
    }

    /**
     * 重置内部状态（模式切换时调用，避免旧值导致跳变）
     */
    fun reset() {
        xPrev = 0f
        dxPrev = 0f
        initialized = false
    }

    /**
     * 输入一个观测值，返回滤波后的值
     * @param x  观测值
     * @param dt 距上次调用的时间间隔(秒)
     */
    fun filter(x: Float, dt: Float): Float {
        // dt 异常（<=0 或 >1s）时直接返回输入并重置状态，避免历史值污染
        if (dt <= 0f || dt > 1.0f) {
            xPrev = x
            dxPrev = 0f
            initialized = true
            return x
        }

        // 首次调用：直接返回输入，初始化状态
        if (!initialized) {
            xPrev = x
            dxPrev = 0f
            initialized = true
            return x
        }

        // 1. 估计导数（变化率）
        val dx = (x - xPrev) / dt

        // 2. 导数低通滤波（平滑变化率估计）
        val alphaD = alpha(dCutoff, dt)
        val dxHat = alphaD * dx + (1f - alphaD) * dxPrev

        // 3. 自适应截止频率：运动越快，截止频率越高，滤波越弱
        val cutoff = minCutoff + beta * abs(dxHat)

        // 4. 信号低通滤波
        val alphaX = alpha(cutoff, dt)
        val xHat = alphaX * x + (1f - alphaX) * xPrev

        // 5. 更新状态
        xPrev = xHat
        dxPrev = dxHat

        return xHat
    }

    /**
     * 计算低通滤波系数 alpha
     * alpha = 1 / (1 + tau/dt), 其中 tau = 1/(2*pi*cutoff)
     * cutoff 越高 → tau 越小 → alpha 越接近 1 → 滤波越弱
     */
    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
