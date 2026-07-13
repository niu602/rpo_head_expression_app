/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.style

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.roboparty.rpoexpression.expression.EyeParameters
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * 像素眼风格渲染器
 * 8bit复古像素风格，方块像素，锯齿边缘，复古游戏感
 */
class PixelEyeRenderer : EyeStyleRenderer {

    companion object {
        private const val PIXEL_GRID_SIZE = 8 // 8x8像素网格
    }

    override fun DrawScope.drawEyes(
        params: EyeParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val metrics = calculateEyeMetrics(params, centerX, centerY, size)
        val pixelSize = metrics.eyeWidth / PIXEL_GRID_SIZE

        // 像素风格眼距
        val pixelMetrics = metrics.copy(
            leftEyeCenter = Offset(centerX - metrics.eyeWidth * 1.15f, centerY),
            rightEyeCenter = Offset(centerX + metrics.eyeWidth * 1.15f, centerY)
        )

        // 像素光效
        drawPixelGlow(params, pixelMetrics, pixelSize, time)

        // 绘制像素眼
        drawPixelEye(params, pixelMetrics.leftEyeCenter, pixelMetrics, pixelSize, time)
        drawPixelEye(params, pixelMetrics.rightEyeCenter, pixelMetrics, pixelSize, time)
    }

    private fun DrawScope.drawPixelGlow(
        params: EyeParameters,
        metrics: EyeMetrics,
        pixelSize: Float,
        time: Float
    ) {
        if (params.glowIntensity <= 0f) return

        val glowAlpha = if (params.glowBlink) {
            val blinkFrame = (time * 8f).toInt() % 2
            params.glowIntensity * if (blinkFrame == 0) 0.5f else 0.2f
        } else {
            params.glowIntensity * 0.3f
        }

        listOf(metrics.leftEyeCenter, metrics.rightEyeCenter).forEach { center ->
            // 像素化光晕 - 3x3方块
            for (dx in -2..2) {
                for (dy in -2..2) {
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) <= 3) {
                        drawRect(
                            color = params.glowColor.copy(alpha = glowAlpha * (1f - (kotlin.math.abs(dx) + kotlin.math.abs(dy))/4f)),
                            topLeft = Offset(
                                center.x + dx * pixelSize * 2 - pixelSize,
                                center.y + dy * pixelSize * 2 - pixelSize
                            ),
                            size = Size(pixelSize * 2, pixelSize * 2)
                        )
                    }
                }
            }
        }
    }

    private fun DrawScope.drawPixelEye(
        params: EyeParameters,
        eyeCenter: Offset,
        metrics: EyeMetrics,
        pixelSize: Float,
        time: Float
    ) {
        val openHeight = (PIXEL_GRID_SIZE * params.eyelidOpenness).roundToInt()
        val tailOffset = (params.eyeTailAngle * 2).roundToInt()

        // 生成像素图案
        val pixelPattern = generatePixelPattern(params, openHeight, tailOffset, time)

        // 绘制像素
        for (y in 0 until PIXEL_GRID_SIZE) {
            for (x in 0 until PIXEL_GRID_SIZE) {
                val pixel = pixelPattern[y][x]
                if (pixel != Color.Transparent) {
                    drawRect(
                        color = pixel,
                        topLeft = Offset(
                            eyeCenter.x - metrics.eyeWidth/2 + x * pixelSize,
                            eyeCenter.y - metrics.eyeHeight/2 + y * pixelSize
                        ),
                        size = Size(pixelSize, pixelSize)
                    )
                }
            }
        }
    }

    private fun generatePixelPattern(
        params: EyeParameters,
        openHeight: Int,
        tailOffset: Int,
        time: Float
    ): Array<Array<Color>> {
        val pattern = Array(PIXEL_GRID_SIZE) { Array(PIXEL_GRID_SIZE) { Color.Transparent } }
        val white = Color.White
        val black = Color.Black
        val glow = params.glowColor

        // 眼白部分
        for (y in (PIXEL_GRID_SIZE - openHeight)/2 until (PIXEL_GRID_SIZE + openHeight)/2) {
            for (x in 0 until PIXEL_GRID_SIZE) {
                // 眼形：椭圆形
                val dx = (x - PIXEL_GRID_SIZE/2f) / (PIXEL_GRID_SIZE/2f)
                val dy = (y - PIXEL_GRID_SIZE/2f) / (openHeight/2f)
                if (dx*dx + dy*dy <= 1f) {
                    pattern[y][x] = white
                }
            }
        }

        // 瞳孔部分
        if (params.pupilSize > 0f && openHeight > 2) {
            val pupilSize = (3 * params.pupilSize * params.pupilScale).roundToInt().coerceAtLeast(1)
            val pupilX = (PIXEL_GRID_SIZE/2 + params.pupilOffsetX * 2).roundToInt()
            val pupilY = (PIXEL_GRID_SIZE/2 + params.pupilOffsetY * 2).roundToInt()

            for (dy in -pupilSize..pupilSize) {
                for (dx in -pupilSize..pupilSize) {
                    val px = pupilX + dx
                    val py = pupilY + dy
                    if (px in 0 until PIXEL_GRID_SIZE && py in 0 until PIXEL_GRID_SIZE) {
                        if (dx*dx + dy*dy <= pupilSize*pupilSize) {
                            pattern[py][px] = black
                        }
                    }
                }
            }

            // 像素高光（聚焦时显示）
            if (params.unfocused <= 0f) {
                val highlightFrame = (time * 2f).toInt() % 4
                if (highlightFrame < 3) {
                    val hx = pupilX - 1
                    val hy = pupilY - 1
                    if (hx in 0 until PIXEL_GRID_SIZE && hy in 0 until PIXEL_GRID_SIZE) {
                        pattern[hy][hx] = white
                    }
                }
            } else {
                // 涣散时不显示高光，显示闪烁像素
                val blinkPixel = (time * 3f).toInt() % 8
                if (blinkPixel < 2) {
                    val px = pupilX + (kotlin.random.Random.nextInt(3) - 1)
                    val py = pupilY + (kotlin.random.Random.nextInt(3) - 1)
                    if (px in 0 until PIXEL_GRID_SIZE && py in 0 until PIXEL_GRID_SIZE) {
                        pattern[py][px] = white.copy(alpha = 0.5f)
                    }
                }
            }

            // 发光瞳孔边缘
            for (dy in -pupilSize-1..pupilSize+1) {
                for (dx in -pupilSize-1..pupilSize+1) {
                    val px = pupilX + dx
                    val py = pupilY + dy
                    if (px in 0 until PIXEL_GRID_SIZE && py in 0 until PIXEL_GRID_SIZE) {
                        if (pattern[py][px] == white && kotlin.math.abs(dx) + kotlin.math.abs(dy) == pupilSize + 1) {
                            pattern[py][px] = glow.copy(alpha = params.glowIntensity * 0.7f)
                        }
                    }
                }
            }
        }

        // 眨眼效果：上半部分变黑
        if (params.eyelidOpenness < 0.3f) {
            for (y in 0 until PIXEL_GRID_SIZE/2) {
                for (x in 0 until PIXEL_GRID_SIZE) {
                    pattern[y][x] = black
                }
            }
        }

        return pattern
    }
}
