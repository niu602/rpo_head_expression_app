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
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.StrokeCap
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
                    drawSystemStateOverlays(state, eyeMetrics, centerX, centerY, size, time)
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
     * 5类特效：思考加载、识别成功、开心星光、错误脉冲、无特效
     * 所有特效均为瞳孔区域叠加，不破坏眼睛主体造型
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

        // 特效严格限制在瞳孔周围 1.6 倍半径范围内，不超出眼白区域
        val maxEffectRadius = pupilRadius * 1.6f

        // 对左右眼分别绘制特效
        listOf(leftPupil, rightPupil).forEach { pupilCenter ->
            when (effect) {
                // ==================== 思考特效：双层反向旋转圆弧 ====================
                EyeEffectType.THINKING_LOADING -> {
                    drawThinkingLoadingEffect(pupilCenter, pupilRadius, maxEffectRadius, scale, time, highlight)
                }

                // ==================== 识别成功：星芒高光一次性闪过 ====================
                EyeEffectType.RECOGNIZE_SUCCESS -> {
                    drawRecognizeSuccessEffect(pupilCenter, pupilRadius, maxEffectRadius, scale, time, highlight)
                }

                // ==================== 开心特效：细碎星光粒子动效 ====================
                EyeEffectType.HAPPY_STARLIGHT -> {
                    drawHappyStarlightEffect(pupilCenter, pupilRadius, maxEffectRadius, scale, time, highlight)
                }

                // ==================== 错误特效：红色脉冲波纹 ====================
                EyeEffectType.ERROR_PULSE -> {
                    drawErrorPulseEffect(pupilCenter, pupilRadius, maxEffectRadius, scale, time, highlight)
                }

                else -> {}
            }
        }
    }

    /**
     * 思考加载特效：双层反向旋转圆弧
     * 外层2段120°圆弧顺时针旋转，内层2段90°圆弧逆时针旋转
     * 形成精致的加载动画感，严格控制在瞳孔周围
     */
    private fun DrawScope.drawThinkingLoadingEffect(
        center: Offset,
        pupilRadius: Float,
        maxRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        // 外层：大圆弧，顺时针
        val outerRadius = pupilRadius * 1.35f
        val outerAngle = time * 120f // 旋转速度（度/秒）
        val outerStroke = 2.5f * scale

        // 内层：小圆弧，逆时针
        val innerRadius = pupilRadius * 1.05f
        val innerAngle = -time * 180f // 反向旋转
        val innerStroke = 1.8f * scale

        // 外层两段圆弧（对称分布）
        for (i in 0..1) {
            val startAngle = outerAngle + i * 180f
            drawArc(
                color = Color.White.copy(alpha = 0.65f * highlight),
                startAngle = startAngle,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                size = Size(outerRadius * 2, outerRadius * 2),
                style = Stroke(width = outerStroke)
            )
        }

        // 内层两段圆弧（反向）
        for (i in 0..1) {
            val startAngle = innerAngle + i * 180f + 45f // 错开相位
            drawArc(
                color = Color.White.copy(alpha = 0.45f * highlight),
                startAngle = startAngle,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                size = Size(innerRadius * 2, innerRadius * 2),
                style = Stroke(width = innerStroke)
            )
        }

        // 中心微光点（加载中心）
        drawCircle(
            color = Color.White.copy(alpha = 0.25f * highlight),
            radius = pupilRadius * 0.15f,
            center = center
        )
    }

    /**
     * 识别成功特效：星芒高光一次性闪过
     * 8道放射光线 + 扩散光环 + 中心亮点
     * 动画曲线：0~30% 快速亮起扩散，30%~100% 渐隐消失
     * 使用表情已播放时长比例控制，确保只完整闪过一次
     */
    private fun DrawScope.drawRecognizeSuccessEffect(
        center: Offset,
        pupilRadius: Float,
        maxRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        // 使用表情已播放时长比例作为特效进度（0~1），确保一次完整闪过
        val state = ExpressionManager.getInstance().expressionState.value
        val elapsed = (System.currentTimeMillis() - state.startTime).toFloat()
        val duration = state.duration.toFloat().coerceAtLeast(500f)
        val rawProgress = (elapsed / duration).coerceIn(0f, 1f)

        // 重映射动画曲线：前30%快速展开增强，后70%渐隐消失
        val progress = if (rawProgress < 0.3f) {
            rawProgress / 0.3f  // 0 → 1 快速达到峰值
        } else {
            1f - (rawProgress - 0.3f) / 0.7f  // 1 → 0 渐隐
        }.coerceIn(0f, 1f)

        if (progress <= 0.01f) return

        val rayCount = 8
        val maxRayLength = pupilRadius * (0.7f + rawProgress * 0.7f)
        val rayStroke = (1.5f + progress * 1f) * scale
        val rayAlpha = progress * 0.75f * highlight

        // 8道星芒射线
        for (i in 0 until rayCount) {
            val angle = (i * 360f / rayCount) * Math.PI / 180f
            val endX = center.x + cos(angle).toFloat() * maxRayLength
            val endY = center.y + sin(angle).toFloat() * maxRayLength
            drawLine(
                color = Color.White.copy(alpha = rayAlpha),
                start = center,
                end = Offset(endX, endY),
                strokeWidth = rayStroke
            )
        }

        // 扩散光环（圆环）
        val ringRadius = pupilRadius * (0.5f + rawProgress * 1.0f)
        val ringAlpha = (1f - rawProgress) * 0.5f * highlight
        val ringStroke = 2f * scale * (1f - rawProgress * 0.5f)
        drawCircle(
            color = Color.White.copy(alpha = ringAlpha),
            radius = ringRadius,
            center = center,
            style = Stroke(width = ringStroke.coerceAtLeast(0.5f))
        )

        // 中心亮点
        val coreRadius = pupilRadius * 0.35f * progress
        drawCircle(
            color = Color.White.copy(alpha = progress * 0.6f * highlight),
            radius = coreRadius,
            center = center
        )
    }

    /**
     * 开心特效：瞳孔周边细碎星光粒子动效
     * 12个粒子分布在瞳孔周围，大小不一，不同相位闪烁
     * 整体有呼吸感，粒子位置缓慢漂移，灵动不生硬
     */
    private fun DrawScope.drawHappyStarlightEffect(
        center: Offset,
        pupilRadius: Float,
        maxRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        val starCount = 12

        // 整体呼吸亮度（慢节奏）
        val breath = 0.7f + 0.3f * sin(time * 1.5f)

        repeat(starCount) { i ->
            // 每个粒子有独立的角度、距离、相位（基于索引计算，避免每帧随机跳变）
            val baseAngle = (i * 360f / starCount + 15f) * Math.PI / 180f
            val angleDrift = sin(time * 0.5f + i * 0.7f) * 0.15f // 角度缓慢漂移
            val angle = baseAngle + angleDrift

            // 距离：分布在 0.6 ~ 1.4 倍瞳孔半径
            val baseDist = pupilRadius * (0.6f + (i % 4) * 0.27f)
            val distDrift = sin(time * 0.8f + i * 1.1f) * pupilRadius * 0.12f
            val distance = baseDist + distDrift

            val starX = center.x + cos(angle).toFloat() * distance
            val starY = center.y + sin(angle).toFloat() * distance

            // 闪烁：每个粒子相位不同，亮度 0.3 ~ 1.0
            val blink = 0.35f + 0.65f * (sin(time * 2.5f + i * 0.9f) * 0.5f + 0.5f)

            // 大小：大小粒子错落，1 ~ 2.5 像素
            val baseSize = (1f + (i % 3) * 0.75f) * scale
            val sizePulse = 0.3f * sin(time * 3f + i * 0.5f)
            val starRadius = baseSize + sizePulse

            val alpha = blink * breath * 0.75f * highlight

            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = starRadius.coerceAtLeast(0.5f),
                center = Offset(starX, starY)
            )
        }

        // 中心微亮（开心时瞳孔微微发光）
        drawCircle(
            color = Color.White.copy(alpha = 0.12f * breath * highlight),
            radius = pupilRadius * 0.5f,
            center = center
        )
    }

    /**
     * 错误特效：眼周红色脉冲波纹
     * 3层圆环依次向外扩散，形成递进的波纹脉冲感
     * 每层圆环从实到虚扩散消失，中心红色脉动
     */
    private fun DrawScope.drawErrorPulseEffect(
        center: Offset,
        pupilRadius: Float,
        maxRadius: Float,
        scale: Float,
        time: Float,
        highlight: Float
    ) {
        val pulsePeriod = 1.2f // 脉冲周期 1.2 秒
        val ringCount = 3
        val ringOffset = 0.25f // 每层波纹时间偏移

        val errorColor = Color(0xFFFF5252)

        // 3层递进波纹
        for (i in 0 until ringCount) {
            val localTime = (time + i * ringOffset) % pulsePeriod
            val t = localTime / pulsePeriod // 0 ~ 1

            // 半径：从瞳孔大小扩散到 1.5 倍
            val ringRadius = pupilRadius * (0.9f + t * 0.6f)
            // 透明度：前20%快速显现，后80%渐隐
            val alpha = if (t < 0.2f) {
                t / 0.2f * 0.6f
            } else {
                (1f - (t - 0.2f) / 0.8f) * 0.6f
            } * highlight

            // 线宽：由粗变细
            val strokeWidth = (3f - t * 2f) * scale

            if (alpha > 0.01f && strokeWidth > 0.2f) {
                drawCircle(
                    color = errorColor.copy(alpha = alpha),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = strokeWidth.coerceAtLeast(0.5f))
                )
            }
        }

        // 中心红色脉动光点
        val pulse = 0.6f + 0.4f * sin(time * 4f)
        drawCircle(
            color = errorColor.copy(alpha = 0.25f * pulse * highlight),
            radius = pupilRadius * 0.7f,
            center = center
        )

        // 最内层微亮环
        drawCircle(
            color = errorColor.copy(alpha = 0.35f * highlight),
            radius = pupilRadius * 0.95f,
            center = center,
            style = Stroke(width = 1.5f * scale)
        )
    }

    /**
     * 绘制系统状态特殊叠加效果
     * 严格不涉及下半脸元素
     * 性能优化：复用 tempPath，避免每帧 new Path
     */
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
                val strokeW = 4f * scale * scaleAnim
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
                    startAngle = 225f,      // 从右下往左上画弧
                    sweepAngle = 270f,      // 扫过270度，形成标准问号上半弧
                    useCenter = false,
                    topLeft = arcRect.topLeft,
                    size = arcRect.size,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )

                val endAngle = Math.toRadians((225f + 270f).toDouble())
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

                // 两个小爱心（左右漂浮）
                if (progress > 0.3f) {
                    val sideProgress = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
                    val sideScale = sideProgress * (1f - sideProgress * 0.2f)
                    val floatY = -sideProgress * 55f * scale
                    val alpha = (1f - sideProgress) * 0.85f

                    // 左小爱心
                    val leftHeartX = centerX - size.width * 0.18f + sin(sideProgress * Math.PI.toFloat() * 3) * 10f * scale
                    val leftHeartY = centerY - size.height * 0.12f + floatY
                    drawHeart(leftHeartX, leftHeartY, 28f * scale * sideScale, heartColor.copy(alpha = alpha), scale)

                    // 右小爱心
                    val rightHeartX = centerX + size.width * 0.18f - sin(sideProgress * Math.PI.toFloat() * 3) * 10f * scale
                    val rightHeartY = centerY - size.height * 0.12f + floatY - 15f * scale
                    drawHeart(rightHeartX, rightHeartY, 25f * scale * sideScale, heartColor.copy(alpha = alpha * 0.9f), scale)
                }
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

                // 害羞时眼睛上方飘小爱心点缀（放大提高可见度）
                if (progress > 0.4f) {
                    val heartProgress = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    val heartAlpha = heartProgress * 0.8f * (1f - heartProgress * 0.3f)
                    val tinyHeartSize = 18f * scale * heartProgress
                    val tinyColor = Color(0xFFFF6B81).copy(alpha = heartAlpha)
                    drawHeart(centerX - 25f * scale, centerY - size.height * 0.2f - heartProgress * 20f * scale, tinyHeartSize, tinyColor, scale)
                }
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
