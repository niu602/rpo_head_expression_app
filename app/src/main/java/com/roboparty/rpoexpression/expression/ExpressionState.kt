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
    val transitionProgress: Float = 1f,
    val animFrame: Long = 0L  // 动画帧计数器，驱动持续重绘（瞳孔跟随、眨眼、微动作等依赖每帧更新）
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

    /**
     * 获取应用了强度、眨眼的最终眼部参数
     * 瞳孔滞后滤波统一移至 ExpressionManager 中管理，避免 data class 拷贝导致状态丢失
     */
    fun getEyeParameters(): EyeParameters {
        val baseParams = EyeParameterMapper.getBaseParameters(ExpressionType.IDLE)
        val targetParams = EyeParameterMapper.getBaseParameters(type)

        // 表情切换使用弹性缓动，实现物理回弹效果
        val easedProgress = EyeParameters.elasticEasing(transitionProgress)
        val transitionedParams = EyeParameters.lerp(baseParams, targetParams, easedProgress)
        val finalParams = EyeParameters.applyIntensity(transitionedParams, baseParams, intensity)

        val manager = ExpressionManager.getInstance()

        // 计算目标瞳孔偏移（表情基础偏移 + 视线跟踪 + 微动作 + 临时晃动 + 声源 + 触摸）
        val targetPupilX = finalParams.pupilOffsetX +
                manager.pupilOffsetX * 0.8f +
                manager.microPupilOffsetX +
                manager.tempPupilShakeX +
                manager.soundPupilOffsetX * 0.5f +
                manager.touchPupilOffsetX
        val targetPupilY = finalParams.pupilOffsetY +
                manager.pupilOffsetY * 0.8f +
                manager.microPupilOffsetY +
                manager.touchPupilOffsetY

        // 瞳孔低通滤波（状态保存在 ExpressionManager 单例中，确保跨帧持久化）
        val smoothedPupilX = manager.updateSmoothedPupilX(targetPupilX)
        val smoothedPupilY = manager.updateSmoothedPupilY(targetPupilY)

        // 应用眨眼叠加、IMU临时偏移、视觉模仿
        val blinkOverlay = manager.blinkOverlay
        val tempEyelidOffset = manager.tempEyelidOffset
        val mimicEyelid = manager.mimicEyelidOpenness
        val finalEyelidOpenness = ((finalParams.eyelidOpenness * (1f - blinkOverlay) + tempEyelidOffset) * mimicEyelid)
            .coerceIn(0f, 1f)

        // 叠加音频情绪效果
        val adjustedEyeTail = finalParams.eyeTailAngle + manager.audioEyeShape * 0.5f
        val adjustedPupilSize = finalParams.pupilSize * (1f - manager.audioEyeShape * 0.2f)

        return finalParams.copy(
            eyelidOpenness = finalEyelidOpenness,
            pupilOffsetX = smoothedPupilX + manager.soundPupilOffsetX * 0.3f,
            pupilOffsetY = smoothedPupilY,
            eyeTailAngle = adjustedEyeTail.coerceIn(-1f, 1f),
            pupilSize = adjustedPupilSize.coerceIn(0.5f, 1.2f)
        )
    }
}
