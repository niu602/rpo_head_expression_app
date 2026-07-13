/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.style

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.roboparty.rpoexpression.expression.EyeParameters
import kotlin.math.sin

/**
 * 极简线条风格渲染器
 * 极简主义，只用线条勾勒，无填充，优雅简洁
 */
class MinimalEyeRenderer : EyeStyleRenderer {

    override fun DrawScope.drawEyes(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val metrics = calculateEyeMetrics(params, centerX, centerY, size)

        // 极简风格眼距适中
        val minimalMetrics = metrics.copy(
            leftEyeCenter = Offset(centerX - metrics.eyeWidth * 1.1f, centerY),
            rightEyeCenter = Offset(centerX + metrics.eyeWidth * 1.1f, centerY)
        )

        // 绘制微弱光效
        drawMinimalGlow(params, minimalMetrics, time)

        // 绘制线条眼睛
        drawLineEye(params, minimalMetrics.leftEyeCenter, minimalMetrics, time)
        drawLineEye(params, minimalMetrics.rightEyeCenter, minimalMetrics, time)
    }

    private fun DrawScope.drawMinimalGlow(params: EyeParameters, metrics: EyeMetrics, time: Float) {
        if (params.glowIntensity <= 0f) return

        val glowAlpha = if (params.glowBlink) {
            params.glowIntensity * 0.3f * (0.5f + 0.5f * sin(time * 6f))
        } else {
            params.glowIntensity * 0.2f
        }

        listOf(metrics.leftEyeCenter, metrics.rightEyeCenter).forEach { center ->
            drawCircle(
                color = params.glowColor.copy(alpha = glowAlpha),
                radius = metrics.eyeWidth * (0.8f + params.glowRadius * 0.3f),
                center = center
            )
        }
    }

    private fun DrawScope.drawLineEye(
        params: EyeParameters,
        eyeCenter: Offset,
        metrics: EyeMetrics,
        time: Float
    ) {
        val eyeHeight = metrics.eyeHeight * params.eyelidOpenness
        val eyeWidth = metrics.eyeWidth
        val strokeWidth = 2.5f * metrics.scale
        val lineColor = Color.White

        // 眼尾角度偏移
        val tailAngle = params.eyeTailAngle * 20f * metrics.scale
        val leftX = eyeCenter.x - eyeWidth/2
        val rightX = eyeCenter.x + eyeWidth/2
        val topY = eyeCenter.y - eyeHeight/2
        val bottomY = eyeCenter.y + eyeHeight/2

        // 上眼睑线条
        val upperLidPath = Path().apply {
            moveTo(leftX, eyeCenter.y)
            // 贝塞尔曲线：上眼睑弧度
            quadraticBezierTo(
                eyeCenter.x, topY - params.upperEyelidCurve * eyeHeight * 0.5f,
                rightX, eyeCenter.y - tailAngle
            )
        }
        drawPath(
            path = upperLidPath,
            color = lineColor,
            style = Stroke(width = strokeWidth)
        )

        // 下眼睑线条
        val lowerLidPath = Path().apply {
            moveTo(leftX, eyeCenter.y)
            // 贝塞尔曲线：下眼睑弧度
            quadraticBezierTo(
                eyeCenter.x, bottomY + params.lowerEyelidCurve * eyeHeight * 0.3f,
                rightX, eyeCenter.y - tailAngle
            )
        }
        drawPath(
            path = lowerLidPath,
            color = lineColor,
            style = Stroke(width = strokeWidth)
        )

        // 闭眼状态只画一条线
        if (params.eyelidOpenness < 0.1f) {
            drawLine(
                color = lineColor,
                start = Offset(leftX, eyeCenter.y),
                end = Offset(rightX, eyeCenter.y - tailAngle),
                strokeWidth = strokeWidth
            )
            return
        }

        // 瞳孔 - 极简圆点
        if (params.pupilSize > 0f && params.eyelidOpenness > 0.2f) {
            val pupilCenter = calculatePupilPosition(eyeCenter, eyeWidth, eyeHeight, params)
            val pupilRadius = eyeWidth * 0.12f * params.pupilSize * params.pupilScale

            drawCircle(
                color = lineColor,
                radius = pupilRadius,
                center = pupilCenter
            )

            // 眼神方向指示线（可选）
            if (kotlin.math.abs(params.pupilOffsetX) > 0.2f || kotlin.math.abs(params.pupilOffsetY) > 0.2f) {
                drawLine(
                    color = lineColor.copy(alpha = 0.5f),
                    start = pupilCenter,
                    end = Offset(
                        pupilCenter.x + params.pupilOffsetX * pupilRadius * 2,
                        pupilCenter.y + params.pupilOffsetY * pupilRadius * 2
                    ),
                    strokeWidth = 1f * metrics.scale
                )
            }
        }
    }
}
