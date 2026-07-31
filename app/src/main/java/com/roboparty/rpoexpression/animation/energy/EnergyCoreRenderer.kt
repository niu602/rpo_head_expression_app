/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.energy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.roboparty.rpoexpression.expression.EnergyFieldParameters
import kotlin.math.sin

/**
 * L1 能量焦点渲染器（内核层）
 *
 * 绘制内容：
 * 1. 外层大光晕：径向渐变，大半径，低透明度（模拟辉光）
 * 2. 内层核心亮点：径向渐变，小半径，高透明度（模拟核心）
 * 3. 呼吸脉动：大小和亮度随时间周期性变化
 * 4. 颤动效果：高频微幅抖动（模拟能量不稳定感）
 *
 * 性能优化：所有 Brush/Color 对象无额外分配，
 * drawCircle 直接使用 DrawScope 内置 API。
 */
class EnergyCoreRenderer {

    // ========== 颜色缓存（避免每帧 HSL→Color 转换） ==========
    private var cachedHue = Float.MAX_VALUE
    private var cachedSat = -1f
    private var cachedLight = -1f
    private var coreColor: Color = Color.White
    private var haloColor: Color = Color.White

    /**
     * 渲染 L1 能量焦点
     *
     * @param params   能量场参数
     * @param centerX  Blob 中心 X（焦点基于此偏移）
     * @param centerY  Blob 中心 Y
     * @param time     当前时间（秒）
     */
    fun DrawScope.renderCore(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        time: Float
    ) {
        // 更新颜色缓存
        updateColorCache(params)

        val realBaseDim = minOf(this.size.width, this.size.height)

        // 颤动偏移（X/Y 不同相位，避免机械感）
        val tremorX = if (params.coreTremorAmp > 0f) {
            params.coreTremorAmp * realBaseDim * 0.02f * sin(time * params.coreTremorFreq * 2f * PI_FLOAT)
        } else 0f
        val tremorY = if (params.coreTremorAmp > 0f) {
            params.coreTremorAmp * realBaseDim * 0.02f * sin(time * params.coreTremorFreq * 2f * PI_FLOAT + PHASE_Y)
        } else 0f

        // 焦点位置（归一化偏移 → 像素偏移）
        val focusX = centerX + params.corePositionX * realBaseDim * 0.3f + tremorX
        val focusY = centerY + params.corePositionY * realBaseDim * 0.3f + tremorY

        // 呼吸脉动：亮度 ~0.85~1.0 摆动
        val breathBrightness = params.coreBrightness * (0.85f + 0.15f * sin(time * BREATH_FREQ))
        val clampedBrightness = breathBrightness.coerceIn(0f, 1f)

        // 呼吸脉动：大小 ~0.9~1.1 摆动
        val breathScale = 0.95f + 0.05f * sin(time * BREATH_FREQ)
        val coreRadius = (realBaseDim * params.coreSize * 0.12f * breathScale).coerceAtLeast(0.5f)

        // 单焦点或双焦点
        val positions = if (params.coreCount == 2) {
            val sep = realBaseDim * 0.15f
            listOf(Offset(focusX - sep, focusY), Offset(focusX + sep, focusY))
        } else {
            listOf(Offset(focusX, focusY))
        }

        positions.forEach { pos ->
            drawSingleCore(pos, coreRadius, clampedBrightness)
        }
    }

    /**
     * 绘制单个焦点：外层光晕 + 核心亮点 + 白心
     */
    private fun DrawScope.drawSingleCore(
        pos: Offset,
        coreRadius: Float,
        brightness: Float
    ) {
        val haloRadius = coreRadius * 4f

        // ----- 外层大光晕：径向渐变，大半径低透明度 -----
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    haloColor.copy(alpha = 0.6f * brightness),
                    haloColor.copy(alpha = 0.15f * brightness),
                    Color.Transparent
                ),
                radius = haloRadius,
                center = pos
            ),
            radius = haloRadius,
            center = pos
        )

        // ----- 内层核心亮点：径向渐变，小半径高透明度 -----
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    coreColor.copy(alpha = brightness),
                    coreColor.copy(alpha = brightness * 0.5f)
                ),
                radius = coreRadius,
                center = pos
            ),
            radius = coreRadius,
            center = pos
        )

        // ----- 最内层白心（提升穿透力） -----
        drawCircle(
            color = Color.White.copy(alpha = brightness * 0.9f),
            radius = (coreRadius * 0.35f).coerceAtLeast(0.5f),
            center = pos
        )
    }

    /**
     * 更新 HSL → Color 缓存（仅在参数变化时重新计算）
     */
    private fun updateColorCache(params: EnergyFieldParameters) {
        if (params.primaryHue != cachedHue ||
            params.primarySaturation != cachedSat ||
            params.primaryLightness != cachedLight
        ) {
            cachedHue = params.primaryHue
            cachedSat = params.primarySaturation
            cachedLight = params.primaryLightness

            // 核心色 = 主色相 + 辅色偏移，提亮饱和度
            coreColor = hslToColor(
                (params.primaryHue + params.secondaryHueOffset + 360f) % 360f,
                (params.primarySaturation + 0.1f).coerceAtMost(1f),
                (params.primaryLightness + 0.25f).coerceAtMost(0.95f)
            )
            // 光晕色 = 核心色再提亮
            haloColor = hslToColor(
                (params.primaryHue + params.secondaryHueOffset + 360f) % 360f,
                (params.primarySaturation + 0.05f).coerceAtMost(1f),
                (params.primaryLightness + 0.35f).coerceAtMost(0.95f)
            )
        }
    }

    companion object {
        private val PI_FLOAT = Math.PI.toFloat()
        private const val PHASE_Y = 1.5f      // Y 颤动相位偏移
        private const val BREATH_FREQ = 0.5f   // 呼吸频率（~0.8s 周期）

        /**
         * HSL → Color 转换
         */
        fun hslToColor(h: Float, s: Float, l: Float): Color {
            if (s <= 0f) {
                val v = (l * 255f).toInt().coerceIn(0, 255)
                return Color(v, v, v)
            }

            val hh = h / 60f
            val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
            val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
            val m = l - c / 2f

            val r: Float
            val g: Float
            val b: Float

            when {
                hh < 1f -> { r = c; g = x; b = 0f }
                hh < 2f -> { r = x; g = c; b = 0f }
                hh < 3f -> { r = 0f; g = c; b = x }
                hh < 4f -> { r = 0f; g = x; b = c }
                hh < 5f -> { r = x; g = 0f; b = c }
                else -> { r = c; g = 0f; b = x }
            }

            return Color(
                red = (r + m).coerceIn(0f, 1f),
                green = (g + m).coerceIn(0f, 1f),
                blue = (b + m).coerceIn(0f, 1f)
            )
        }
    }
}
