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
import com.roboparty.rpoexpression.expression.ExpressionManager
/**
 * 女款·杏眼风格渲染器
 *
 * 视觉特征：
 * - 眼型：圆润杏眼/桃花眼，眼尾柔和微垂，大而饱满
 * - 眼白：暖白色，柔和温润
 * - 瞳孔：大圆瞳孔，明亮有神，萌感强
 * - 光晕：粉紫色调，温柔梦幻
 * - 整体：温柔、灵动、可爱，适合女性角色设定
 */
class FemaleEyeRenderer : EyeStyleRenderer {

    override fun DrawScope.drawEyes(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val metrics = calculateEyeMetrics(params, centerX, centerY, size)

        // 绘制眼周粉紫光晕
        drawGlow(params, metrics, time)

        // 绘制左右眼（杏眼）
        drawSingleEye(params, metrics.leftEyeCenter, metrics, time, isLeft = true)
        drawSingleEye(params, metrics.rightEyeCenter, metrics, time, isLeft = false)
    }

    private fun DrawScope.drawGlow(params: EyeParameters, metrics: EyeMetrics, time: Float) {
        if (params.glowIntensity <= 0f) return

        // 呼吸光晕，节奏轻柔，温柔感
        val glowAlpha = if (params.glowBlink) {
            params.glowIntensity * (0.5f + 0.5f * sin(time * 7f))
        } else {
            params.glowIntensity * (0.9f + 0.1f * sin(time * 2f))
        }

        // 使用全局光晕颜色，支持热更新配置
        // Config -> State -> Shader 架构：ConfigManager 更新后，下一帧自动应用
        val glowColor = ExpressionManager.getInstance().getGlowColor()

        // 左眼光晕（大而柔和的椭圆）
        drawOval(
            color = glowColor.copy(alpha = glowAlpha * 0.3f),
            topLeft = Offset(
                metrics.leftEyeCenter.x - metrics.eyeWidth * 1.0f,
                metrics.leftEyeCenter.y - metrics.eyeHeight * 0.9f
            ),
            size = Size(metrics.eyeWidth * 2.0f, metrics.eyeHeight * 1.8f)
        )
        // 右眼光晕
        drawOval(
            color = glowColor.copy(alpha = glowAlpha * 0.3f),
            topLeft = Offset(
                metrics.rightEyeCenter.x - metrics.eyeWidth * 1.0f,
                metrics.rightEyeCenter.y - metrics.eyeHeight * 0.9f
            ),
            size = Size(metrics.eyeWidth * 2.0f, metrics.eyeHeight * 1.8f)
        )
    }

    private fun DrawScope.drawSingleEye(
        params: EyeParameters,
        eyeCenter: Offset,
        metrics: EyeMetrics,
        time: Float,
        isLeft: Boolean
    ) {
        // 杏眼：高度饱满圆润（0.95倍），与男款扁长型形成鲜明对比
        val eyeHeight = metrics.eyeHeight * 0.95f * params.eyelidOpenness
        val eyeWidth = metrics.eyeWidth * 0.98f

        // ===== 眼白（暖白色，圆润饱满） =====
        drawRoundRect(
            color = Color(0xFFFFFBF5), // 暖白，偏黄一点，柔和温润
            topLeft = Offset(eyeCenter.x - eyeWidth / 2, eyeCenter.y - eyeHeight / 2),
            size = Size(eyeWidth, eyeHeight),
            // 旧版API：左上+右上圆角，底部0圆角 实现上圆下方
            cornerRadius = CornerRadius(eyeHeight * 0.45f, 0f)
        )

        // ===== 上眼睑（柔和弧度，桃花眼感） =====
        val eyelidHeight = metrics.eyeHeight * 0.9f * (1f - params.eyelidOpenness)
        if (eyelidHeight > 0f) {
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(eyeCenter.x - eyeWidth / 2, eyeCenter.y - metrics.eyeHeight * 0.9f / 2),
                size = Size(eyeWidth, eyelidHeight + metrics.eyeHeight * 0.12f),
                // 仅顶部圆角，底部直角 完美适配眼皮闭合效果
                cornerRadius = CornerRadius(eyeHeight * 0.45f, 0f)
            )
        }

        // ===== 瞳孔（大圆瞳孔，大而明亮，萌感强） =====
        if (params.pupilSize > 0f && params.eyelidOpenness > 0.1f) {
            val pupilCenter = calculatePupilPosition(eyeCenter, eyeWidth, eyeHeight, params)
            val pupilRadius = (eyeWidth * 0.45f * params.pupilSize * params.pupilScale)
                .coerceAtMost(eyeHeight * 0.82f)

            // 大圆瞳孔主体
            drawCircle(
                color = Color(0xFF1A1A2E), // 深黑偏蓝紫，不那么死黑
                radius = pupilRadius,
                center = pupilCenter
            )

            // 瞳孔光晕（柔和梦幻）
            drawCircle(
                color = ExpressionManager.getInstance().getGlowColor().copy(alpha = params.glowIntensity * 0.5f),
                radius = pupilRadius * 1.25f,
                center = pupilCenter
            )

            // 高光（双高光，灵动可爱）
            if (params.unfocused <= 0f) {
                // 主高光
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = pupilRadius * 0.28f,
                    center = Offset(
                        pupilCenter.x - pupilRadius * 0.3f,
                        pupilCenter.y - pupilRadius * 0.35f
                    )
                )
                // 副高光（小一点，增加灵动）
                drawCircle(
                    color = Color.White.copy(alpha = 0.5f),
                    radius = pupilRadius * 0.12f,
                    center = Offset(
                        pupilCenter.x + pupilRadius * 0.25f,
                        pupilCenter.y + pupilRadius * 0.2f
                    )
                )
            } else {
                // 涣散状态
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f),
                    radius = pupilRadius * 0.45f,
                    center = pupilCenter
                )
            }
        }

        // ===== 眼眶轮廓（圆润线条，杏眼形状） =====
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(eyeCenter.x - eyeWidth / 2, eyeCenter.y - eyeHeight / 2),
            size = Size(eyeWidth, eyeHeight),
            cornerRadius = CornerRadius(eyeHeight * 0.45f, 0f),
            style = Stroke(width = 2.8f * metrics.scale)
        )

        // ===== 眼尾下眼睑柔和弧度（桃花眼特色：卧蚕感） =====
        val underEyeY = eyeCenter.y + eyeHeight * 0.45f
        val underEyeAlpha = 0.15f + 0.05f * sin(time * 1.8f)
        drawOval(
            color = ExpressionManager.getInstance().getGlowColor().copy(alpha = underEyeAlpha),
            topLeft = Offset(
                eyeCenter.x - eyeWidth * 0.35f,
                underEyeY
            ),
            size = Size(eyeWidth * 0.7f, eyeHeight * 0.15f)
        )
    }
}