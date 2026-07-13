/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 表情状态数据类
 * 封装当前表情的完整状态信息，用于状态机管理和动画渲染
 *
 * @property type 当前表情类型
 * @property intensity 表情强度 0-100，控制表情幅度，支持无级渐变
 * @property style 当前渲染风格
 * @property startTime 表情开始时间戳（毫秒）
 * @property duration 表情持续时长（毫秒），Long.MAX_VALUE表示持续直到被打断
 * @property transitionProgress 过渡动画进度，用于表情切换平滑过渡
 */
data class ExpressionState(
    val type: ExpressionType = ExpressionType.IDLE,
    val intensity: Int = 100,
    val style: ExpressionStyle = ExpressionStyle.CARTOON,
    val startTime: Long = System.currentTimeMillis(),
    val duration: Long = type.defaultDuration,
    val transitionProgress: Float = 1f
) {
    /**
     * 表情是否已过期（需要自动回落）
     */
    fun isExpired(): Boolean {
        if (!type.isAutoFallback) return false
        return System.currentTimeMillis() - startTime >= duration
    }

    /**
     * 更新过渡动画进度
     * @param transitionDuration 过渡时长（毫秒）
     */
    fun updateProgress(transitionDuration: Long = 100L): ExpressionState {
        // 计算过渡进度
        val transitionElapsed = (System.currentTimeMillis() - startTime).coerceAtMost(transitionDuration)
        val newTransitionProgress = (transitionElapsed / transitionDuration.toFloat()).coerceIn(0f, 1f)

        return copy(
            transitionProgress = newTransitionProgress
        )
    }

    // 瞳孔位置滞后缓存，实现物理惯性
    private var lastPupilX = 0f
    private var lastPupilY = 0f

    /**
     * 获取应用了强度、眨眼、瞳孔滞后的最终眼部参数
     */
    fun getEyeParameters(): EyeParameters {
        val baseParams = EyeParameterMapper.getBaseParameters(ExpressionType.IDLE)
        val targetParams = EyeParameterMapper.getBaseParameters(type)

        // 表情切换使用弹性缓动，实现物理回弹效果
        val easedProgress = EyeParameters.elasticEasing(transitionProgress)
        val transitionedParams = EyeParameters.lerp(baseParams, targetParams, easedProgress)
        val finalParams = EyeParameters.applyIntensity(transitionedParams, baseParams, intensity)

        // 瞳孔偏移使用减速缓动，实现物理滞后效果
        val manager = ExpressionManager.getInstance()
        val targetPupilX = finalParams.pupilOffsetX + 
                manager.pupilOffsetX * 0.6f + 
                manager.microPupilOffsetX
        val targetPupilY = finalParams.pupilOffsetY + 
                manager.pupilOffsetY * 0.6f + 
                manager.microPupilOffsetY
        // 瞳孔低通滤波，系数0.06，非常平滑，消除抖动
        val dx = targetPupilX - lastPupilX
        val dy = targetPupilY - lastPupilY
        // 死区：差值小于0.005时直接对齐，避免微小抖动
        if (kotlin.math.abs(dx) < 0.005f) {
            lastPupilX = targetPupilX
        } else {
            lastPupilX += dx * 0.06f
        }
        if (kotlin.math.abs(dy) < 0.005f) {
            lastPupilY = targetPupilY
        } else {
            lastPupilY += dy * 0.06f
        }

        // 应用眨眼叠加：在当前眼睑开合度基础上乘以(1 - blinkOverlay)
        val blinkOverlay = ExpressionManager.getInstance().blinkOverlay
        val finalEyelidOpenness = finalParams.eyelidOpenness * (1f - blinkOverlay)

        return finalParams.copy(
            eyelidOpenness = finalEyelidOpenness,
            pupilOffsetX = lastPupilX,
            pupilOffsetY = lastPupilY
        )
    }
}
