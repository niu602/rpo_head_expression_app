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
import com.roboparty.rpoexpression.animation.shape.ShapeExpressionRenderer
import com.roboparty.rpoexpression.expression.ExpressionMode
import com.roboparty.rpoexpression.expression.ExpressionState
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.expression.EyeEffectType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.compose.ui.graphics.drawscope.Fill
import com.roboparty.rpoexpression.sensor.AmbientLightManager
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.StrokeCap
import com.roboparty.rpoexpression.expression.ExpressionManager

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

    // ========== 能量模式渲染器 ==========
    private val energyRenderer = RorschachEnergyRenderer()

    // 当前渲染模式缓存（每帧从 ExpressionManager 同步）
    private var currentMode: ExpressionMode = ExpressionMode.TRADITIONAL

    /**
     * 主渲染方法：根据当前模式分发到传统眼部渲染或能量场渲染
     */
    fun DrawScope.render(state: ExpressionState, size: Size, lightConfig: AmbientLightManager.LightConfig, effect: EyeEffectType) {
        // 同步当前渲染模式
        currentMode = ExpressionManager.getInstance().getCurrentMode()
        // 绘制纯黑背景
        drawRect(color = Color.Black, size = size)

        // 计算中心点：能量模式偏下，传统模式居中
        // 每帧直接计算，避免模式切换时缓存状态错误
        val centerX = size.width / 2f
        val centerY = if (currentMode == ExpressionMode.ENERGY) {
            size.height * 0.68f
        } else {
            size.height / 2f
        }
        val time = System.currentTimeMillis() / 1000f

        // 验证亮度值（调试用）
        android.util.Log.d("ExpressionRenderer", "lightConfig: brightness=${lightConfig.brightness}, highlight=${lightConfig.highlight}, colorTemp=${lightConfig.colorTemp}, isNight=${lightConfig.isNightMode}")

        // 更新色温颜色矩阵（复用已有对象，不新建）
        updateColorMatrix(lightConfig)
        filterPaint.colorFilter = ColorFilter.colorMatrix(colorMatrix)
        
        // 验证颜色矩阵值（调试用）
        android.util.Log.d("ExpressionRenderer", "colorMatrix: R=${colorMatrix.values[0]}, G=${colorMatrix.values[6]}, B=${colorMatrix.values[12]}, A=${colorMatrix.values[18]}")

        // 应用微动画：呼吸缩放
        val microAnim = MicroAnimationLayer.getInstance().apply(state.type)

        drawIntoCanvas { canvas ->
            canvas.save()

            // 应用环境光颜色滤镜（亮度+色温）
            drawContext.canvas.saveLayer(bounds = size.toRect(), paint = filterPaint)

            // 根据当前模式分发渲染
            if (currentMode == ExpressionMode.ENERGY) {
                // ========== 能量模式：分层能量场渲染 ==========
                with(energyRenderer) {
                    renderField(
                        params = state.getEnergyFieldParameters(),
                        centerX = centerX,
                        centerY = centerY,
                        size = size,
                        time = time
                    )
                }
            } else {
                // ========== 传统模式 ==========
                if (state.style == ExpressionStyle.SHAPE_MALE || state.style == ExpressionStyle.SHAPE_FEMALE) {
                    // 形状风格：独立渲染体系，不复用传统模式的scale/translate
                    val expressionManager = ExpressionManager.getInstance()
                    val pupilOffsetX = expressionManager.pupilOffsetX
                    val pupilOffsetY = expressionManager.pupilOffsetY
                    
                    ShapeExpressionRenderer.render(
                        drawScope = this,
                        style = state.style,
                        type = state.type,
                        size = size,
                        centerX = centerX,
                        centerY = centerY,
                        pupilOffsetX = pupilOffsetX,
                        pupilOffsetY = pupilOffsetY,
                        transitionProgress = state.transitionProgress,
                        time = time
                    )
                } else {
                    // 传统参数化风格：应用呼吸缩放和头部微动
                    scale(microAnim.scale, microAnim.scale, pivot = Offset(centerX, centerY)) {
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
                            var adjustedPupilRadius = eyeMetrics.eyeWidth * 0.18f
                            var adjustedLeftPupil = leftPupil
                            var adjustedRightPupil = rightPupil

                            // 表情过渡进度（弹性缓动），用于平滑切换
                            val transitionProgress = state.transitionProgress.coerceIn(0f, 1f)
                            val easedProgress = (1f - (1f - transitionProgress) * (1f - transitionProgress)).coerceIn(0f, 1f) // easeOutQuad

                            // 根据表情调整瞳孔大小和位置，带平滑过渡
                            when (state.type) {
                                ExpressionType.HAPPY -> {
                                    // 开心：瞳孔放大30%，随过渡进度平滑变化
                                    adjustedPupilRadius *= 1f + 0.3f * easedProgress
                                }
                                ExpressionType.CONFUSED -> {
                                    // 思考：瞳孔向上偏移30%半径，随过渡进度平滑变化
                                    val offsetY = adjustedPupilRadius * 0.3f * easedProgress
                                    adjustedLeftPupil = Offset(leftPupil.x, leftPupil.y - offsetY)
                                    adjustedRightPupil = Offset(rightPupil.x, rightPupil.y - offsetY)
                                }
                                else -> {}
                            }

                            // 绘制眼睛
                            with(styleRenderer) {
                                drawEyes(eyeParams, centerX, centerY, size, time)
                            }

                            // 如果瞳孔有调整，用黑色覆盖原瞳孔，画调整后的瞳孔（带过渡）
                            if (easedProgress > 0.01f && (state.type == ExpressionType.HAPPY || state.type == ExpressionType.CONFUSED)) {
                                drawCircle(
                                    color = Color.Black,
                                    radius = adjustedPupilRadius,
                                    center = adjustedLeftPupil
                                )
                                drawCircle(
                                    color = Color.Black,
                                    radius = adjustedPupilRadius,
                                    center = adjustedRightPupil
                                )
                            }

                            // 绘制眼内特效（使用调整后的瞳孔位置和大小，带淡入淡出）
                            // 仅当当前风格支持眼内特效时才绘制
                            if (styleRenderer.supportsEyeEffects) {
                                drawEyeEffects(adjustedLeftPupil, adjustedRightPupil, adjustedPupilRadius, eyeMetrics.scale, time, lightConfig.highlight * easedProgress, effect)
                            }
                            drawSystemStateOverlays(
                                state = state,
                                eyeMetrics = eyeMetrics,
                                centerX = centerX,
                                centerY = centerY,
                                size = size,
                                time = time
                            )
                        }
                    }
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
     * 绘制眼内特效
     */
    private fun DrawScope.drawEyeEffects(
        leftPupil: Offset,
        rightPupil: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float,
        effect: EyeEffectType
    ) {
        if (effect == EyeEffectType.NONE) return

        listOf(leftPupil, rightPupil).forEach { center ->
            when (effect) {
                EyeEffectType.HAPPY -> drawHappyEffect(center, pupilRadius, scale, time, highlight)
                EyeEffectType.CONFUSED -> drawConfusedEffect(center, pupilRadius, scale, time, highlight)
                EyeEffectType.RECOGNIZING -> drawRecognizingEffect(center, pupilRadius, scale, time, highlight)
                EyeEffectType.SHY -> drawShyEffect(center, pupilRadius, scale, time, highlight)
                EyeEffectType.SYSTEM_ERROR -> drawSystemErrorEffect(center, pupilRadius, scale, time, highlight)
                EyeEffectType.RESPONSE -> drawResponseEffect(center, pupilRadius, scale, time, highlight)
                else -> {}
            }
        }
    }

    /**
     * 开心特效：四角星星，明亮闪烁
     */
    private fun DrawScope.drawHappyEffect(
        center: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        // 中心暖光
        drawCircle(
            color = Color(0xFFFFF9C4).copy(alpha = 0.5f * highlight),
            radius = pupilRadius * 0.9f,
            center = center,
            style = Fill
        )

        // 12颗四角星星
        val starCount = 12
        repeat(starCount) { i ->
            val angle = (i * 360f / starCount + time * 12f) * PI.toFloat() / 180f
            val baseRadius = when {
                i % 3 == 0 -> pupilRadius * 0.3f
                i % 3 == 1 -> pupilRadius * 0.55f
                else -> pupilRadius * 0.8f
            }
            val floatOffset = sin(time * 0.9f + i * 1.1f) * pupilRadius * 0.1f
            val distance = baseRadius + floatOffset
            val x = center.x + cos(angle) * distance
            val y = center.y + sin(angle) * distance

            val twinkle = 0.7f + 0.3f * sin(time * 3f + i * 0.8f)
            val starSize = (3.5f + (i % 3) * 1.5f) * scale * twinkle
            val alpha = twinkle * highlight

            // 画四角星：横+竖两条线
            drawLine(
                color = Color(0xFFFFD54F).copy(alpha = alpha),
                start = Offset(x - starSize, y),
                end = Offset(x + starSize, y),
                strokeWidth = 2f * scale,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFFFFD54F).copy(alpha = alpha),
                start = Offset(x, y - starSize),
                end = Offset(x, y + starSize),
                strokeWidth = 2f * scale,
                cap = StrokeCap.Round
            )
            // 星星中心亮点
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = starSize * 0.4f,
                center = Offset(x, y)
            )
        }
    }

    /**
     * 疑惑特效：瞳孔内问号（瞳孔已自动上移）
     */
    private fun DrawScope.drawConfusedEffect(
        center: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        val floatOffset = sin(time * 2.5f) * 2f * scale
        val questionX = center.x
        val questionY = center.y - pupilRadius * 0.1f + floatOffset

        val questionSize = pupilRadius * 0.9f * scale
        val strokeW = 3f * scale
        val questionColor = Color(0xFFE0E0E0).copy(alpha = 0.75f * highlight)

        val arcRect = androidx.compose.ui.geometry.Rect(
            offset = Offset(
                questionX - questionSize * 0.45f,
                questionY - questionSize * 0.5f
            ),
            size = Size(questionSize * 0.9f, questionSize * 0.7f)
        )

        drawArc(
            color = questionColor,
            startAngle = 180f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = arcRect.topLeft,
            size = arcRect.size,
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )

        val endAngle = (180f + 270f) * PI.toFloat() / 180f
        val radiusX = arcRect.width / 2f
        val radiusY = arcRect.height / 2f
        val arcCenterX = arcRect.center.x
        val arcCenterY = arcRect.center.y
        val arcEndX = arcCenterX + radiusX * cos(endAngle)
        val arcEndY = arcCenterY + radiusY * sin(endAngle)

        drawLine(
            color = questionColor,
            start = Offset(arcEndX, arcEndY),
            end = Offset(arcEndX, arcEndY + questionSize * 0.25f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )

        drawCircle(
            color = questionColor,
            radius = strokeW * 0.7f,
            center = Offset(arcEndX, arcEndY + questionSize * 0.35f + strokeW * 0.7f)
        )
    }

    /**
     * 识别中特效：粗扫描线+亮圆环
     */
    private fun DrawScope.drawRecognizingEffect(
        center: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        repeat(3) { i ->
            val ringPhase = (time * 0.8f + i * 0.35f) % 1f
            val ringRadius = pupilRadius * (0.25f + ringPhase * 0.8f)
            val ringAlpha = (1f - ringPhase) * 0.8f * highlight

            drawCircle(
                color = Color(0xFF4FC3F7).copy(alpha = ringAlpha),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 3f * scale)
            )
        }

        drawCircle(
            color = Color(0xFF81D4FA).copy(alpha = 0.4f * highlight),
            radius = pupilRadius * 0.4f,
            center = center,
            style = Fill
        )

        val scanY = center.y + sin(time * 3f) * pupilRadius * 0.75f
        val lineWidth = pupilRadius * 1.6f
        drawLine(
            color = Color(0xFF00E5FF).copy(alpha = 0.9f * highlight),
            start = Offset(center.x - lineWidth * 0.5f, scanY),
            end = Offset(center.x + lineWidth * 0.5f, scanY),
            strokeWidth = 4f * scale,
            cap = StrokeCap.Round
        )

        drawCircle(
            color = Color.White.copy(alpha = highlight),
            radius = 4f * scale,
            center = Offset(center.x - lineWidth * 0.5f, scanY)
        )
        drawCircle(
            color = Color.White.copy(alpha = highlight),
            radius = 4f * scale,
            center = Offset(center.x + lineWidth * 0.5f, scanY)
        )
    }

    /**
     * 害羞特效：瞳孔边缘粉色描边
     */
    private fun DrawScope.drawShyEffect(
        center: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        val breath = 0.9f + 0.1f * sin(time * 1.5f)
        drawCircle(
            color = Color(0xFFF48FB1).copy(alpha = 0.4f * breath * highlight),
            radius = pupilRadius * 1.05f,
            center = center,
            style = Stroke(width = 5f * scale)
        )
    }

    /**
     * 系统故障/警告特效：超出瞳孔范围，大色块闪烁，不会被眼白遮住
     */
    private fun DrawScope.drawSystemErrorEffect(
        center: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        val glitchColors = listOf(
            Color(0xFFFF1744),
            Color(0xFFFFEA00),
            Color(0xFF00E5FF),
            Color(0xFFFF9100),
            Color(0xFFD500F9)
        )

        // 大色块，范围扩大到1.5倍瞳孔半径（到眼白区域）
        repeat(25) { i ->
            val glitchTime = (time * 25f).toInt() + i
            val px = (sin(glitchTime * 12.9898f) * 43758.5453f % 1f + 1f) % 1f
            val py = (sin(glitchTime * 78.233f) * 43758.5453f % 1f + 1f) % 1f
            val pc = (sin(glitchTime * 43.231f) * 43758.5453f % 1f + 1f) % 1f

            // 范围扩大到1.5倍半径，覆盖眼白
            val x = center.x + (px - 0.5f) * pupilRadius * 2.8f
            val y = center.y + (py - 0.5f) * pupilRadius * 2.8f
            val colorIndex = (pc * glitchColors.size).toInt().coerceIn(0, glitchColors.size - 1)

            val blockSize = (4f + (i % 4) * 2f) * scale
            val flickerAlpha = if ((glitchTime + i) % 2 != 0) highlight else 0.15f * highlight

            drawRect(
                color = glitchColors[colorIndex].copy(alpha = flickerAlpha),
                topLeft = Offset(x - blockSize / 2, y - blockSize / 2),
                size = Size(blockSize, blockSize)
            )
        }

        // 粗故障横线，跨整个眼睛
        repeat(5) { lineIdx ->
            if ((time * 12f + lineIdx * 2f).toInt() % 2 == 0) {
                val lineY = center.y + (sin(time * 35f + lineIdx) * pupilRadius * 1.3f).toInt() * 1f
                val lineOffset = sin(time * 45f + lineIdx) * pupilRadius * 0.4f
                drawLine(
                    color = Color.White.copy(alpha = 0.9f * highlight),
                    start = Offset(center.x - pupilRadius * 1.5f + lineOffset, lineY),
                    end = Offset(center.x + pupilRadius * 1.5f + lineOffset, lineY + 3f),
                    strokeWidth = 3f * scale
                )
            }
        }

        // 中心红色警告脉冲
        val pulse = 0.5f + 0.5f * sin(time * 8f)
        drawCircle(
            color = Color(0xFFFF1744).copy(alpha = pulse * 0.6f * highlight),
            radius = pupilRadius * (0.8f + pulse * 0.3f),
            center = center,
            style = Stroke(width = 4f * scale)
        )
    }

    /**
     * 应答反馈特效：实心填充月牙+发光，非常明显
     */
    private fun DrawScope.drawResponseEffect(
        center: Offset,
        pupilRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        val silverColor = Color(0xFFF5F5F5).copy(alpha = 0.95f * highlight)
        val silverGlow = Color(0xFFFFFFFF).copy(alpha = 0.4f * highlight)

        val moonCenter = Offset(center.x, center.y + pupilRadius * 0.05f)
        val moonRadius = pupilRadius * 0.55f * scale

        // 外发光
        drawCircle(
            color = silverGlow,
            radius = moonRadius * 1.8f,
            center = moonCenter,
            style = Fill
        )

        // 实心月牙：用路径画填充的弯月
        // 先画外圆填充
        drawArc(
            color = silverColor,
            startAngle = 20f,
            sweepAngle = 30f,
            useCenter = true,
            topLeft = Offset(moonCenter.x - moonRadius, moonCenter.y - moonRadius),
            size = Size(moonRadius * 2, moonRadius * 2),
            style = Fill
        )

        // 用黑色圆遮挡出月牙形状
        drawCircle(
            color = Color.Black,
            radius = moonRadius * 0.85f,
            center = Offset(moonCenter.x + moonRadius * 0.35f, moonCenter.y - moonRadius * 0.1f)
        )

        // 月牙边缘高光
        drawArc(
            color = Color.White.copy(alpha = highlight),
            startAngle = 30f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(moonCenter.x - moonRadius, moonCenter.y - moonRadius),
            size = Size(moonRadius * 2, moonRadius * 2),
            style = Stroke(width = 3f * scale, cap = StrokeCap.Round)
        )

        // 12颗大星光
        val starCount = 12
        repeat(starCount) { i ->
            val angle = (i * 360f / starCount + time * 10f) * PI.toFloat() / 180f
            val orbitRadius = pupilRadius * (0.6f + 0.2f * sin(time * 0.9f + i * 0.6f))
            val x = center.x + cos(angle) * orbitRadius
            val y = center.y + sin(angle) * orbitRadius

            val twinkle = 0.5f + 0.5f * sin(time * 3.5f + i * 1.1f)
            val starSize = (2.5f + (i % 2) * 1.5f) * scale * twinkle
            val starAlpha = twinkle * highlight

            // 十字星光
            drawLine(
                color = Color.White.copy(alpha = starAlpha),
                start = Offset(x - starSize * 1.5f, y),
                end = Offset(x + starSize * 1.5f, y),
                strokeWidth = 1.5f * scale,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White.copy(alpha = starAlpha),
                start = Offset(x, y - starSize * 1.5f),
                end = Offset(x, y + starSize * 1.5f),
                strokeWidth = 1.5f * scale,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = Color.White.copy(alpha = starAlpha),
                radius = starSize * 0.5f,
                center = Offset(x, y)
            )
        }
    }

    private fun DrawScope.drawSystemStateOverlays(
        state: ExpressionState,
        eyeMetrics: EyeMetrics,
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

            ExpressionType.IDLE -> {
                // 待机状态：电量修饰叠加（不改变表情类型，仅叠加视觉效果）
                // 保持原有待机逻辑不变，仅通过眼周光晕颜色/动画反映电量状态
                val batteryState = ExpressionManager.getInstance().getIdleBatteryState()
                when (batteryState) {
                    ExpressionManager.IdleBatteryState.LOW_BATTERY -> {
                        // 低电量：双眼橙黄色柔和光晕 + 缓慢脉冲
                        val pulseAlpha = 0.25f + 0.2f * sin(time * 1.2f)
                        val glowColor = Color(0xFFFF9800)
                        // 左右眼分别绘制三层渐变光晕，贴合眼型
                        listOf(eyeMetrics.leftEyeCenter, eyeMetrics.rightEyeCenter).forEach { eyeCenter ->
                            // 外层大光晕
                            drawOval(
                                color = glowColor.copy(alpha = pulseAlpha * 0.4f),
                                topLeft = Offset(
                                    eyeCenter.x - eyeMetrics.eyeWidth * 0.9f,
                                    eyeCenter.y - eyeMetrics.eyeHeight * 0.9f
                                ),
                                size = Size(eyeMetrics.eyeWidth * 1.8f, eyeMetrics.eyeHeight * 1.8f)
                            )
                            // 中层光晕
                            drawOval(
                                color = glowColor.copy(alpha = pulseAlpha * 0.6f),
                                topLeft = Offset(
                                    eyeCenter.x - eyeMetrics.eyeWidth * 0.65f,
                                    eyeCenter.y - eyeMetrics.eyeHeight * 0.65f
                                ),
                                size = Size(eyeMetrics.eyeWidth * 1.3f, eyeMetrics.eyeHeight * 1.3f)
                            )
                        }
                    }
                    ExpressionManager.IdleBatteryState.CHARGING -> {
                        // 充电中：双眼绿色柔和光晕 + 呼吸效果
                        val breathAlpha = 0.2f + 0.25f * (0.5f + 0.5f * sin(time * 0.8f))
                        val glowColor = Color(0xFF66BB6A)
                        // 左右眼分别绘制三层渐变光晕，贴合眼型
                        listOf(eyeMetrics.leftEyeCenter, eyeMetrics.rightEyeCenter).forEach { eyeCenter ->
                            // 外层大光晕
                            drawOval(
                                color = glowColor.copy(alpha = breathAlpha * 0.4f),
                                topLeft = Offset(
                                    eyeCenter.x - eyeMetrics.eyeWidth * 1.0f,
                                    eyeCenter.y - eyeMetrics.eyeHeight * 1.0f
                                ),
                                size = Size(eyeMetrics.eyeWidth * 2.0f, eyeMetrics.eyeHeight * 2.0f)
                            )
                            // 中层光晕
                            drawOval(
                                color = glowColor.copy(alpha = breathAlpha * 0.6f),
                                topLeft = Offset(
                                    eyeCenter.x - eyeMetrics.eyeWidth * 0.7f,
                                    eyeCenter.y - eyeMetrics.eyeHeight * 0.7f
                                ),
                                size = Size(eyeMetrics.eyeWidth * 1.4f, eyeMetrics.eyeHeight * 1.4f)
                            )
                        }
                    }
                    else -> {
                        // 正常电量：无额外修饰
                    }
                }
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
                // 告警状态：加粗加大深红色感叹号，眉心位置，2Hz脉冲闪烁
                val alertSize = 60f * scale  // 从25px加大到60px，和问号同级别大小
                val alertX = centerX
                val alertY = centerY - size.height * 0.22f  // 和问号位置对应
                // 2Hz脉冲闪烁（慢闪更有警示感，不像故障）
                val flashAlpha = if ((time * 2f).toInt() % 2 == 0) 1.0f else 0.4f
                val alertColor = Color(0xFFD50000).copy(alpha = flashAlpha)  // 深红，和眼睛光效一致

                // 感叹号上半部分：超粗竖线，圆角端点
                val barWidth = 14f * scale  // 从4px加粗到14px
                drawLine(
                    color = alertColor,
                    start = Offset(alertX, alertY - alertSize/2),
                    end = Offset(alertX, alertY + alertSize * 0.2f),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
                // 感叹号底部圆点：加大加粗
                drawCircle(
                    color = alertColor,
                    radius = 9f * scale,  // 从4px加大到9px
                    center = Offset(alertX, alertY + alertSize * 0.55f)
                )
                // 外圈红色光晕圈，增强警示感
                drawCircle(
                    color = Color(0xFFFF5252).copy(alpha = flashAlpha * 0.3f),
                    radius = alertSize * 1.2f,
                    center = Offset(alertX, alertY),
                    style = Stroke(width = 6f * scale)
                )
            }

            ExpressionType.CONFUSED -> {
                // 疑惑表情：问号？从无到有缩放淡入，眼睛右上方
                val elapsed = System.currentTimeMillis() - state.startTime
                val animDuration = 1500L
                val progress = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
                // 前40%缩放淡入，后60%保持并轻微上下浮动
                val appearProgress = (progress / 0.4f).coerceIn(0f, 1f)
                val holdProgress = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                val scaleAnim = appearProgress * appearProgress * (3 - 2 * appearProgress) // smoothStep
                val floatOffset = if (holdProgress > 0f) sin(holdProgress * Math.PI.toFloat() * 3) * 6f * scale else 0f


                val questionX = centerX + size.width * 0.2f
                val questionY = centerY - size.height * 0.22f + floatOffset

                // 问号整体参数
                val questionSize = 80f * scale
                val strokeW = 5f * scale * scaleAnim
                val questionColor = Color(0xFFFFC107).copy(alpha = scaleAnim * 0.95f)

                // 计算弧线的外接矩形
                val arcRect = androidx.compose.ui.geometry.Rect(
                    offset = Offset(
                        questionX - questionSize * 0.45f,
                        questionY - questionSize * 0.5f
                    ),
                    size = Size(questionSize * 0.9f, questionSize * 0.7f)
                )

                // 1. 问号上半部分圆弧
                drawArc(
                    color = questionColor,
                    startAngle = 180f,      // 从右下往左上画弧
                    sweepAngle = 270f,      // 扫过270度，形成标准问号上半弧
                    useCenter = false,
                    topLeft = arcRect.topLeft,
                    size = arcRect.size,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )

                val endAngle = Math.toRadians((180f + 270f).toDouble())
                val arcCenterX = arcRect.center.x
                val arcCenterY = arcRect.center.y
                val radiusX = arcRect.width / 2
                val radiusY = arcRect.height / 2

                // 弧线终点坐标（椭圆上的精确点）
                val arcEndX = arcCenterX + radiusX * kotlin.math.cos(endAngle).toFloat()
                val arcEndY = arcCenterY + radiusY * kotlin.math.sin(endAngle).toFloat()

                // 2. 问号中间竖线（从弧线终点精确衔接）
                drawLine(
                    color = questionColor,
                    start = Offset(arcEndX, arcEndY),
                    end = Offset(
                        arcEndX,
                        arcEndY + questionSize * 0.8f  // 竖线长度
                    ),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )

                // 3. 问号底部圆点（在竖线正下方）
                drawCircle(
                    color = questionColor,
                    radius = strokeW * 0.85f,
                    center = Offset(
                        arcEndX,
                        arcEndY + questionSize * 0.9f + strokeW * 0.85f  // 紧贴竖线末端
                    )
                )
            }

            ExpressionType.HAPPY -> {
                // 开心表情：爱心❤漂浮动画
                val elapsed = System.currentTimeMillis() - state.startTime
                val animDuration = 1800L
                val progress = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
                val heartColor = Color(0xFFFF4081)

                // 主爱心（眼睛上方居中）
                val mainAppear = (progress / 0.3f).coerceIn(0f, 1f)
                val mainScale = mainAppear * mainAppear * (3 - 2 * mainAppear)
                val mainHeartSize = 45f * scale * mainScale
                val mainHeartY = centerY - size.height * 0.25f + sin(progress * Math.PI.toFloat() * 4) * 5f * scale
                drawHeart(centerX, mainHeartY, mainHeartSize, heartColor.copy(alpha = 0.95f * mainScale), scale)

            }

            ExpressionType.SHY -> {
                // 害羞表情：粉红色脸红（眼睛下方两侧）
                val elapsed = System.currentTimeMillis() - state.startTime
                val animDuration = 1200L
                val progress = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
                val blushAlpha = (progress / 0.5f).coerceIn(0f, 1f) * 0.6f
                val blushColor = Color(0xFFFF8A95).copy(alpha = blushAlpha)
                val blushRadiusX = eyeMetrics.eyeWidth * 0.4f
                val blushRadiusY = eyeMetrics.eyeHeight * 0.25f
                val blushOffsetY = eyeMetrics.eyeHeight * 0.4f

                // 左脸颊红晕
                drawOval(
                    color = blushColor,
                    topLeft = Offset(
                        eyeMetrics.leftEyeCenter.x - blushRadiusX,
                        eyeMetrics.leftEyeCenter.y + blushOffsetY - blushRadiusY
                    ),
                    size = Size(blushRadiusX * 2, blushRadiusY * 2)
                )
                // 右脸颊红晕
                drawOval(
                    color = blushColor,
                    topLeft = Offset(
                        eyeMetrics.rightEyeCenter.x - blushRadiusX,
                        eyeMetrics.rightEyeCenter.y + blushOffsetY - blushRadiusY
                    ),
                    size = Size(blushRadiusX * 2, blushRadiusY * 2)
                )
            }

            else -> {}
        }
    }

    /**
     * 绘制爱心形状
     * 用两个圆 + 底部三角组成经典饱满爱心造型
     */
    private fun DrawScope.drawHeart(
        centerX: Float,
        centerY: Float,
        size: Float,
        color: Color,
        scale: Float
    ) {
        if (size < 1f) return
        val radius = size * 0.4f
        val circleOffset = size * 0.25f
        val circleY = centerY - circleOffset * 0.3f

        // 左半圆
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(centerX - circleOffset, circleY)
        )
        // 右半圆
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(centerX + circleOffset, circleY)
        )
        // 底部三角（用path）
        tempPath.rewind()
        tempPath.moveTo(centerX - size * 0.5f, circleY + radius * 0.3f)
        tempPath.lineTo(centerX + size * 0.5f, circleY + radius * 0.3f)
        tempPath.lineTo(centerX, centerY + size * 0.6f)
        tempPath.close()
        drawPath(
            path = tempPath,
            color = color
        )
    }
}
