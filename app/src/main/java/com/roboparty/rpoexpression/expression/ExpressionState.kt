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
    val style: ExpressionStyle = ExpressionStyle.FEMALE,
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

        // 计算目标瞳孔偏移（表情基础偏移 + 视线跟踪 + 微动作 + 临时晃动 + 触摸）
        val targetPupilX = finalParams.pupilOffsetX +
                manager.pupilOffsetX * 0.8f +
                manager.microPupilOffsetX +
                manager.tempPupilShakeX +
                manager.touchPupilOffsetX
        val targetPupilY = finalParams.pupilOffsetY +
                manager.pupilOffsetY * 0.8f +
                manager.microPupilOffsetY +
                manager.touchPupilOffsetY

        // 瞳孔低通滤波（状态保存在 ExpressionManager 单例中，确保跨帧持久化）
        val smoothedPupilX = manager.updateSmoothedPupilX(targetPupilX)
        val smoothedPupilY = manager.updateSmoothedPupilY(targetPupilY)

        // 应用眨眼叠加、IMU临时偏移
        val blinkOverlay = manager.blinkProgress
        val tempEyelidOffset = manager.tempEyelidOffset
        val finalEyelidOpenness = (finalParams.eyelidOpenness * (1f - blinkOverlay) + tempEyelidOffset)
            .coerceIn(0f, 1f)

        // 距离感知瞳孔缩放
        val adjustedEyeTail = finalParams.eyeTailAngle
        val adjustedPupilSize = finalParams.pupilSize * 
                manager.distancePupilScale

        // 应用性别切换动画overlay
        val genderOverlay = manager.getGenderSwitchOverlay()
        val finalEyelid = if (manager.isGenderSwitching()) {
            finalEyelidOpenness * genderOverlay.eyelidOpenness
        } else {
            finalEyelidOpenness
        }
        val finalEyeHeight = if (manager.isGenderSwitching()) {
            finalParams.eyeHeightRatio * genderOverlay.eyeHeightRatio / 0.7f
        } else {
            finalParams.eyeHeightRatio
        }
        val finalPupilSizeGender = if (manager.isGenderSwitching()) {
            adjustedPupilSize * genderOverlay.pupilSize / 0.6f
        } else {
            adjustedPupilSize
        }
        val finalGlowIntensity = if (manager.isGenderSwitching()) {
            finalParams.glowIntensity + genderOverlay.glowIntensity
        } else {
            finalParams.glowIntensity
        }


        // 应用长按进度overlay
        val longPressOverlay = manager.getLongPressOverlay()
        val finalEyelidLongPress = if (longPressOverlay.eyelidOpenness < 1f) {
            finalEyelid * longPressOverlay.eyelidOpenness
        } else {
            finalEyelid
        }
        val finalGlowLongPress = finalGlowIntensity + longPressOverlay.glowIntensity

                // 应用关机overlay（最高优先级）
        val powerOffOverlay = manager.getPowerOffOverlay()
        val finalEyelidPowerOff = finalEyelidLongPress * powerOffOverlay.eyelidOpenness
        val finalEyeHeightPowerOff = finalEyeHeight * powerOffOverlay.eyeHeightRatio / 0.7f
        val finalPupilPowerOff = finalPupilSizeGender * powerOffOverlay.pupilSize / 0.6f
        val finalGlowPowerOff = finalGlowLongPress * powerOffOverlay.glowIntensity / 0.3f
        return finalParams.copy(
            eyelidOpenness = finalEyelidPowerOff.coerceIn(0f, 1f),
            pupilOffsetX = smoothedPupilX,
            pupilOffsetY = smoothedPupilY,
            eyeTailAngle = adjustedEyeTail.coerceIn(-1f, 1f),
            pupilSize = finalPupilPowerOff.coerceIn(0f, 1.2f),
            eyeHeightRatio = finalEyeHeightPowerOff.coerceIn(0f, 1f),
            glowIntensity = finalGlowPowerOff.coerceIn(0f, 1f),
            glowBlink = genderOverlay.glowBlink
        )
    }

    /**
     * 获取应用了强度、过渡的能量场参数（能量面部模式）
     *
     * 逻辑与 [getEyeParameters] 完全对称：
     * 1. 从 EnergyFieldParameterMapper 取 base params（IDLE）和 target params（当前表情）
     * 2. 应用弹性缓动过渡插值
     * 3. 应用 intensity 强度缩放
     * 4. 应用视线跟踪/微动作到焦点位置
     * 5. 应用关机/长按 overlay
     */
    fun getEnergyFieldParameters(): EnergyFieldParameters {
        val baseParams = EnergyFieldParameterMapper.map(ExpressionType.IDLE)
        val targetParams = EnergyFieldParameterMapper.map(type)

        // 表情切换使用弹性缓动（复用 EyeParameters 的缓动函数）
        val easedProgress = EyeParameters.elasticEasing(transitionProgress)
        val transitionedParams = baseParams.lerp(targetParams, easedProgress)

        // 应用 intensity 强度缩放
        val intensityT = (intensity / 100f).coerceIn(0f, 1f)
        var finalParams = baseParams.lerp(transitionedParams, intensityT)

        val manager = ExpressionManager.getInstance()

        // 视线跟踪 / 微动作 / 触摸 → 映射到能量焦点位置
        // 系数提升到0.5，让焦点跟随更明显
        val gazeX = finalParams.corePositionX +
                manager.pupilOffsetX * 0.5f +
                manager.microPupilOffsetX * 0.5f +
                manager.tempPupilShakeX * 0.5f +
                manager.touchPupilOffsetX
        val gazeY = finalParams.corePositionY +
                manager.pupilOffsetY * 0.5f +
                manager.microPupilOffsetY * 0.5f +
                manager.touchPupilOffsetY

        // 关机/长按 overlay：缩放焦点亮度和大小
        val powerOffOverlay = manager.getPowerOffOverlay()
        val longPressOverlay = manager.getLongPressOverlay()
        val powerScale = powerOffOverlay.eyelidOpenness *
                (1f - longPressOverlay.eyelidOpenness.coerceIn(0f, 1f) * 0.3f)

        finalParams = finalParams.copy(
            corePositionX = gazeX.coerceIn(-1f, 1f),
            corePositionY = gazeY.coerceIn(-1f, 1f),
            coreBrightness = (finalParams.coreBrightness * powerScale).coerceIn(0f, 1f),
            coreSize = (finalParams.coreSize * powerScale).coerceIn(0f, 1f)
        )

        return finalParams
    }
}






