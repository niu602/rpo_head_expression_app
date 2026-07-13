/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.style

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.roboparty.rpoexpression.expression.EyeParameters
import kotlin.math.sin

/**
 * 卡通圆眼风格渲染器
 * 圆润可爱的卡通风格，大眼睛，柔和高光
 */
class CartoonEyeRenderer : EyeStyleRenderer {

    override fun DrawScope.drawEyes(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val metrics = calculateEyeMetrics(params, centerX, centerY, size)

        // 绘制眼周光晕
        drawGlow(params, metrics, time)

        // 绘制左右眼
        drawSingleEye(params, metrics.leftEyeCenter, metrics, time)
        drawSingleEye(params, metrics.rightEyeCenter, metrics, time)
    }

    private fun DrawScope.drawGlow(params: EyeParameters, metrics: EyeMetrics, time: Float) {
        if (params.glowIntensity <= 0f) return

        val glowAlpha = if (params.glowBlink) {
            params.glowIntensity * (0.5f + 0.5f * sin(time * 8f))
        } else {
            params.glowIntensity * (0.9f + 0.1f * sin(time * 2f))
        }

        // 左眼光晕
        drawCircle(
            color = params.glowColor.copy(alpha = glowAlpha * 0.3f),
            radius = metrics.eyeWidth * (1f + params.glowRadius),
            center = metrics.leftEyeCenter
        )
        // 右眼光晕
        drawCircle(
            color = params.glowColor.copy(alpha = glowAlpha * 0.3f),
            radius = metrics.eyeWidth * (1f + params.glowRadius),
            center = metrics.rightEyeCenter
        )
    }

    private fun DrawScope.drawSingleEye(
        params: EyeParameters,
        eyeCenter: Offset,
        metrics: EyeMetrics,
        time: Float
    ) {
        val eyeHeight = metrics.eyeHeight * params.eyelidOpenness
        val eyeWidth = metrics.eyeWidth

        // 眼白
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(eyeCenter.x - eyeWidth/2, eyeCenter.y - eyeHeight/2),
            size = Size(eyeWidth, eyeHeight),
            cornerRadius = CornerRadius(eyeWidth/2, eyeHeight/2)
        )

        // 上眼睑
        val eyelidHeight = metrics.eyeHeight * (1f - params.eyelidOpenness)
        if (eyelidHeight > 0f) {
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(eyeCenter.x - eyeWidth/2, eyeCenter.y - metrics.eyeHeight/2),
                size = Size(eyeWidth, eyelidHeight + metrics.eyeHeight * 0.1f),
                cornerRadius = CornerRadius(eyeWidth/2, eyeHeight/2)
            )
        }

        // 瞳孔
        if (params.pupilSize > 0f && params.eyelidOpenness > 0.1f) {
            val pupilCenter = calculatePupilPosition(eyeCenter, eyeWidth, eyeHeight, params)
            val pupilRadius = (eyeWidth * 0.4f * params.pupilSize * params.pupilScale)
                .coerceAtMost(eyeHeight * 0.8f)

            // 瞳孔主体
            drawCircle(
                color = Color.Black,
                radius = pupilRadius,
                center = pupilCenter
            )

            // 瞳孔颜色光晕
            drawCircle(
                color = params.glowColor.copy(alpha = params.glowIntensity * 0.5f),
                radius = pupilRadius * 1.2f,
                center = pupilCenter
            )

            // 高光（聚焦时显示清晰高光）
            if (params.unfocused <= 0f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = pupilRadius * 0.25f,
                    center = Offset(pupilCenter.x - pupilRadius * 0.3f, pupilCenter.y - pupilRadius * 0.3f)
                )
            } else {
                // 涣散时显示模糊效果
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f),
                    radius = pupilRadius * 0.4f,
                    center = pupilCenter
                )
            }
        }

        // 眼眶轮廓
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(eyeCenter.x - eyeWidth/2, eyeCenter.y - eyeHeight/2),
            size = Size(eyeWidth, eyeHeight),
            cornerRadius = CornerRadius(eyeWidth/2, eyeHeight/2),
            style = Stroke(width = 3f * metrics.scale)
        )
    }
}
