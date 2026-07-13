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
 * 机械赛博风格渲染器
 * 科技感机械眼，棱角分明，LED光效，扫描线
 */
class CyberpunkEyeRenderer : EyeStyleRenderer {

    override fun DrawScope.drawEyes(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val metrics = calculateEyeMetrics(params, centerX, centerY, size)

        // 赛博风格眼距更宽
        val cyberMetrics = metrics.copy(
            leftEyeCenter = Offset(centerX - metrics.eyeWidth * 1.3f, centerY),
            rightEyeCenter = Offset(centerX + metrics.eyeWidth * 1.3f, centerY)
        )

        // 绘制扫描光效
        drawScanEffect(params, cyberMetrics, time)

        // 绘制机械眼眶
        drawMechanicalFrame(params, cyberMetrics, time)

        // 绘制眼球
        drawCyberEyeball(params, cyberMetrics, time)
    }

    private fun DrawScope.drawScanEffect(params: EyeParameters, metrics: EyeMetrics, time: Float) {
        if (params.glowIntensity <= 0f) return

        val glowAlpha = if (params.glowBlink) {
            params.glowIntensity * (0.3f + 0.7f * sin(time * 10f))
        } else {
            params.glowIntensity
        }

        // 六边形机械光效
        listOf(metrics.leftEyeCenter, metrics.rightEyeCenter).forEach { center ->
            // 外层六边形光晕
            val hexPath = createHexagonPath(center, metrics.eyeWidth * (1.2f + params.glowRadius * 0.5f))
            drawPath(
                path = hexPath,
                color = params.glowColor.copy(alpha = glowAlpha * 0.2f),
                style = Stroke(width = 2f * metrics.scale)
            )

            // 扫描线
            val scanY = center.y + (sin(time * 3f) * metrics.eyeHeight)
            drawLine(
                color = params.glowColor.copy(alpha = glowAlpha * 0.8f),
                start = Offset(center.x - metrics.eyeWidth, scanY),
                end = Offset(center.x + metrics.eyeWidth, scanY),
                strokeWidth = 2f * metrics.scale
            )
        }
    }

    private fun DrawScope.drawMechanicalFrame(params: EyeParameters, metrics: EyeMetrics, time: Float) {
        val frameColor = Color(0xFF424242)
        val accentColor = params.glowColor

        listOf(metrics.leftEyeCenter, metrics.rightEyeCenter).forEach { center ->
            val eyeHeight = metrics.eyeHeight * params.eyelidOpenness

            // 机械外框 - 切角矩形
            val framePath = Path().apply {
                val w = metrics.eyeWidth * 1.1f
                val h = eyeHeight * 1.1f
                val cornerCut = 20f * metrics.scale

                moveTo(center.x - w + cornerCut, center.y - h)
                lineTo(center.x + w - cornerCut, center.y - h)
                lineTo(center.x + w, center.y - h + cornerCut)
                lineTo(center.x + w, center.y + h - cornerCut)
                lineTo(center.x + w - cornerCut, center.y + h)
                lineTo(center.x - w + cornerCut, center.y + h)
                lineTo(center.x - w, center.y + h - cornerCut)
                lineTo(center.x - w, center.y - h + cornerCut)
                close()
            }

            drawPath(
                path = framePath,
                color = frameColor,
                style = Stroke(width = 4f * metrics.scale)
            )

            // 螺丝装饰
            drawCircle(
                color = accentColor.copy(alpha = 0.6f),
                radius = 4f * metrics.scale,
                center = Offset(center.x - metrics.eyeWidth, center.y - eyeHeight * 0.8f)
            )
            drawCircle(
                color = accentColor.copy(alpha = 0.6f),
                radius = 4f * metrics.scale,
                center = Offset(center.x + metrics.eyeWidth, center.y - eyeHeight * 0.8f)
            )
        }
    }

    private fun DrawScope.drawCyberEyeball(params: EyeParameters, metrics: EyeMetrics, time: Float) {
        listOf(metrics.leftEyeCenter, metrics.rightEyeCenter).forEach { center ->
            val eyeHeight = metrics.eyeHeight * params.eyelidOpenness

            if (params.eyelidOpenness < 0.1f) {
                // 休眠/闭眼：机械快门闭合效果
                drawLine(
                    color = params.glowColor.copy(alpha = 0.3f),
                    start = Offset(center.x - metrics.eyeWidth, center.y),
                    end = Offset(center.x + metrics.eyeWidth, center.y),
                    strokeWidth = 3f * metrics.scale
                )
                return@forEach
            }

            // 眼球背景 - 深色
            drawRect(
                color = Color.Black,
                topLeft = Offset(center.x - metrics.eyeWidth * 0.9f, center.y - eyeHeight * 0.9f),
                size = Size(metrics.eyeWidth * 1.8f, eyeHeight * 1.8f)
            )

            // 机械瞳孔 - 菱形/六边形
            if (params.pupilSize > 0f) {
                val pupilCenter = calculatePupilPosition(center, metrics.eyeWidth, eyeHeight, params)
                val pupilSize = metrics.eyeWidth * 0.5f * params.pupilSize * params.pupilScale

                // 瞳孔发光
                drawCircle(
                    color = params.glowColor.copy(alpha = params.glowIntensity * 0.8f),
                    radius = pupilSize,
                    center = pupilCenter
                )

                // 机械瞳孔十字准星
                drawLine(
                    color = Color.Black,
                    start = Offset(pupilCenter.x - pupilSize, pupilCenter.y),
                    end = Offset(pupilCenter.x + pupilSize, pupilCenter.y),
                    strokeWidth = 2f * metrics.scale
                )
                drawLine(
                    color = Color.Black,
                    start = Offset(pupilCenter.x, pupilCenter.y - pupilSize),
                    end = Offset(pupilCenter.x, pupilCenter.y + pupilSize),
                    strokeWidth = 2f * metrics.scale
                )

                // 数据流动效果
                if (params.unfocused>0) {
                    val dataOffset = (time * 50f) % pupilSize.toDouble()
                    for (i in 0..2) {
                        val lineX = pupilCenter.x - pupilSize + dataOffset.toFloat() + i * 15f * metrics.scale
                        if (lineX < pupilCenter.x + pupilSize) {
                            drawLine(
                                color = Color.Black.copy(alpha = 0.5f),
                                start = Offset(lineX, pupilCenter.y - pupilSize * 0.5f),
                                end = Offset(lineX, pupilCenter.y + pupilSize * 0.5f),
                                strokeWidth = 1f * metrics.scale
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createHexagonPath(center: Offset, radius: Float): Path {
        return Path().apply {
            for (i in 0..5) {
                val angle = Math.PI / 3 * i - Math.PI / 2
                val x = center.x + radius * Math.cos(angle).toFloat()
                val y = center.y + radius * Math.sin(angle).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
}
