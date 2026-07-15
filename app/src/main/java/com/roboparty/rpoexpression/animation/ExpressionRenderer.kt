/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.roboparty.rpoexpression.animation.style.EyeMetrics
import com.roboparty.rpoexpression.animation.style.EyeStyleFactory
import com.roboparty.rpoexpression.expression.EyeEffectType
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionState
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.sensor.AmbientLightManager
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random
import androidx.compose.ui.geometry.toRect
/**
 * 纯眼部表情渲染器
 * 严格限定在眼部区域绘制，通过参数化控制所有视觉效果
 * 支持多风格动态切换，支持强度无级调节
 *
 * 性能优化：所有可复用对象缓存为成员变量，避免每帧分配导致 GC 卡顿
 */
class ExpressionRenderer {
    // ========== 缓存对象，避免每帧分配 ==========
    private val colorMatrix = ColorMatrix()
    private val filterPaint = androidx.compose.ui.graphics.Paint()
    private val tempPath = Path()

    // 上一帧尺寸缓存，尺寸变化时才重新计算
    private var lastSize: Size = Size.Zero
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    /**
     * 主渲染方法：参数化绘制纯眼部表情
     */
    fun DrawScope.render(state: ExpressionState, size: Size, lightConfig: AmbientLightManager.LightConfig) {
        // 绘制纯黑背景
        drawRect(color = Color.Black, size = size)

        // 尺寸变化时更新中心缓存
        if (size != lastSize) {
            lastSize = size
            lastCenterX = size.width / 2f
            lastCenterY = size.height / 2f
        }
        val centerX = lastCenterX
        val centerY = lastCenterY
        val time = System.currentTimeMillis() / 1000f

        // 更新色温颜色矩阵（复用已有对象，不新建）
        updateColorMatrix(lightConfig)
        filterPaint.colorFilter = ColorFilter.colorMatrix(colorMatrix)

        // 应用微动画：呼吸缩放
        val microAnim = MicroAnimationLayer.getInstance().apply(state.type)

        drawIntoCanvas { canvas ->
            canvas.save()

            // 应用环境光颜色滤镜（亮度+色温）
            drawContext.canvas.saveLayer(bounds = size.toRect(), paint = filterPaint)

            // 应用呼吸缩放
            scale(microAnim.scale, microAnim.scale, pivot = Offset(centerX, centerY)) {
                // 应用头部Y轴微动
                translate(top = microAnim.offsetY * size.height * 0.05f) {
                    // 获取当前风格渲染器
                    val styleRenderer = EyeStyleFactory.getRenderer(state.style)

                    // 获取应用了强度和过渡的眼部参数
                    val eyeParams = state.getEyeParameters()
                    // 计算眼睛位置度量
                    val eyeMetrics = with(styleRenderer) {
                        calculateEyeMetrics(eyeParams, centerX, centerY, size)
                    }
                    // 计算左右瞳孔位置
                    val leftPupil = with(styleRenderer) {
                        calculatePupilPosition(eyeMetrics.leftEyeCenter, eyeMetrics.eyeWidth, eyeMetrics.eyeHeight, eyeParams)
                    }
                    val rightPupil = with(styleRenderer) {
                        calculatePupilPosition(eyeMetrics.rightEyeCenter, eyeMetrics.eyeWidth, eyeMetrics.eyeHeight, eyeParams)
                    }
                    val pupilRadius = eyeMetrics.eyeWidth * 0.18f // 瞳孔半径

                    // 绘制眼睛
                    with(styleRenderer) {
                        drawEyes(eyeParams, centerX, centerY, size, time)
                    }

                    // 绘制眼内特效（严格限制在瞳孔/眼周区域）
                    drawEyeEffects(leftPupil, rightPupil, pupilRadius, eyeMetrics.scale, time, lightConfig.highlight)

                    // 绘制系统状态特殊叠加效果（复用 tempPath）
                    drawSystemStateOverlays(state, centerX, centerY, size, time)
                }
            }

            // 恢复环境光颜色滤镜
            drawContext.canvas.restore()
            canvas.restore()
        }
    }

    /**
     * 更新颜色矩阵，复用对象避免每帧新建
     */
    private fun updateColorMatrix(lightConfig: AmbientLightManager.LightConfig) {
        val (rCoeff, gCoeff, bCoeff) = when {
            lightConfig.isNightMode -> Triple(1.1f, 1.0f, 0.7f)
            lightConfig.colorTemp < 4000 -> Triple(1.08f, 1.02f, 0.85f)
            lightConfig.colorTemp > 6000 -> Triple(0.9f, 0.98f, 1.1f)
            else -> Triple(1.0f, 1.0f, 1.0f)
        }
        val brightness = lightConfig.brightness
        val values = colorMatrix.values
        values[0] = rCoeff * brightness
        values[6] = gCoeff * brightness
        values[12] = bCoeff * brightness
        values[18] = 1f
    }

    /**
     * 绘制眼内特效（严格限制在瞳孔和眼周区域，不超出眼睛范围）
     */
    private fun DrawScope.drawEyeEffects(
        leftPupil: Offset,
        rightPupil: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        val effect = ExpressionManager.getInstance().currentEffect.value
        if (effect == EyeEffectType.NONE) return

        // 对左右眼分别绘制特效
        listOf(leftPupil, rightPupil).forEach { pupilCenter ->
            when (effect) {
                EyeEffectType.THINKING_LOADING -> {
                    // 思考：旋转加载线，2条旋转圆弧
                    val rotateAngle = time * 2f // 旋转速度
                    val arcRadius = pupilRadius * 1.3f
                    val strokeWidth = 2f * scale

                    // 两条旋转的半圆弧
                    for (i in 0..1) {
                        val startAngle = Math.toRadians(((rotateAngle + i * 180f) % 360f).toDouble())
                        val sweepAngle = Math.toRadians(60.0)
                        drawArc(
                            color = Color.White.copy(alpha = 0.6f * highlight),
                            startAngle = Math.toDegrees(startAngle).toFloat(),
                            sweepAngle = Math.toDegrees(sweepAngle).toFloat(),
                            useCenter = false,
                            topLeft = Offset(pupilCenter.x - arcRadius, pupilCenter.y - arcRadius),
                            size = Size(arcRadius * 2, arcRadius * 2),
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }

                EyeEffectType.RECOGNIZE_SUCCESS -> {
                    // 识别成功：星芒高光，4条放射状光线，闪烁1秒
                    val progress = (time % 1.5f) / 1.5f
                    if (progress > 0.8f) return@forEach // 只显示前0.8秒
                    val alpha = (1f - progress / 0.8f) * 0.8f * highlight
                    val rayLength = pupilRadius * (1f + progress * 0.5f)
                    val strokeWidth = 1.5f * scale

                    // 4条星芒射线
                    for (i in 0..3) {
                        val angle = i * Math.PI / 2f
                        val endX = pupilCenter.x + cos(angle).toFloat() * rayLength
                        val endY = pupilCenter.y + sin(angle).toFloat() * rayLength
                        drawLine(
                            color = Color.White.copy(alpha = alpha),
                            start = pupilCenter,
                            end = Offset(endX, endY),
                            strokeWidth = strokeWidth
                        )
                    }
                    // 中心亮点
                    drawCircle(
                        color = Color.White.copy(alpha = alpha * 0.7f),
                        radius = pupilRadius * 0.3f * (1f - progress),
                        center = pupilCenter
                    )
                }

                EyeEffectType.HAPPY_STARLIGHT -> {
                    // 开心：细碎星光，随机小光点闪烁
                    val starCount = 6
                    val starSeed = (time * 2f).toInt()
                    val random = Random(starSeed) // 固定种子，避免每帧乱跳
                    repeat(starCount) {
                        val angle = random.nextFloat() * 2f * Math.PI.toFloat()
                        val distance = random.nextFloat() * pupilRadius * 1.2f
                        val starX = pupilCenter.x + cos(angle) * distance
                        val starY = pupilCenter.y + sin(angle) * distance
                        val blink = (sin(time * 3f + it) * 0.5f + 0.5f)
                        val starRadius = (1f + random.nextFloat() * 1.5f) * scale
                        drawCircle(
                            color = Color.White.copy(alpha = blink * 0.7f * highlight),
                            radius = starRadius,
                            center = Offset(starX, starY)
                        )
                    }
                }

                EyeEffectType.ERROR_PULSE -> {
                    // 报错：红色脉冲光，向外扩散圆环
                    val pulseProgress = (time % 1f)
                    val pulseRadius = pupilRadius * (1f + pulseProgress * 1f)
                    val alpha = (1f - pulseProgress) * 0.5f * highlight
                    val strokeWidth = 2f * scale * (1f - pulseProgress)

                    drawCircle(
                        color = Color(0xFFFF5252).copy(alpha = alpha),
                        radius = pulseRadius,
                        center = pupilCenter,
                        style = Stroke(width = strokeWidth)
                    )
                    // 中心红色微光
                    drawCircle(
                        color = Color(0xFFFF5252).copy(alpha = 0.3f * highlight),
                        radius = pupilRadius * 0.8f,
                        center = pupilCenter
                    )
                }

                else -> {}
            }
        }
    }

    /**
     * 绘制系统状态特殊叠加效果
     * 严格不涉及下半脸元素
     * 性能优化：复用 tempPath，避免每帧 new Path
     */
    private fun DrawScope.drawSystemStateOverlays(
        state: ExpressionState,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val scale = min(size.width, size.height) / 1080f

        when (state.type) {
            ExpressionType.THINKING -> {
                // 思考状态：三个跳动省略号（眼部下方）
                val dotRadius = 8f * scale
                val dotSpacing = 30f * scale
                val dotY = centerY + size.height * 0.25f
                for (i in 0..2) {
                    val offset = sin(time * 3f + i * 0.8f) * 6f * scale
                    drawCircle(
                        color = Color(0xFFFFD700).copy(alpha = 0.7f + 0.3f * sin(time * 3f + i * 0.8f)),
                        radius = dotRadius,
                        center = Offset(centerX + (i - 1) * dotSpacing, dotY + offset)
                    )
                }
            }

            ExpressionType.RECOGNIZING -> {
                // 识别中状态：眼部区域扫描框
                val boxWidth = size.width * 0.5f
                val boxHeight = size.height * 0.4f
                val boxLeft = centerX - boxWidth/2
                val boxTop = centerY - boxHeight/2
                val scanOffset = (time % 1.5f) / 1.5f * boxHeight
                val cornerLength = 25f * scale
                val strokeWidth = 2f * scale
                val boxColor = Color(0xFF7C4DFF).copy(alpha = 0.8f)

                // 四角（直接画线，不创建对象）
                drawLine(boxColor, Offset(boxLeft, boxTop), Offset(boxLeft + cornerLength, boxTop), strokeWidth = strokeWidth)
                drawLine(boxColor, Offset(boxLeft, boxTop), Offset(boxLeft, boxTop + cornerLength), strokeWidth = strokeWidth)
                drawLine(boxColor, Offset(boxLeft + boxWidth, boxTop), Offset(boxLeft + boxWidth - cornerLength, boxTop), strokeWidth = strokeWidth)
                drawLine(boxColor, Offset(boxLeft + boxWidth, boxTop), Offset(boxLeft + boxWidth, boxTop + cornerLength), strokeWidth = strokeWidth)
                drawLine(boxColor, Offset(boxLeft, boxTop + boxHeight), Offset(boxLeft + cornerLength, boxTop + boxHeight), strokeWidth = strokeWidth)
                drawLine(boxColor, Offset(boxLeft, boxTop + boxHeight), Offset(boxLeft, boxTop + boxHeight - cornerLength), strokeWidth = strokeWidth)
                drawLine(boxColor, Offset(boxLeft + boxWidth, boxTop + boxHeight), Offset(boxLeft + boxWidth - cornerLength, boxTop + boxHeight), strokeWidth = strokeWidth)
                drawLine(boxColor, Offset(boxLeft + boxWidth, boxTop + boxHeight), Offset(boxLeft + boxWidth, boxTop + boxHeight - cornerLength), strokeWidth = strokeWidth)

                // 扫描线
                drawLine(
                    Color(0xFF7C4DFF).copy(alpha = 0.6f),
                    Offset(boxLeft, boxTop + scanOffset),
                    Offset(boxLeft + boxWidth, boxTop + scanOffset),
                    strokeWidth = 1.5f * scale
                )
            }

            ExpressionType.RESPONSE -> {
                // 应答状态：对勾动画（眼部下方）- 复用 tempPath
                val progress = state.transitionProgress.coerceIn(0f, 1f)
                val checkSize = 50f * scale
                val checkY = centerY + size.height * 0.22f
                tempPath.rewind()
                tempPath.moveTo(centerX - checkSize/2, checkY)
                tempPath.lineTo(centerX - checkSize/6, checkY + checkSize/2)
                tempPath.lineTo(centerX + checkSize/2, checkY - checkSize/3)
                drawPath(
                    path = tempPath,
                    color = Color(0xFFFFD700).copy(alpha = progress),
                    style = Stroke(width = 5f * scale)
                )
            }

            ExpressionType.CHARGING -> {
                // 充电状态：闪电图标 - 复用 tempPath
                val boltSize = 30f * scale
                val boltY = centerY - size.height * 0.25f
                tempPath.rewind()
                tempPath.moveTo(centerX, boltY - boltSize)
                tempPath.lineTo(centerX - boltSize/2, boltY)
                tempPath.lineTo(centerX, boltY - boltSize/4)
                tempPath.lineTo(centerX, boltY + boltSize)
                tempPath.lineTo(centerX + boltSize/2, boltY - boltSize/4)
                tempPath.lineTo(centerX, boltY)
                tempPath.close()
                drawPath(
                    path = tempPath,
                    color = Color(0xFF66BB6A).copy(alpha = 0.5f + 0.5f * sin(time * 2f)),
                    style = Stroke(width = 3f * scale)
                )
            }

            ExpressionType.UPDATING -> {
                // 升级状态：进度条
                val barWidth = size.width * 0.4f
                val barHeight = 8f * scale
                val barY = centerY + size.height * 0.25f
                val progress = ((time * 0.2f) % 1f)

                // 背景条
                drawRect(
                    color = Color(0xFF2196F3).copy(alpha = 0.3f),
                    topLeft = Offset(centerX - barWidth/2, barY),
                    size = Size(barWidth, barHeight)
                )
                // 进度条
                drawRect(
                    color = Color(0xFF2196F3),
                    topLeft = Offset(centerX - barWidth/2, barY),
                    size = Size(barWidth * progress, barHeight)
                )
            }

            ExpressionType.ALERT -> {
                // 告警状态：感叹号
                val alertSize = 25f * scale
                val alertY = centerY - size.height * 0.25f
                val flashAlpha = if ((time * 5f).toInt() % 2 == 0) 0.9f else 0.3f

                drawCircle(
                    color = Color(0xFFE53935).copy(alpha = flashAlpha),
                    radius = alertSize,
                    center = Offset(centerX, alertY),
                    style = Stroke(width = 3f * scale)
                )
                drawLine(
                    color = Color(0xFFE53935).copy(alpha = flashAlpha),
                    start = Offset(centerX, alertY - alertSize/2),
                    end = Offset(centerX, alertY + alertSize/3),
                    strokeWidth = 4f * scale
                )
                drawCircle(
                    color = Color(0xFFE53935).copy(alpha = flashAlpha),
                    radius = 4f * scale,
                    center = Offset(centerX, alertY + alertSize * 0.6f)
                )
            }

            else -> {}
        }
    }
}
