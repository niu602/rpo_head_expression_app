/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.style


import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.roboparty.rpoexpression.expression.EyeParameters
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
/**
 * 男款·丹凤眼风格渲染器
 *
 * 视觉特征：
 * - 眼型：细长丹凤眼，眼尾明显上挑，棱角分明
 * - 眼白：冷白色，干净利落
 * - 瞳孔：深黑偏细长，锐利有神
 * - 光晕：冰蓝色调，冷峻科技感
 * - 整体：英气、冷峻、锐利，适合男性角色设定
 */
class MaleEyeRenderer : EyeStyleRenderer {

    override fun DrawScope.drawEyes(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val metrics = calculateEyeMetrics(params, centerX, centerY, size)

        // 绘制眼周冰蓝光晕
        drawGlow(params, metrics, time)

        // 绘制左右眼（丹凤眼）
        drawSingleEye(params, metrics.leftEyeCenter, metrics, time, isLeft = true)
        drawSingleEye(params, metrics.rightEyeCenter, metrics, time, isLeft = false)
    }

    private fun DrawScope.drawGlow(params: EyeParameters, metrics: EyeMetrics, time: Float) {
        if (params.glowIntensity <= 0f) return

        // 冰蓝色呼吸光晕，节奏偏慢，沉稳感
        val glowAlpha = if (params.glowBlink) {
            params.glowIntensity * (0.5f + 0.5f * sin(time * 6f))
        } else {
            params.glowIntensity * (0.85f + 0.15f * sin(time * 1.5f))
        }

        val glowColor = Color(0xFF4FC3F7) // 冰蓝色

        // 左眼光晕（横向椭圆，贴合丹凤眼形状）
        drawOval(
            color = glowColor.copy(alpha = glowAlpha * 0.25f),
            topLeft = Offset(
                metrics.leftEyeCenter.x - metrics.eyeWidth * 0.9f,
                metrics.leftEyeCenter.y - metrics.eyeHeight * 0.7f
            ),
            size = Size(metrics.eyeWidth * 1.8f, metrics.eyeHeight * 1.4f)
        )
        // 右眼光晕
        drawOval(
            color = glowColor.copy(alpha = glowAlpha * 0.25f),
            topLeft = Offset(
                metrics.rightEyeCenter.x - metrics.eyeWidth * 0.9f,
                metrics.rightEyeCenter.y - metrics.eyeHeight * 0.7f
            ),
            size = Size(metrics.eyeWidth * 1.8f, metrics.eyeHeight * 1.4f)
        )
    }

    /**
     * 绘制自定义四角不同圆角矩形（替代drawRoundRect多圆角报错）
     */
    private fun DrawScope.drawDiffRoundRect(
        color: Color,
        topLeft: Offset,
        size: Size,
        topLeftRad: Float,
        topRightRad: Float,
        botLeftRad: Float,
        botRightRad: Float,
        style: DrawStyle = Fill
    ) {
        val path = Path()
        val w = size.width
        val h = size.height
        val x = topLeft.x
        val y = topLeft.y

        path.moveTo(x + topLeftRad, y)
        // 上边
        path.lineTo(x + w - topRightRad, y)
        // 右上圆弧
        path.arcTo(
            rect = Rect(
                left = x + w - topRightRad * 2,
                top = y,
                right = x + w,
                bottom = y + topRightRad * 2
            ),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        // 右边
        path.lineTo(x + w, y + h - botRightRad)
        // 右下圆弧
        path.arcTo(
            rect = Rect(
                left = x + w - botRightRad * 2,
                top = y + h - botRightRad * 2,
                right = x + w,
                bottom = y + h
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        // 下边
        path.lineTo(x + botLeftRad, y + h)
        // 左下圆弧
        path.arcTo(
            rect = Rect(
                left = x,
                top = y + h - botLeftRad * 2,
                right = x + botLeftRad * 2,
                bottom = y + h
            ),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        // 左边
        path.lineTo(x, y + topLeftRad)
        // 左上圆弧
        path.arcTo(
            rect = Rect(
                left = x,
                top = y,
                right = x + topLeftRad * 2,
                bottom = y + topLeftRad * 2
            ),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        path.close()
        drawPath(path, color = color, style = style)
    }

    private fun DrawScope.drawSingleEye(
        params: EyeParameters,
        eyeCenter: Offset,
        metrics: EyeMetrics,
        time: Float,
        isLeft: Boolean
    ) {
        // 丹凤眼：高度更扁（0.6倍），细长感更强，眼尾上挑锐利
        val eyeHeight = metrics.eyeHeight * 0.60f * params.eyelidOpenness
        val eyeWidth = metrics.eyeWidth

        // 四角圆角参数：内眼角稍圆，外眼角尖锐（丹凤眼特色）
        val tlRad = if (isLeft) eyeHeight * 0.35f else eyeHeight * 0.1f
        val trRad = if (isLeft) eyeHeight * 0.1f else eyeHeight * 0.35f
        val blRad = if (isLeft) eyeHeight * 0.3f else eyeHeight * 0.05f
        val brRad = if (isLeft) eyeHeight * 0.05f else eyeHeight * 0.3f

        // ===== 眼白（冷白色，横向偏长） =====
        drawDiffRoundRect(
            color = Color(0xFFF5F5F5),
            topLeft = Offset(eyeCenter.x - eyeWidth / 2, eyeCenter.y - eyeHeight / 2),
            size = Size(eyeWidth, eyeHeight),
            topLeftRad = tlRad,
            topRightRad = trRad,
            botLeftRad = blRad,
            botRightRad = brRad
        )

        // ===== 上眼睑（丹凤眼特色：眼尾上挑） =====
        val eyelidHeight = metrics.eyeHeight * 0.75f * (1f - params.eyelidOpenness)
        if (eyelidHeight > 0f) {
            drawDiffRoundRect(
                color = Color.Black,
                topLeft = Offset(eyeCenter.x - eyeWidth / 2, eyeCenter.y - metrics.eyeHeight * 0.75f / 2),
                size = Size(eyeWidth, eyelidHeight + metrics.eyeHeight * 0.08f),
                topLeftRad = tlRad,
                topRightRad = trRad,
                botLeftRad = 0f,
                botRightRad = 0f
            )
        }

        // ===== 瞳孔（小而圆，锐利聚焦感） =====
        if (params.pupilSize > 0f && params.eyelidOpenness > 0.1f) {
            val pupilCenter = calculatePupilPosition(eyeCenter, eyeWidth, eyeHeight, params)
            val pupilSize = (eyeHeight * 0.75f * params.pupilSize * params.pupilScale)
                .coerceAtMost(eyeHeight * 0.8f)

            // 正圆小瞳孔，聚焦感强
            drawOval(
                color = Color.Black,
                topLeft = Offset(pupilCenter.x - pupilSize, pupilCenter.y - pupilSize),
                size = Size(pupilSize * 2, pupilSize * 2)
            )

            // 冰蓝色瞳孔光晕
            drawOval(
                color = Color(0xFF4FC3F7).copy(alpha = params.glowIntensity * 0.35f),
                topLeft = Offset(
                    pupilCenter.x - pupilSize * 1.4f,
                    pupilCenter.y - pupilSize * 1.4f
                ),
                size = Size(pupilSize * 2.8f, pupilSize * 2.8f)
            )

            // 高光（偏小偏圆，冷峻感）
            if (params.unfocused <= 0f) {
                drawOval(
                    color = Color.White.copy(alpha = 0.6f),
                    topLeft = Offset(
                        pupilCenter.x - pupilSize * 0.2f,
                        pupilCenter.y - pupilSize * 0.35f
                    ),
                    size = Size(pupilSize * 0.4f, pupilSize * 0.4f)
                )
            } else {
                // 涣散状态
                drawOval(
                    color = Color.White.copy(alpha = 0.2f),
                    topLeft = Offset(
                        pupilCenter.x - pupilSize * 0.35f,
                        pupilCenter.y - pupilSize * 0.25f
                    ),
                    size = Size(pupilSize * 0.7f, pupilSize * 0.5f)
                )
            }
        }

        // ===== 眼眶轮廓（硬朗线条，丹凤眼形状） =====
        drawDiffRoundRect(
            color = Color.Black,
            topLeft = Offset(eyeCenter.x - eyeWidth / 2, eyeCenter.y - eyeHeight / 2),
            size = Size(eyeWidth, eyeHeight),
            topLeftRad = tlRad,
            topRightRad = trRad,
            botLeftRad = blRad,
            botRightRad = brRad,
            style = Stroke(width = 3.5f * metrics.scale)
        )

        // ===== 眼尾上挑延长线（丹凤眼标志性特征，更长更锐利） =====
        val tailLength = eyeWidth * 0.18f
        val tailY = eyeCenter.y - eyeHeight * 0.28f
        if (isLeft) {
            // 左眼尾：向左上方延伸，角度更陡
            drawLine(
                color = Color.Black,
                start = Offset(eyeCenter.x - eyeWidth / 2, eyeCenter.y - eyeHeight * 0.3f),
                end = Offset(eyeCenter.x - eyeWidth / 2 - tailLength, tailY - eyeHeight * 0.15f),
                strokeWidth = 3.5f * metrics.scale
            )
        } else {
            // 右眼尾：向右上方延伸，角度更陡
            drawLine(
                color = Color.Black,
                start = Offset(eyeCenter.x + eyeWidth / 2, eyeCenter.y - eyeHeight * 0.3f),
                end = Offset(eyeCenter.x + eyeWidth / 2 + tailLength, tailY - eyeHeight * 0.15f),
                strokeWidth = 3.5f * metrics.scale
            )
        }
    }
}