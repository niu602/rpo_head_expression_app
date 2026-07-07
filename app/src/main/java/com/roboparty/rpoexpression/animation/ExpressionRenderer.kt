/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.roboparty.rpoexpression.expression.ExpressionState
import com.roboparty.rpoexpression.expression.ExpressionType
import kotlin.math.cos
import kotlin.math.sin

/**
 * 表情渲染器
 * 使用Jetpack Compose Canvas进行自定义绘制，保证60fps流畅动画
 *
 * 设计原则：
 * 1. 纯函数式绘制，无状态依赖
 * 2. 基于进度参数驱动所有动画
 * 3. 使用硬件加速绘制路径
 */
class ExpressionRenderer {

    // 主题颜色 - 机器人蓝色眼睛
    private val eyeColor = Color(0xFF00D4FF)
    private val eyeGlowColor = Color(0x8000D4FF)
    private val backgroundColor = Color.Black
    private val mouthColor = Color(0xFF00D4FF)

    // 缓存绘制路径，减少GC
    private val eyePath = Path()
    private val mouthPath = Path()

    /**
     * 主绘制入口
     * @param state 当前表情状态
     * @param size 画布尺寸
     */
    fun DrawScope.render(state: ExpressionState, size: Size) {
        // 绘制黑色背景
        drawRect(color = backgroundColor, size = size)

        // 计算中心位置和缩放比例
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val scale = size.width / 1080f  // 基于1080p设计稿缩放

        // 根据表情类型绘制对应内容
        when (state.type) {
            ExpressionType.IDLE -> renderIdle(centerX, centerY, scale, state.progress)
            ExpressionType.BLINK -> renderBlink(centerX, centerY, scale, state.progress)
            ExpressionType.SPEAKING -> renderSpeaking(centerX, centerY, scale, state.progress)
            ExpressionType.THINKING -> renderThinking(centerX, centerY, scale, state.progress)
            ExpressionType.RECOGNIZING -> renderRecognizing(centerX, centerY, scale, state.progress)
            ExpressionType.RESPONSE -> renderResponse(centerX, centerY, scale, state.progress)
        }

        // 应用过渡效果
        applyTransition(state.transitionProgress)
    }

    /**
     * 绘制待机表情 - 轻微呼吸微动
     */
    private fun DrawScope.renderIdle(cx: Float, cy: Float, scale: Float, progress: Float) {
        // 呼吸效果：眼睛大小轻微变化
        val breathScale = 1f + 0.02f * sin(progress * 2 * Math.PI).toFloat()
        val eyeOffsetY = 5f * sin(progress * 2 * Math.PI).toFloat() * scale

        // 绘制双眼
        drawEye(cx - 180f * scale, cy + eyeOffsetY, 80f * scale * breathScale)
        drawEye(cx + 180f * scale, cy + eyeOffsetY, 80f * scale * breathScale)

        // 绘制微笑嘴巴
        drawSmileMouth(cx, cy + 150f * scale, 100f * scale, 0.3f)
    }

    /**
     * 绘制眨眼表情 - 眼睛快速闭合再睁开
     */
    private fun DrawScope.renderBlink(cx: Float, cy: Float, scale: Float, progress: Float) {
        // 眨眼进度：0~0.5闭合，0.5~1睁开
        val eyeHeight = if (progress < 0.5f) {
            80f * scale * (1f - progress * 2)
        } else {
            80f * scale * ((progress - 0.5f) * 2)
        }

        // 绘制闭合/半睁的眼睛
        drawEyeSquint(cx - 180f * scale, cy, 80f * scale, eyeHeight)
        drawEyeSquint(cx + 180f * scale, cy, 80f * scale, eyeHeight)

        // 保持微笑
        drawSmileMouth(cx, cy + 150f * scale, 100f * scale, 0.3f)
    }

    /**
     * 绘制说话表情 - 嘴巴张合动画
     */
    private fun DrawScope.renderSpeaking(cx: Float, cy: Float, scale: Float, progress: Float) {
        // 眼睛保持睁开
        drawEye(cx - 180f * scale, cy, 80f * scale)
        drawEye(cx + 180f * scale, cy, 80f * scale)

        // 嘴巴张合：模拟说话口型
        val mouthOpen = (0.5f + 0.5f * sin(progress * 8 * Math.PI)).toFloat()
        drawTalkingMouth(cx, cy + 150f * scale, 80f * scale, mouthOpen)
    }

    /**
     * 绘制思考表情 - 眼睛向上看，旋转光圈
     */
    private fun DrawScope.renderThinking(cx: Float, cy: Float, scale: Float, progress: Float) {
        // 眼睛向上偏移
        val lookUp = -30f * scale
        drawEye(cx - 180f * scale, cy + lookUp, 60f * scale)
        drawEye(cx + 180f * scale, cy + lookUp, 60f * scale)

        // 思考中的省略号动画
        val dotOffset = (progress * 3).toInt()
        for (i in 0..2) {
            val alpha = if (i <= dotOffset % 3) 1f else 0.3f
            drawCircle(
                color = eyeColor.copy(alpha = alpha),
                radius = 15f * scale,
                center = Offset(cx + (i - 1) * 50f * scale, cy + 250f * scale)
            )
        }

        // 思考时嘴巴微抿
        drawLine(
            color = mouthColor,
            start = Offset(cx - 50f * scale, cy + 150f * scale),
            end = Offset(cx + 50f * scale, cy + 150f * scale),
            strokeWidth = 8f * scale
        )
    }

    /**
     * 绘制识别中表情 - 扫描动画
     */
    private fun DrawScope.renderRecognizing(cx: Float, cy: Float, scale: Float, progress: Float) {
        // 绘制矩形扫描框
        val boxSize = 300f * scale
        val scanY = cy - boxSize/2 + progress * boxSize

        drawRoundRect(
            color = eyeColor,
            topLeft = Offset(cx - boxSize/2, cy - boxSize/2),
            size = Size(boxSize, boxSize),
            cornerRadius = CornerRadius(20f * scale),
            style = Stroke(width = 6f * scale)
        )

        // 扫描线
        drawLine(
            color = eyeColor,
            start = Offset(cx - boxSize/2, scanY),
            end = Offset(cx + boxSize/2, scanY),
            strokeWidth = 4f * scale,
            blendMode = BlendMode.Plus
        )

        // 扫描线发光效果
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, eyeGlowColor, Color.Transparent),
                startY = scanY - 30f * scale,
                endY = scanY + 30f * scale
            ),
            topLeft = Offset(cx - boxSize/2, scanY - 30f * scale),
            size = Size(boxSize, 60f * scale)
        )

        // 眼睛在扫描框两侧
        drawEye(cx - 250f * scale, cy, 50f * scale)
        drawEye(cx + 250f * scale, cy, 50f * scale)
    }

    /**
     * 绘制应答反馈表情 - 开心眨眼+对勾
     */
    private fun DrawScope.renderResponse(cx: Float, cy: Float, scale: Float, progress: Float) {
        // 开心弯眼
        drawHappyEye(cx - 180f * scale, cy, 80f * scale)
        drawHappyEye(cx + 180f * scale, cy, 80f * scale)

        // 大笑嘴巴
        drawSmileMouth(cx, cy + 150f * scale, 120f * scale, 0.6f)

        // 成功对勾动画（进度完成后显示）
        if (progress > 0.3f) {
            val checkProgress = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
            drawCheckMark(cx, cy - 200f * scale, 60f * scale, checkProgress)
        }
    }

    // region 绘制辅助方法
    /**
     * 绘制圆形眼睛
     */
    private fun DrawScope.drawEye(x: Float, y: Float, radius: Float) {
        // 外发光
        drawCircle(
            color = eyeGlowColor,
            radius = radius * 1.5f,
            center = Offset(x, y),
            blendMode = BlendMode.Plus
        )
        // 主体
        drawCircle(
            color = eyeColor,
            radius = radius,
            center = Offset(x, y)
        )
        // 高光
        drawCircle(
            color = Color.White.copy(alpha = 0.8f),
            radius = radius * 0.2f,
            center = Offset(x - radius * 0.3f, y - radius * 0.3f)
        )
    }

    /**
     * 绘制眯眼（眨眼用）
     */
    private fun DrawScope.drawEyeSquint(x: Float, y: Float, width: Float, height: Float) {
        if (height < 2f) {
            // 完全闭合，画一条线
            drawLine(
                color = eyeColor,
                start = Offset(x - width, y),
                end = Offset(x + width, y),
                strokeWidth = 8f
            )
        } else {
            drawOval(
                color = eyeColor,
                topLeft = Offset(x - width, y - height),
                size = Size(width * 2, height * 2)
            )
        }
    }

    /**
     * 绘制开心弯眼（^_^）
     */
    private fun DrawScope.drawHappyEye(x: Float, y: Float, radius: Float) {
        eyePath.reset()
        eyePath.moveTo(x - radius, y)
        eyePath.quadraticBezierTo(x, y - radius, x + radius, y)
        drawPath(
            path = eyePath,
            color = eyeColor,
            style = Stroke(width = 10f, cap = StrokeCap.Round)
        )
    }

    /**
     * 绘制微笑嘴巴
     */
    private fun DrawScope.drawSmileMouth(x: Float, y: Float, width: Float, curve: Float) {
        mouthPath.reset()
        mouthPath.moveTo(x - width, y)
        mouthPath.quadraticBezierTo(x, y + width * curve * 2, x + width, y)
        drawPath(
            path = mouthPath,
            color = mouthColor,
            style = Stroke(width = 8f, cap = StrokeCap.Round)
        )
    }

    /**
     * 绘制说话时的嘴巴
     */
    private fun DrawScope.drawTalkingMouth(x: Float, y: Float, width: Float, openAmount: Float) {
        val height = width * openAmount
        drawOval(
            color = mouthColor,
            topLeft = Offset(x - width/2, y - height/2),
            size = Size(width, height)
        )
    }

    /**
     * 绘制对勾动画
     */
    private fun DrawScope.drawCheckMark(x: Float, y: Float, size: Float, progress: Float) {
        val path = Path()
        val p1 = Offset(x - size * 0.5f, y)
        val p2 = Offset(x - size * 0.1f, y + size * 0.5f)
        val p3 = Offset(x + size * 0.6f, y - size * 0.4f)

        val firstPartEnd = if (progress < 0.5f) {
            val t = progress * 2
            Offset(
                p1.x + (p2.x - p1.x) * t,
                p1.y + (p2.y - p1.y) * t
            )
        } else p2

        path.moveTo(p1.x, p1.y)
        path.lineTo(firstPartEnd.x, firstPartEnd.y)

        if (progress >= 0.5f) {
            val t = (progress - 0.5f) * 2
            path.lineTo(
                p2.x + (p3.x - p2.x) * t,
                p2.y + (p3.y - p2.y) * t
            )
        }

        drawPath(
            path = path,
            color = Color(0xFF00FF88),
            style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    /**
     * 应用过渡透明度
     */
    private fun DrawScope.applyTransition(progress: Float) {
        // 过渡期间可以添加淡入淡出效果，这里简单处理
        // 实际可以通过canvas.saveLayer实现更复杂的过渡
    }
    // endregion
}
