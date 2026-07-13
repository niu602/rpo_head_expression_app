/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

import androidx.compose.ui.graphics.Color

/**
 * 表情参数映射器
 * 为每种表情类型定义对应的基础眼部参数
 * 所有参数严格限定在眼部区域，不涉及下半脸
 */
object EyeParameterMapper {

    /**
     * 获取指定表情类型的基础眼部参数
     */
    fun getBaseParameters(type: ExpressionType): EyeParameters {
        return when (type) {
            // ==================== 功能类表情 ====================
            ExpressionType.IDLE -> idleParams()
            ExpressionType.BLINK -> blinkParams()
            ExpressionType.SPEAKING -> speakingParams()
            ExpressionType.THINKING -> thinkingParams()
            ExpressionType.RECOGNIZING -> recognizingParams()
            ExpressionType.RESPONSE -> responseParams()

            // ==================== 情绪类表情 ====================
            ExpressionType.HAPPY -> happyParams()
            ExpressionType.CONFUSED -> confusedParams()
            ExpressionType.SURPRISED -> surprisedParams()
            ExpressionType.SAD -> sadParams()
            ExpressionType.ANGRY -> angryParams()
            ExpressionType.SLEEPY -> sleepyParams()
            ExpressionType.SHY -> shyParams()

            // ==================== 系统状态类表情 ====================
            ExpressionType.LOW_BATTERY -> lowBatteryParams()
            ExpressionType.CHARGING -> chargingParams()
            ExpressionType.ALERT -> alertParams()
            ExpressionType.SLEEPING -> sleepingParams()
            ExpressionType.UPDATING -> updatingParams()
        }
    }

    // ==================== 功能类参数实现 ====================

    /**
     * 待机：正常睁开，柔和蓝光，轻微呼吸
     */
    private fun idleParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.95f,
        upperEyelidCurve = 0.5f,
        lowerEyelidCurve = 0.3f,
        pupilSize = 0.6f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 1f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 0.7f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFF4FC3F7),
        glowIntensity = 0.3f,
        glowRadius = 0.4f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0.1f
    )

    /**
     * 眨眼：眼睑快速闭合
     */
    private fun blinkParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0f,
        upperEyelidCurve = 0.8f,
        lowerEyelidCurve = 0.6f,
        pupilSize = 0.6f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 0.9f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 0.1f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFF4FC3F7),
        glowIntensity = 0.2f,
        glowRadius = 0.3f,
        glowBlink = false,
        blinkProgress = 1f,
        unfocused = 0f
    )

    /**
     * 说话：眼睑轻微颤动，瞳孔随说话微动
     */
    private fun speakingParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.9f,
        upperEyelidCurve = 0.55f,
        lowerEyelidCurve = 0.35f,
        pupilSize = 0.65f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 1.05f,
        eyeTailAngle = 0.1f,
        eyeHeightRatio = 0.75f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFF81D4FA),
        glowIntensity = 0.4f,
        glowRadius = 0.5f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0f
    )

    /**
     * 思考：瞳孔向上偏移，眼睑微合
     */
    private fun thinkingParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.75f,
        upperEyelidCurve = 0.6f,
        lowerEyelidCurve = 0.4f,
        pupilSize = 0.55f,
        pupilOffsetX = 0f,
        pupilOffsetY = -0.3f,
        pupilScale = 0.95f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 0.6f,
        eyeWidthRatio = 0.95f,
        glowColor = Color(0xFFFFD54F),
        glowIntensity = 0.35f,
        glowRadius = 0.4f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0.3f
    )

    /**
     * 识别中：瞳孔聚焦，紫光扫描效果
     */
    private fun recognizingParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.95f,
        upperEyelidCurve = 0.5f,
        lowerEyelidCurve = 0.3f,
        pupilSize = 0.5f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 1.1f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 0.75f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFF7C4DFF),
        glowIntensity = 0.6f,
        glowRadius = 0.6f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0f
    )

    /**
     * 应答反馈：眼尾微扬，金色高光
     */
    private fun responseParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.9f,
        upperEyelidCurve = 0.45f,
        lowerEyelidCurve = 0.5f,
        pupilSize = 0.7f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 1.15f,
        eyeTailAngle = 0.3f,
        eyeHeightRatio = 0.7f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFFFFD700),
        glowIntensity = 0.7f,
        glowRadius = 0.6f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0f
    )

    // ==================== 情绪类参数实现 ====================

    /**
     * 开心：眼尾大幅上扬，瞳孔放大，下眼睑弯曲（笑眼）
     */
    private fun happyParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.85f,
        upperEyelidCurve = 0.4f,
        lowerEyelidCurve = 0.7f,
        pupilSize = 0.75f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 1.2f,
        eyeTailAngle = 0.6f,
        eyeHeightRatio = 0.65f,
        eyeWidthRatio = 1.05f,
        glowColor = Color(0xFFFFEB3B),
        glowIntensity = 0.6f,
        glowRadius = 0.5f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0f
    )

    /**
     * 疑惑：一高一低？不，单侧斜视，眼睑微蹙
     */
    private fun confusedParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.8f,
        upperEyelidCurve = 0.65f,
        lowerEyelidCurve = 0.35f,
        pupilSize = 0.55f,
        pupilOffsetX = 0.4f,
        pupilOffsetY = 0f,
        pupilScale = 0.9f,
        eyeTailAngle = -0.2f,
        eyeHeightRatio = 0.65f,
        eyeWidthRatio = 0.95f,
        glowColor = Color(0xFFFFB74D),
        glowIntensity = 0.3f,
        glowRadius = 0.4f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0.2f
    )

    /**
     * 惊讶：眼睑完全张开，瞳孔缩小，眼型变圆
     */
    private fun surprisedParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 1f,
        upperEyelidCurve = 0.3f,
        lowerEyelidCurve = 0.2f,
        pupilSize = 0.4f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 0.8f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 1f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFFFFFFFF),
        glowIntensity = 0.7f,
        glowRadius = 0.7f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0f
    )

    /**
     * 委屈/难过：眼尾下垂，眼睑半合，瞳孔向下
     */
    private fun sadParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.6f,
        upperEyelidCurve = 0.7f,
        lowerEyelidCurve = 0.5f,
        pupilSize = 0.5f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0.3f,
        pupilScale = 0.85f,
        eyeTailAngle = -0.6f,
        eyeHeightRatio = 0.5f,
        eyeWidthRatio = 0.9f,
        glowColor = Color(0xFF90CAF9),
        glowIntensity = 0.25f,
        glowRadius = 0.3f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0.4f
    )

    /**
     * 生气：眼睑收紧，瞳孔缩小，红光效
     */
    private fun angryParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.7f,
        upperEyelidCurve = 0.8f,
        lowerEyelidCurve = 0.6f,
        pupilSize = 0.4f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 0.85f,
        eyeTailAngle = -0.3f,
        eyeHeightRatio = 0.55f,
        eyeWidthRatio = 0.95f,
        glowColor = Color(0xFFEF5350),
        glowIntensity = 0.7f,
        glowRadius = 0.6f,
        glowBlink = true,
        blinkProgress = 0f,
        unfocused = 0f
    )

    /**
     * 困倦：眼睑严重下垂，瞳孔涣散
     */
    private fun sleepyParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.3f,
        upperEyelidCurve = 0.85f,
        lowerEyelidCurve = 0.5f,
        pupilSize = 0.45f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0.2f,
        pupilScale = 0.8f,
        eyeTailAngle = -0.2f,
        eyeHeightRatio = 0.3f,
        eyeWidthRatio = 0.9f,
        glowColor = Color(0xFFB0BEC5),
        glowIntensity = 0.15f,
        glowRadius = 0.2f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0.7f
    )

    /**
     * 害羞：眼睑微垂，眼周粉光，瞳孔偏移
     */
    private fun shyParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.65f,
        upperEyelidCurve = 0.6f,
        lowerEyelidCurve = 0.4f,
        pupilSize = 0.5f,
        pupilOffsetX = -0.3f,
        pupilOffsetY = 0.2f,
        pupilScale = 0.9f,
        eyeTailAngle = 0.1f,
        eyeHeightRatio = 0.55f,
        eyeWidthRatio = 0.95f,
        glowColor = Color(0xFFF48FB1),
        glowIntensity = 0.5f,
        glowRadius = 0.5f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0.2f
    )

    // ==================== 系统状态类参数实现 ====================

    /**
     * 低电量：眼睑半合，黄光闪烁
     */
    private fun lowBatteryParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.4f,
        upperEyelidCurve = 0.7f,
        lowerEyelidCurve = 0.5f,
        pupilSize = 0.4f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 0.75f,
        eyeTailAngle = -0.3f,
        eyeHeightRatio = 0.35f,
        eyeWidthRatio = 0.85f,
        glowColor = Color(0xFFFFB300),
        glowIntensity = 0.4f,
        glowRadius = 0.3f,
        glowBlink = true,
        blinkProgress = 0f,
        unfocused = 0.5f
    )

    /**
     * 充电中：瞳孔呼吸，绿光流动
     */
    private fun chargingParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.8f,
        upperEyelidCurve = 0.5f,
        lowerEyelidCurve = 0.4f,
        pupilSize = 0.65f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 1f,
        eyeTailAngle = 0.2f,
        eyeHeightRatio = 0.65f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFF66BB6A),
        glowIntensity = 0.6f,
        glowRadius = 0.6f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0.1f
    )

    /**
     * 告警：眼睑张大，红光急闪
     */
    private fun alertParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 1f,
        upperEyelidCurve = 0.4f,
        lowerEyelidCurve = 0.3f,
        pupilSize = 0.35f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 0.9f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 0.8f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFFE53935),
        glowIntensity = 0.9f,
        glowRadius = 0.8f,
        glowBlink = true,
        blinkProgress = 0f,
        unfocused = 0f
    )

    /**
     * 休眠：眼睑完全闭合，呼吸微光
     */
    private fun sleepingParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0f,
        upperEyelidCurve = 0.7f,
        lowerEyelidCurve = 0.6f,
        pupilSize = 0f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 0f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 0.05f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFF37474F),
        glowIntensity = 0.1f,
        glowRadius = 0.2f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 1f
    )

    /**
     * 固件升级：蓝色进度光效，瞳孔聚焦
     */
    private fun updatingParams(): EyeParameters = EyeParameters(
        eyelidOpenness = 0.9f,
        upperEyelidCurve = 0.5f,
        lowerEyelidCurve = 0.3f,
        pupilSize = 0.6f,
        pupilOffsetX = 0f,
        pupilOffsetY = 0f,
        pupilScale = 1f,
        eyeTailAngle = 0f,
        eyeHeightRatio = 0.7f,
        eyeWidthRatio = 1f,
        glowColor = Color(0xFF2196F3),
        glowIntensity = 0.7f,
        glowRadius = 0.7f,
        glowBlink = false,
        blinkProgress = 0f,
        unfocused = 0f
    )
}
