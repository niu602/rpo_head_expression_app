/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import com.roboparty.rpoexpression.animation.energy.EnergyFieldMask
import com.roboparty.rpoexpression.animation.energy.EnergyParticlesRenderer
import com.roboparty.rpoexpression.expression.EnergyFieldParameters
import com.roboparty.rpoexpression.expression.ExpressionManager
import kotlin.math.abs
import kotlin.math.sin

/**
 * Rorschach 能量场主渲染器（分层能量场 · 方案E）
 *
 * 三层渲染管线：
 * - L2 流体光场：超椭圆 Blob + 径向渐变 + 边缘发光
 * - L1 能量焦点：径向渐变光晕 + 核心亮点 + 颤动
 * - 效果叠加：扫描线 / 进度条 / 全屏闪（框架预留）
 *
 * 性能优化：所有 Paint、Path 对象预创建为成员变量，零每帧分配。
 */
class RorschachEnergyRenderer : EnergyFieldRenderer {

    // ========== L3 粒子渲染器 ==========
    private val particlesRenderer = EnergyParticlesRenderer()

    // ========== 头部轮廓蒙版 ==========
    val mask = EnergyFieldMask()

    // ========== 零分配缓存对象 ==========
    private val blobPath = Path()
    private val glowPath = Path()

    // ========== 预创建静态颜色列表（避免每帧 listOf 分配） ==========
    private val bgGradientColors = listOf(Color(0xFF0A0A1A), Color(0xFF000000))
    private val blobFillColors = mutableListOf<Color>()
    private val blobGlowColors = mutableListOf<Color>()
    private val coreHaloColors = mutableListOf<Color>()
    private val coreMidColors = mutableListOf<Color>()
    private val coreCenterColors = mutableListOf<Color>()
    private var blobColorsInit = false
    private var coreColorsInit = false

    // ========== 主色 HSL → Color 缓存 ==========
    // 避免每帧重复 HSL→RGB 转换，仅在色相变化时更新
    private var cachedHue = Float.MAX_VALUE
    private var cachedSat = -1f
    private var cachedLight = -1f
    private var primaryColor: Color = Color.Black
    private var coreColor: Color = Color.White

    // ========== 绘制尺寸缓存 ==========
    private var lastSize: Size = Size.Zero
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    // ========== IMU 惯性状态（弹簧-阻尼模型） ==========
    // 目标值（来自 IMU），当前值（带惯性），每帧逼近目标
    private var targetOffsetX = 0f
    private var targetOffsetY = 0f
    private var inertiaOffsetX = 0f
    private var inertiaOffsetY = 0f
    private var targetTiltOffset = 0f   // IMU 驱动的额外倾斜
    private var inertiaTiltOffset = 0f
    private var targetRotation = 0f      // IMU Roll 驱动的整体旋转
    private var inertiaRotation = 0f
    // 摇晃冲击：峰值后指数衰减
    private var shakeImpact = 0f
    // 点头弹跳：峰值后阻尼振荡
    private var nodBounce = 0f

    /**
     * 主渲染方法
     */
    override fun DrawScope.renderField(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float,
        imuPitch: Float,
        imuRoll: Float
    ) {
        // 缓存中心坐标
        if (size != lastSize) {
            lastSize = size
            lastCenterX = size.width / 2f
            lastCenterY = size.height / 2f
        }

        // 更新颜色缓存
        updateColorCache(params)

        // 更新 IMU 惯性
        updateImuInertia(imuPitch, imuRoll, time)

        // IMU 惯性偏移（像素级，叠加到各层中心位置）
        val baseDim = minOf(size.width, size.height)
        val inertiaPxX = inertiaOffsetX * baseDim * 0.15f
        val inertiaPxY = inertiaOffsetY * baseDim * 0.15f

        // ===== 视角补偿：侧面时 Blob 透视压缩 + 饱和度提升 + 焦点偏移 =====
        // 注意：注视方向偏移已由 getEnergyFieldParameters() 中的 corePositionX/Y 处理
        // 此处只做侧面观看的形变/色彩补偿，不重复偏移焦点
        val gazeX = ExpressionManager.getInstance().pupilOffsetX
        val gazeY = ExpressionManager.getInstance().pupilOffsetY
        val gazeMag = kotlin.math.sqrt((gazeX * gazeX + gazeY * gazeY)).coerceAtMost(1f)
        val perspectiveScaleX = (1f - gazeMag * 0.35f).coerceIn(0.65f, 1f)
        val perspectiveScaleY = (1f - gazeMag * 0.18f).coerceIn(0.78f, 1f)
        val satBoost = (gazeMag * 0.25f).coerceIn(0f, 0.25f)
        // 焦点位置随视角整体偏移（模拟虽然头转了但还在看你的感觉）
        val perspectiveShiftX = gazeX * baseDim * 0.08f
        val perspectiveShiftY = gazeY * baseDim * 0.06f

        // ===== 背景始终全屏绘制（蒙版只裁剪能量场内容） =====
        drawBackground(size, centerX, centerY)

        // ===== 能量场内容包在蒙版内 =====
        with(mask) {
            applyMask(size) {
                // ===== 2. L3 粒子环带（最底层，被 L2 遮挡部分自然形成深度感） =====
                if (params.particleCount > 0) {
                    with(particlesRenderer) {
                        renderParticles(params, centerX + inertiaPxX * 0.5f, centerY + inertiaPxY * 0.5f, size, time)
                    }
                }

                // ===== 3. L2 流体光场 =====
                drawBlobField(
                    params, centerX + inertiaPxX, centerY + inertiaPxY, size, time,
                    perspectiveScaleX, perspectiveScaleY, satBoost
                )

                // ===== 4. L1 能量焦点 =====
                drawCoreFocus(
                    params, centerX + inertiaPxX * 0.7f + perspectiveShiftX,
                    centerY + inertiaPxY * 0.7f + perspectiveShiftY, size, time
                )

                // ===== 5. 效果叠加层 =====
                drawEffectOverlays(params, centerX, centerY, size, time)
            }
        }
    }

    // ==================== 背景层 ====================

    /**
     * 深色背景：纯黑底 + 中心微弱径向渐变（极深蓝紫色，暗示空间深度）
     */
    private fun DrawScope.drawBackground(size: Size, centerX: Float, centerY: Float) {
        // 纯黑底
        drawRect(
            color = Color.Black,
            size = size
        )
        // 中心微弱径向渐变（极深蓝紫色 → 纯黑）
        val maxRadius = maxOf(size.width, size.height) * 0.7f
        drawCircle(
            brush = Brush.radialGradient(
                colors = bgGradientColors,
                radius = maxRadius,
                center = Offset(centerX, centerY)
            ),
            radius = maxRadius,
            center = Offset(centerX, centerY)
        )
    }

    // ==================== L2 流体光场 ====================

    /**
     * L2 流体光场：参数化 Blob + 径向渐变填充 + 多层边缘发光
     * @param perspectiveScaleX 视角补偿横向缩放（1=正面，<1=侧面压缩）
     * @param satBoost 饱和度补偿（侧面提升）
     */
    private fun DrawScope.drawBlobField(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float,
        perspectiveScaleX: Float = 1f,
        perspectiveScaleY: Float = 1f,
        satBoost: Float = 0f
    ) {
        // 基准尺寸（占屏幕短边的比例）
        val baseDim = minOf(size.width, size.height)

        // 脉动效果：随时间周期性膨胀收缩
        val pulse = 1f + params.blobPulseAmp * sin(time * params.blobPulseFreq * 2f * PI_FLOAT)

        // Blob 尺寸（应用视角压缩）
        val blobW = baseDim * params.blobWidthRatio * pulse * perspectiveScaleX
        val blobH = baseDim * params.blobHeightRatio * pulse * perspectiveScaleY

        // 颜色饱和度补偿
        val effectivePrimary = if (satBoost > 0f) {
            adjustSaturation(primaryColor, satBoost)
        } else {
            primaryColor
        }

        // 计算 Blob 路径
        // IMU 惯性叠加到 Blob 倾斜
        val effectiveTilt = (params.blobTilt + inertiaTiltOffset).coerceIn(-1f, 1f)
        // 摇晃时增加扰动幅度（液团被甩动的不规则感）
        val effectiveDistortion = (params.blobDistortion + shakeImpact * 0.15f).coerceIn(0f, 1f)

        BlobCalculator.calculateBlobPath(
            path = blobPath,
            centerX = centerX,
            centerY = centerY,
            width = blobW,
            height = blobH,
            exponent = params.blobExponent,
            distortion = effectiveDistortion,
            distortionSpeed = params.blobDistortionSpeed,
            tilt = effectiveTilt,
            time = time
        )

        // ----- 填充：径向渐变，关键：渐变半径 > Blob 半径，确保边缘无硬轮廓 -----
        // 渐变从中心到 blobRadius 的 1.3 倍，使边缘 alpha 平滑过渡到 0
        val blobRadius = maxOf(blobW, blobH) * 0.5f
        val gradientRadius = blobRadius * 1.3f
        // 复用预创建的颜色列表，避免每帧 listOf 分配
        if (!blobColorsInit || blobFillColors.size != 5) {
            repeat(5) { if (blobFillColors.size <= it) blobFillColors.add(Color.Transparent) }
            repeat(4) { if (blobGlowColors.size <= it) blobGlowColors.add(Color.Transparent) }
            blobColorsInit = true
        }
        blobFillColors[0] = effectivePrimary.copy(alpha = 0.85f)
        blobFillColors[1] = effectivePrimary.copy(alpha = 0.5f)
        blobFillColors[2] = effectivePrimary.copy(alpha = 0.2f)
        blobFillColors[3] = effectivePrimary.copy(alpha = 0.03f)
        blobFillColors[4] = Color.Transparent
        drawPath(
            path = blobPath,
            brush = Brush.radialGradient(
                colors = blobFillColors,
                radius = gradientRadius,
                center = Offset(centerX, centerY)
            ),
            style = Fill
        )

        // 不再画 Stroke 描边——描边会暴露 Blob 轮廓路径，产生"机械感"
        // 改用第二层更大的 Blob 填充，以极低 alpha 形成自然外发光
        BlobCalculator.calculateBlobPath(
            path = glowPath,
            centerX = centerX,
            centerY = centerY,
            width = blobW * 1.3f,
            height = blobH * 1.3f,
            exponent = params.blobExponent,
            distortion = effectiveDistortion * 0.7f,
            distortionSpeed = params.blobDistortionSpeed * 0.5f,
            tilt = effectiveTilt,
            time = time * 0.8f
        )
        blobGlowColors[0] = Color.Transparent
        blobGlowColors[1] = effectivePrimary.copy(alpha = 0.06f)
        blobGlowColors[2] = effectivePrimary.copy(alpha = 0.03f)
        blobGlowColors[3] = Color.Transparent
        drawPath(
            path = glowPath,
            brush = Brush.radialGradient(
                colors = blobGlowColors,
                radius = blobRadius * 1.5f,
                center = Offset(centerX, centerY)
            ),
            style = Fill
        )
    }

    // ==================== L1 能量焦点 ====================

    /**
     * L1 能量焦点：径向渐变光晕 + 核心亮点 + 颤动
     */
    private fun DrawScope.drawCoreFocus(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        val baseDim = minOf(size.width, size.height)

        // 颤动偏移
        val tremorX = if (params.coreTremorAmp > 0f) {
            params.coreTremorAmp * baseDim * 0.02f * sin(time * params.coreTremorFreq * 2f * PI_FLOAT)
        } else 0f
        val tremorY = if (params.coreTremorAmp > 0f) {
            params.coreTremorAmp * baseDim * 0.02f * sin(time * params.coreTremorFreq * 2f * PI_FLOAT + 1.5f)
        } else 0f

        // 焦点位置（归一化偏移 → 像素偏移，系数增大使跟随更明显）
        val focusX = centerX + params.corePositionX * baseDim * 0.30f + tremorX
        val focusY = centerY + params.corePositionY * baseDim * 0.30f + tremorY

        // 呼吸脉动亮度调制
        val breathBrightness = params.coreBrightness * (0.85f + 0.15f * sin(time * 0.5f * PI_FLOAT))
        val clampedBrightness = breathBrightness.coerceIn(0f, 1f)

        // 焦点半径（缩小系数，让焦点更精细更明亮，与外围 Blob 区分）
        val coreRadius = baseDim * params.coreSize * 0.22f
        // 光晕半径（适中范围，避免压过 Blob 层）
        val haloRadius = coreRadius * 3.5f

        // 单焦点或双焦点（复用预创建颜色列表）
        if (!coreColorsInit) {
            repeat(3) { if (coreHaloColors.size <= it) coreHaloColors.add(Color.Transparent) }
            repeat(3) { if (coreMidColors.size <= it) coreMidColors.add(Color.Transparent) }
            repeat(3) { if (coreCenterColors.size <= it) coreCenterColors.add(Color.Transparent) }
            coreColorsInit = true
        }

        coreHaloColors[0] = coreColor.copy(alpha = 0.25f * clampedBrightness)
        coreHaloColors[1] = coreColor.copy(alpha = 0.06f * clampedBrightness)
        coreHaloColors[2] = Color.Transparent

        coreMidColors[0] = coreColor.copy(alpha = 0.7f * clampedBrightness)
        coreMidColors[1] = coreColor.copy(alpha = 0.2f * clampedBrightness)
        coreMidColors[2] = Color.Transparent

        coreCenterColors[0] = Color.White.copy(alpha = clampedBrightness)
        coreCenterColors[1] = coreColor.copy(alpha = clampedBrightness * 0.8f)
        coreCenterColors[2] = coreColor.copy(alpha = 0f)

        if (params.coreCount == 2) {
            val sep = baseDim * 0.15f
            drawSingleCore(Offset(focusX - sep, focusY), haloRadius, coreRadius,
                coreHaloColors, coreMidColors, coreCenterColors)
            drawSingleCore(Offset(focusX + sep, focusY), haloRadius, coreRadius,
                coreHaloColors, coreMidColors, coreCenterColors)
        } else {
            drawSingleCore(Offset(focusX, focusY), haloRadius, coreRadius,
                coreHaloColors, coreMidColors, coreCenterColors)
        }
    }

    /**
     * 绘制单个能量焦点（复用预创建颜色列表）
     */
    private fun DrawScope.drawSingleCore(
        pos: Offset,
        haloRadius: Float,
        coreRadius: Float,
        haloColors: List<Color>,
        midColors: List<Color>,
        centerColors: List<Color>
    ) {
        // ----- 最外层大气光晕 -----
        drawCircle(
            brush = Brush.radialGradient(
                colors = haloColors,
                radius = haloRadius,
                center = pos
            ),
            radius = haloRadius,
            center = pos
        )
        // ----- 中层光晕 -----
        drawCircle(
            brush = Brush.radialGradient(
                colors = midColors,
                radius = coreRadius * 2.5f,
                center = pos
            ),
            radius = coreRadius * 2.5f,
            center = pos
        )
        // ----- 核心 -----
        drawCircle(
            brush = Brush.radialGradient(
                colors = centerColors,
                radius = coreRadius,
                center = pos
            ),
            radius = coreRadius,
            center = pos
        )
    }

    // ==================== 效果叠加层 ====================

    /**
     * 效果叠加层：扫描线 / 进度条 / 全屏闪
     * 当前为框架预留，后续按需填充实现
     */
    private fun DrawScope.drawEffectOverlays(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        // ----- 全屏闪烁（告警/应答） -----
        if (params.flashIntensity > 0.01f) {
            drawRect(
                color = primaryColor.copy(alpha = params.flashIntensity * 0.4f),
                size = size
            )
        }

        // ----- 水平扫描线（识别中） -----
        if (params.scanLineActive) {
            val scanY = centerY + (sin(time * 2f) * size.height * 0.2f)
            drawRect(
                color = primaryColor.copy(alpha = 0.25f),
                topLeft = Offset(0f, scanY - 1.5f),
                size = Size(size.width, 3f)
            )
        }

        // ----- 进度条（升级中） -----
        if (params.progressValue > 0.01f) {
            val barW = size.width * 0.4f
            val barH = size.height * 0.012f
            val barY = centerY + size.height * 0.3f
            // 背景条
            drawRect(
                color = primaryColor.copy(alpha = 0.2f),
                topLeft = Offset(centerX - barW / 2f, barY),
                size = Size(barW, barH)
            )
            // 进度填充
            drawRect(
                color = primaryColor.copy(alpha = 0.8f),
                topLeft = Offset(centerX - barW / 2f, barY),
                size = Size(barW * params.progressValue, barH)
            )
        }
    }

    // ==================== IMU 惯性 ====================

    /**
     * 每帧更新 IMU 惯性状态
     *
     * - Pitch → 焦点 Y 偏移 + Blob 倾斜偏移
     * - Roll → Blob 整体旋转
     * - SHAKE → 惯性甩动（通过大幅 roll 变化间接触发）
     * - NOD → 焦点快速上下弹跳（通过大幅 pitch 变化间接触发）
     *
     * 物理模型：一阶低通滤波 + 阻尼衰减
     * stiffness=0.15, damping=0.85
     */
    private fun updateImuInertia(imuPitch: Float, imuRoll: Float, time: Float) {
        // ===== 1. Pitch → Y 偏移 + 倾斜 =====
        // pitch 正值=抬头，负值=低头（弧度）
        targetOffsetY = imuPitch.coerceIn(-1f, 1f)
        targetTiltOffset = imuPitch.coerceIn(-1f, 1f) * 0.3f

        // ===== 2. Roll → X 偏移 + 整体旋转 =====
        // roll 正值=右倾，负值=左倾
        targetOffsetX = imuRoll.coerceIn(-1f, 1f) * 0.5f
        targetRotation = imuRoll.coerceIn(-1f, 1f) * 15f // 最大旋转15度

        // ===== 3. 检测剧烈摇晃（roll 绝对值 > 0.5 → 触发摇晃甩动） =====
        val rollAbs = abs(imuRoll)
        if (rollAbs > 0.5f && shakeImpact < rollAbs) {
            shakeImpact = rollAbs
        }

        // ===== 4. 检测点头（pitch 绝对值突变 → 触发弹跳） =====
        val pitchAbs = abs(imuPitch)
        if (pitchAbs > 0.6f && nodBounce < pitchAbs * 0.5f) {
            nodBounce = pitchAbs * 0.5f
        }

        // ===== 4. 摇晃冲击 → 随机方向甩动 =====
        if (shakeImpact > 0.01f) {
            // 摇晃产生与 roll 方向相反的横向甩动
            targetOffsetX += -imuRoll * shakeImpact * 0.8f
            shakeImpact *= 0.90f
        } else {
            shakeImpact = 0f
        }

        // ===== 5. 点头 → 焦点弹跳 =====
        if (nodBounce > 0.01f) {
            // 阻尼振荡：sin 随时间衰减
            targetOffsetY += sin(time * 8f) * nodBounce * 0.5f
            nodBounce *= 0.88f
        } else {
            nodBounce = 0f
        }

        // ===== 6. 弹簧-阻尼滤波（一阶低通 + 阻尼衰减） =====
        val stiffness = 0.15f
        val damping = 0.85f
        inertiaOffsetX += (targetOffsetX - inertiaOffsetX) * stiffness
        inertiaOffsetX *= damping
        inertiaOffsetY += (targetOffsetY - inertiaOffsetY) * stiffness
        inertiaOffsetY *= damping
        inertiaTiltOffset += (targetTiltOffset - inertiaTiltOffset) * stiffness
        inertiaTiltOffset *= damping
        inertiaRotation += (targetRotation - inertiaRotation) * stiffness
        inertiaRotation *= damping
    }

    // ==================== 颜色缓存 ====================

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
            primaryColor = hslToColor(
                params.primaryHue,
                params.primarySaturation,
                params.primaryLightness
            )
            // 辅色 = 主色相偏移后，大幅提亮饱和度（用于内核焦点，与外围 Blob 区分）
            coreColor = hslToColor(
                (params.primaryHue + params.secondaryHueOffset + 360f) % 360f,
                (params.primarySaturation + 0.2f).coerceAtMost(1f),
                (params.primaryLightness + 0.35f).coerceAtMost(0.92f)
            )
        }
    }

    /**
     * 简单饱和度提升：将 RGB 各通道远离灰色中值
     */
    private fun adjustSaturation(color: Color, boost: Float): Color {
        val gray = (color.red + color.green + color.blue) / 3f
        return Color(
            red = (color.red + (color.red - gray) * boost).coerceIn(0f, 1f),
            green = (color.green + (color.green - gray) * boost).coerceIn(0f, 1f),
            blue = (color.blue + (color.blue - gray) * boost).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }

    companion object {
        private val PI_FLOAT = Math.PI.toFloat()

        /**
         * HSL → Color 转换
         * @param h 色相 0~360
         * @param s 饱和度 0~1
         * @param l 亮度 0~1
         */
        fun hslToColor(h: Float, s: Float, l: Float): Color {
            if (s <= 0f) {
                // 灰度
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
