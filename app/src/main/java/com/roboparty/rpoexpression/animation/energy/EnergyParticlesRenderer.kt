/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.energy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.roboparty.rpoexpression.expression.EnergyFieldParameters
import com.roboparty.rpoexpression.expression.ParticleMode
import com.roboparty.rpoexpression.animation.energy.EnergyCoreRenderer.Companion.hslToColor
import kotlin.math.cos
import kotlin.math.sin

/**
 * L3 粒子环带渲染器（外层）
 *
 * 粒子系统设计（重新设计版）：
 * - 固定粒子池：始终维护 maxParticles 个粒子的位置，用 per-particle alpha 平滑淡入淡出
 * - 粒子位置 = 预计算基础轨道角度 + 时间偏移 × 速度 → 纯数学轨迹，无物理仿真
 * - 零每帧分配：所有数组预创建为成员变量
 *
 * 核心改进：
 * - 表情切换时粒子不再突变，而是通过 alpha 低通滤波平滑过渡
 * - 前 count 个粒子目标 alpha=1，其余=0，每帧逐渐逼近目标
 * - 颜色切换同样平滑（保留旧色逐渐过渡到新色）
 *
 * 支持的运动模式：
 * - ORBIT     轨道运动：粒子沿圆周轨道运动
 * - ROTATE    旋转：粒子整体绕中心旋转（思考感）
 * - CONVERGE  汇聚：粒子向中心流动（识别感）
 * - DIVERGE   发散：粒子向外扩散（惊讶/应答）
 * - BROWNIAN  布朗运动：粒子随机抖动（疑惑）
 * - FALL      下落：粒子向下飘（悲伤）
 * - RISE      上升：粒子向上飘（开心）
 *
 * 性能：60 粒子 × (2 次三角函数 + drawCircle) ≈ ~0.3ms/帧
 */
class EnergyParticlesRenderer {

    /** 粒子最大数量（硬上限，始终全部计算位置） */
    private val maxParticles = 60

    /** 预计算粒子基础轨道角度（均匀分布） */
    private val baseAngles = FloatArray(maxParticles) { i ->
        (i.toFloat() / maxParticles) * TWO_PI
    }

    /** 预计算粒子大小种子（确定性调制，固定值避免真随机闪烁） */
    private val seedsSize = FloatArray(maxParticles) { i ->
        (i * 3.7f).let { sin(it) * 0.3f + 0.7f }
    }

    /** 粒子位置缓存（避免每帧分配） */
    private val particleX = FloatArray(maxParticles)
    private val particleY = FloatArray(maxParticles)

    /**
     * 每粒子独立可见性 alpha（0=完全透明，1=完全可见）
     * 通过低通滤波平滑过渡，实现粒子淡入淡出
     */
    private val particleVisibility = FloatArray(maxParticles) { 0f }

    /** 布朗运动历史位置（平滑抖动，避免每帧完全随机跳跃） */
    private val brownianX = FloatArray(maxParticles)
    private val brownianY = FloatArray(maxParticles)
    private var brownianInit = false

    // ========== 颜色缓存（支持平滑过渡） ==========
    private var cachedHue = Float.MAX_VALUE
    private var cachedSat = -1f
    private var cachedLight = -1f
    private var targetColor: Color = Color.White
    private var currentColor: Color = Color.White

    // ========== 预创建颜色列表（避免每帧 120+ listOf 分配） ==========
    private val haloGradientColors = mutableListOf<Color>()
    private val coreGradientColors = mutableListOf<Color>()
    private var particleColorsInit = false

    /**
     * 渲染 L3 粒子环带
     *
     * @param params   能量场参数
     * @param centerX  中心 X
     * @param centerY  中心 Y
     * @param size     画布尺寸
     * @param time     当前时间（秒）
     */
    fun DrawScope.renderParticles(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val count = params.particleCount.coerceIn(0, maxParticles)

        updateColorCache(params)

        val baseDim = minOf(size.width, size.height)
        // 粒子轨道半径：大于 Blob 半径（0.5*blobWidthRatio），确保粒子不被遮挡
        // blobWidthRatio 是 Blob 直径占比，Blob 半径 = baseDim * blobWidthRatio * 0.5
        // 轨道半径 = Blob 半径 * 1.15，确保粒子在 Blob 外缘外侧
        val orbitRadius = baseDim * params.blobWidthRatio * 0.55f
        // 粒子半径
        val particleRadius = baseDim * params.particleSize * 0.04f
        // 时间偏移 × 速度
        val t = time * params.particleSpeed

        // 布朗运动历史位置初始化
        if (!brownianInit) {
            for (i in 0 until maxParticles) {
                brownianX[i] = 0f
                brownianY[i] = 0f
            }
            brownianInit = true
        }

        // ===== 始终计算全部 60 个粒子的位置 =====
        when (params.particleMode) {
            ParticleMode.ORBIT -> computeOrbit(maxParticles, centerX, centerY, orbitRadius, t)
            ParticleMode.ROTATE -> computeRotate(maxParticles, centerX, centerY, orbitRadius, t)
            ParticleMode.CONVERGE -> computeConverge(maxParticles, centerX, centerY, orbitRadius, t)
            ParticleMode.DIVERGE -> computeDiverge(maxParticles, centerX, centerY, orbitRadius, t)
            ParticleMode.BROWNIAN -> computeBrownian(maxParticles, centerX, centerY, orbitRadius, t)
            ParticleMode.FALL -> computeFall(maxParticles, centerX, centerY, orbitRadius, t)
            ParticleMode.RISE -> computeRise(maxParticles, centerX, centerY, orbitRadius, t)
        }

        // ===== 平滑更新每粒子可见性 alpha =====
        // 前 count 个粒子目标 alpha=1，其余=0
        // 低通滤波（系数0.12）让粒子在约8帧完成淡入/淡出，既平滑又不拖当
        val fadeSpeed = 0.12f
        for (i in 0 until maxParticles) {
            val targetVis = if (i < count) 1f else 0f
            particleVisibility[i] += (targetVis - particleVisibility[i]) * fadeSpeed
        }

        // ===== 绘制：只绘制 alpha > 0.01 的粒子 =====
        // 复用预创建颜色列表，避免每帧为每个粒子分配 listOf
        if (!particleColorsInit) {
            repeat(3) { if (haloGradientColors.size <= it) haloGradientColors.add(Color.Transparent) }
            repeat(3) { if (coreGradientColors.size <= it) coreGradientColors.add(Color.Transparent) }
            particleColorsInit = true
        }

        val baseAlpha = params.particleOpacity
        for (i in 0 until maxParticles) {
            val vis = particleVisibility[i]
            if (vis < 0.01f) continue

            val sizeMod = seedsSize[i]
            val radius = particleRadius * sizeMod
            val alphaMod = 0.6f + 0.4f * sin(baseAngles[i] * 3f + t * 2f)
            val px = particleX[i]
            val py = particleY[i]
            val haloR = radius * 3.5f
            val finalAlpha = (baseAlpha * alphaMod * vis).coerceIn(0f, 1f)
            val particleCenter = Offset(px, py)

            // 更新预创建的颜色列表（原地修改，无新分配）
            haloGradientColors[0] = currentColor.copy(alpha = finalAlpha * 0.5f)
            haloGradientColors[1] = currentColor.copy(alpha = finalAlpha * 0.15f)
            haloGradientColors[2] = Color.Transparent
            coreGradientColors[0] = Color.White.copy(alpha = finalAlpha)
            coreGradientColors[1] = currentColor.copy(alpha = finalAlpha * 0.6f)
            coreGradientColors[2] = Color.Transparent

            // 外层光晕
            drawCircle(
                brush = Brush.radialGradient(
                    colors = haloGradientColors,
                    radius = haloR,
                    center = particleCenter
                ),
                radius = haloR,
                center = particleCenter
            )
            // 核心
            drawCircle(
                brush = Brush.radialGradient(
                    colors = coreGradientColors,
                    radius = radius * 0.6f,
                    center = particleCenter
                ),
                radius = radius * 0.6f,
                center = particleCenter
            )
        }
    }

    // ==================== 运动模式实现 ====================

    /**
     * ORBIT：粒子沿圆周轨道匀速运动
     */
    private fun computeOrbit(
        count: Int, cx: Float, cy: Float, r: Float, t: Float
    ) {
        for (i in 0 until count) {
            val angle = baseAngles[i] + t
            particleX[i] = cx + cos(angle) * r
            particleY[i] = cy + sin(angle) * r
        }
    }

    /**
     * ROTATE：粒子整体绕中心旋转（思考感，所有粒子同步旋转）
     * 与 ORBIT 区别：粒子间距角度不变，整体偏转
     */
    private fun computeRotate(
        count: Int, cx: Float, cy: Float, r: Float, t: Float
    ) {
        // 旋转角度 = t（单向匀速旋转）
        val rotation = t * 0.5f
        // 半径做轻微径向呼吸（螺旋感）
        val radialBreath = 1f + 0.1f * sin(t * 0.8f)
        for (i in 0 until count) {
            val angle = baseAngles[i] + rotation
            val rr = r * radialBreath
            particleX[i] = cx + cos(angle) * rr
            particleY[i] = cy + sin(angle) * rr
        }
    }

    /**
     * CONVERGE：粒子向中心流动（识别感）
     * 半径周期性收缩，接近中心时保留最小半径，回到外圈重新开始
     * 同时缓慢旋转，使运动轨迹更明显
     */
    private fun computeConverge(
        count: Int, cx: Float, cy: Float, r: Float, t: Float
    ) {
        for (i in 0 until count) {
            val phase = (baseAngles[i] / TWO_PI + t * 0.5f) % 1f
            val rr = r * (1f - phase * 0.85f) // 从外圈快速向内收缩，保留最小半径
            val angle = baseAngles[i] + t * 0.3f // 同时缓慢旋转
            particleX[i] = cx + cos(angle) * rr
            particleY[i] = cy + sin(angle) * rr
        }
    }

    /**
     * DIVERGE：粒子向外扩散（惊讶/应答）
     * 半径周期性膨胀，到达外圈后从中心重新开始
     * 同时轻微旋转，增加爆发感
     */
    private fun computeDiverge(
        count: Int, cx: Float, cy: Float, r: Float, t: Float
    ) {
        for (i in 0 until count) {
            val phase = (baseAngles[i] / TWO_PI + t * 0.5f) % 1f
            val rr = r * (0.15f + phase * 0.85f) // 从内圈快速向外膨胀
            val angle = baseAngles[i] + t * 0.2f // 轻微旋转增加动态感
            particleX[i] = cx + cos(angle) * rr
            particleY[i] = cy + sin(angle) * rr
        }
    }

    /**
     * BROWNIAN：粒子随机抖动（疑惑）
     * 使用确定性伪随机 + 低通滤波平滑，避免每帧完全跳跃
     */
    private fun computeBrownian(
        count: Int, cx: Float, cy: Float, r: Float, t: Float
    ) {
        val damp = 0.85f // 低通滤波系数
        for (i in 0 until count) {
            // 基础轨道位置
            val angle = baseAngles[i] + t * 0.3f
            val bx = cos(angle) * r
            val by = sin(angle) * r
            // 随机抖动（基于种子 + 时间，确定性伪随机）
            val noiseX = (sin(t * 3f + i * 12.9898f) * 0.5f) * r * 0.3f
            val noiseY = (sin(t * 3f + i * 78.233f) * 0.5f) * r * 0.3f
            // 低通滤波平滑
            brownianX[i] = brownianX[i] * damp + noiseX * (1f - damp)
            brownianY[i] = brownianY[i] * damp + noiseY * (1f - damp)
            particleX[i] = cx + bx + brownianX[i]
            particleY[i] = cy + by + brownianY[i]
        }
    }

    /**
     * FALL：粒子向下飘（悲伤）
     * 从顶部缓慢下落，到达底部后从顶部重新开始
     */
    private fun computeFall(
        count: Int, cx: Float, cy: Float, r: Float, t: Float
    ) {
        for (i in 0 until count) {
            // X 基于基础角度的水平分量（左右散布）
            val xSpread = cos(baseAngles[i]) * r * 0.7f
            // Y 下落位移（周期循环）
            val cycle = (baseAngles[i] / TWO_PI + t * 0.15f) % 1f
            val yOff = (cycle * 2f - 1f) * r // -r ~ +r
            particleX[i] = cx + xSpread + sin(t + i) * r * 0.05f // 轻微横向飘移
            particleY[i] = cy + yOff
        }
    }

    /**
     * RISE：粒子向上飘（开心）
     * 从底部缓慢上升，到达顶部后从底部重新开始
     */
    private fun computeRise(
        count: Int, cx: Float, cy: Float, r: Float, t: Float
    ) {
        for (i in 0 until count) {
            // X 基于基础角度的水平分量
            val xSpread = cos(baseAngles[i]) * r * 0.7f
            // Y 上升位移（周期循环，反向 FALL）
            val cycle = (baseAngles[i] / TWO_PI + t * 0.2f) % 1f
            val yOff = (1f - cycle * 2f) * r // +r ~ -r
            particleX[i] = cx + xSpread + sin(t * 1.5f + i) * r * 0.06f // 轻微横向飘移
            particleY[i] = cy + yOff
        }
    }

    // ==================== 颜色缓存（带平滑过渡） ====================

    private fun updateColorCache(params: EnergyFieldParameters) {
        if (params.primaryHue != cachedHue ||
            params.primarySaturation != cachedSat ||
            params.primaryLightness != cachedLight
        ) {
            cachedHue = params.primaryHue
            cachedSat = params.primarySaturation
            cachedLight = params.primaryLightness
            // 目标粒子色 = 主色相 + 辅色偏移，提亮
            targetColor = hslToColor(
                (params.primaryHue + params.secondaryHueOffset + 360f) % 360f,
                (params.primarySaturation + 0.1f).coerceAtMost(1f),
                (params.primaryLightness + 0.2f).coerceAtMost(0.9f)
            )
        }
        // 颜色低通滤波：currentColor 逐渐逼近 targetColor（每帧15%），避免颜色突变
        currentColor = Color(
            red = currentColor.red + (targetColor.red - currentColor.red) * 0.15f,
            green = currentColor.green + (targetColor.green - currentColor.green) * 0.15f,
            blue = currentColor.blue + (targetColor.blue - currentColor.blue) * 0.15f,
            alpha = 1f
        )
    }

    companion object {
        private val TWO_PI = (2.0 * Math.PI).toFloat()
    }
}
