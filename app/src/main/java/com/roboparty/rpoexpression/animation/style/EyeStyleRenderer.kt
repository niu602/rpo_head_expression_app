/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.style

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.roboparty.rpoexpression.expression.EyeParameters

/**
 * 眼部风格渲染器接口
 * 不同风格实现此接口，实现参数化绘制
 */
interface EyeStyleRenderer {

    /**
     * 是否支持眼内特效
     * 传统风格默认支持，SVG等极简风格可以禁用
     */
    val supportsEyeEffects: Boolean get() = true

    /**
     * 绘制双眼
     * @param params 眼部参数
     * @param centerX 画布中心X
     * @param centerY 画布中心Y
     * @param size 画布尺寸
     * @param time 动画时间（秒）
     */
    fun DrawScope.drawEyes(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    )

    /**
     * 计算单眼的基础位置和尺寸
     */
    fun calculateEyeMetrics(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size
    ): EyeMetrics {
        val scale = Math.min(size.width, size.height) / 1080f
        // 放大眼睛整体尺寸，让眼白+瞳孔区域在屏幕上占比更大，视觉更饱满
        val eyeSpacing = 560f * scale  // 双眼中心间距（半距），随尺寸微调避免拥挤
        val baseEyeWidth = 700f * scale * params.eyeWidthRatio  // 单眼宽度
        val baseEyeHeight = 610f * scale * params.eyeHeightRatio  // 单眼高度

        return EyeMetrics(
            leftEyeCenter = Offset(centerX - eyeSpacing, centerY),
            rightEyeCenter = Offset(centerX + eyeSpacing, centerY),
            eyeWidth = baseEyeWidth,
            eyeHeight = baseEyeHeight,
            scale = scale
        )
    }

    /**
     * 计算瞳孔位置
     */
    fun calculatePupilPosition(
        eyeCenter: Offset,
        eyeWidth: Float,
        eyeHeight: Float,
        params: EyeParameters
    ): Offset {
        val maxOffsetX = eyeWidth * 0.3f  // 增大瞳孔可移动范围
        val maxOffsetY = eyeHeight * 0.3f
        return Offset(
            x = eyeCenter.x + params.pupilOffsetX * maxOffsetX,
            y = eyeCenter.y + params.pupilOffsetY * maxOffsetY
        )
    }
}

/**
 * 眼睛度量数据
 */
data class EyeMetrics(
    val leftEyeCenter: Offset,
    val rightEyeCenter: Offset,
    val eyeWidth: Float,
    val eyeHeight: Float,
    val scale: Float
)
