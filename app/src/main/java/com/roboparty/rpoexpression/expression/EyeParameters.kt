/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

import androidx.compose.ui.graphics.Color

/**
 * 纯眼部参数数据类
 * 所有视觉表达通过这些参数控制，严格限定在眼部区域
 *
 * 参数范围统一为0f-1f，便于强度控制和插值计算
 */
data class EyeParameters(
    // ==================== 眼睑参数 ====================
    /** 眼睑开合度：0=完全闭合，1=完全张开 */
    val eyelidOpenness: Float = 1f,
    /** 上眼睑弯曲度：0=平直，1=最大弧度（下垂/上扬） */
    val upperEyelidCurve: Float = 0.5f,
    /** 下眼睑弯曲度：0=平直，1=最大弧度 */
    val lowerEyelidCurve: Float = 0.3f,

    // ==================== 瞳孔参数 ====================
    /** 瞳孔大小：0=最小，1=最大 */
    val pupilSize: Float = 0.6f,
    /** 瞳孔X轴偏移：-1=最左，0=居中，1=最右 */
    val pupilOffsetX: Float = 0f,
    /** 瞳孔Y轴偏移：-1=最上，0=居中，1=最下 */
    val pupilOffsetY: Float = 0f,
    /** 瞳孔缩放（呼吸/惊讶效果） */
    val pupilScale: Float = 1f,

    // ==================== 眼型参数 ====================
    /** 眼尾角度：-1=下垂（难过），0=水平，1=上扬（开心） */
    val eyeTailAngle: Float = 0f,
    /** 眼睛高度比例：0=最细（眯眼），1=最圆（惊讶） */
    val eyeHeightRatio: Float = 0.7f,
    /** 眼睛宽度比例 */
    val eyeWidthRatio: Float = 1f,
    /** 内眼角弧度 */
    val innerCornerRadius: Float = 0.3f,
    /** 外眼角弧度 */
    val outerCornerRadius: Float = 0.4f,

    // ==================== 眼周光效参数 ====================
    /** 眼周主光颜色 */
    val glowColor: Color = Color(0xFF4FC3F7),
    /** 光效强度：0=无光，1=最亮 */
    val glowIntensity: Float = 0.3f,
    /** 光晕大小 */
    val glowRadius: Float = 0.5f,
    /** 是否闪烁 */
    val glowBlink: Boolean = false,

    // ==================== 动画参数 ====================
    /** 眨眼动画进度：0=无，1=完全闭合中 */
    val blinkProgress: Float = 0f,
    /** 眼神涣散程度：0=聚焦，1=完全涣散 */
    val unfocused: Float = 0f
) {
    companion object {
        /**
         * 线性插值两个眼部参数，用于平滑过渡
         */
        fun lerp(start: EyeParameters, end: EyeParameters, fraction: Float): EyeParameters {
            val t = fraction.coerceIn(0f, 1f)
            return EyeParameters(
                eyelidOpenness = lerpFloat(start.eyelidOpenness, end.eyelidOpenness, t),
                upperEyelidCurve = lerpFloat(start.upperEyelidCurve, end.upperEyelidCurve, t),
                lowerEyelidCurve = lerpFloat(start.lowerEyelidCurve, end.lowerEyelidCurve, t),
                pupilSize = lerpFloat(start.pupilSize, end.pupilSize, t),
                pupilOffsetX = lerpFloat(start.pupilOffsetX, end.pupilOffsetX, t),
                pupilOffsetY = lerpFloat(start.pupilOffsetY, end.pupilOffsetY, t),
                pupilScale = lerpFloat(start.pupilScale, end.pupilScale, t),
                eyeTailAngle = lerpFloat(start.eyeTailAngle, end.eyeTailAngle, t),
                eyeHeightRatio = lerpFloat(start.eyeHeightRatio, end.eyeHeightRatio, t),
                eyeWidthRatio = lerpFloat(start.eyeWidthRatio, end.eyeWidthRatio, t),
                innerCornerRadius = lerpFloat(start.innerCornerRadius, end.innerCornerRadius, t),
                outerCornerRadius = lerpFloat(start.outerCornerRadius, end.outerCornerRadius, t),
                glowColor = lerpColor(start.glowColor, end.glowColor, t),
                glowIntensity = lerpFloat(start.glowIntensity, end.glowIntensity, t),
                glowRadius = lerpFloat(start.glowRadius, end.glowRadius, t),
                glowBlink = if (t < 0.5f) start.glowBlink else end.glowBlink,
                blinkProgress = lerpFloat(start.blinkProgress, end.blinkProgress, t),
                unfocused = lerpFloat(start.unfocused, end.unfocused, t)
            )
        }

        private fun lerpFloat(start: Float, end: Float, fraction: Float): Float {
            return start + (end - start) * fraction
        }

        /**
         * 带缓动函数的浮点插值
         */
        private fun lerpFloat(start: Float, end: Float, fraction: Float, easing: (Float) -> Float): Float {
            return start + (end - start) * easing(fraction.coerceIn(0f, 1f))
        }

        private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
            val t = fraction.coerceIn(0f, 1f)
            return Color(
                red = lerpFloat(start.red, end.red, t),
                green = lerpFloat(start.green, end.green, t),
                blue = lerpFloat(start.blue, end.blue, t),
                alpha = lerpFloat(start.alpha, end.alpha, t)
            )
        }

        private fun lerpColor(start: Color, end: Color, fraction: Float, easing: (Float) -> Float): Color {
            val t = easing(fraction.coerceIn(0f, 1f))
            return Color(
                red = lerpFloat(start.red, end.red, t),
                green = lerpFloat(start.green, end.green, t),
                blue = lerpFloat(start.blue, end.blue, t),
                alpha = lerpFloat(start.alpha, end.alpha, t)
            )
        }

        // ==================== 物理感缓动函数 ====================
        /**
         * 眨眼专用：快闭慢睁曲线
         * 前25%时间快速闭眼，后75%时间缓慢睁眼
         */
        val blinkEasing: (Float) -> Float = { t ->
            if (t < 0.25f) {
                // 闭眼阶段：快速从0到1
                t / 0.25f
            } else {
                // 睁眼阶段：缓慢从1到0，最后完全睁开
                1f - (t - 0.25f) / 0.75f
            }
        }

        /**
         * 柔和回弹缓动：表情切换用，轻微过冲，自然不生硬
         */
        val elasticEasing: (Float) -> Float = { t ->
            // 柔和的先快后慢带轻微过冲，避免强烈弹动导致抖动
            if (t < 0.8f) {
                val t1 = t / 0.8f
                1.02f * (1f - Math.pow(3.0, (-10.0 * t1).toDouble()).toFloat())
            } else {
                val t1 = (t - 0.8f) / 0.2f
                1.02f - 0.02f * t1
            }
        }

        /**
         * 减速缓动：瞳孔移动用，有滞后物理感
         */
        val decelerateEasing: (Float) -> Float = { t ->
            1f - (1f - t) * (1f - t)
        }

        /**
         * 先快后慢缓动：通用过渡
         */
        val fastOutSlowInEasing: (Float) -> Float = { t ->
            t * t * (3f - 2f * t)
        }

        /**
         * 应用强度系数，0-100强度映射到参数变化
         */
        fun applyIntensity(params: EyeParameters, baseParams: EyeParameters, intensity: Int): EyeParameters {
            val t = (intensity / 100f).coerceIn(0f, 1f)
            return lerp(baseParams, params, t)
        }
    }
}

/**
 * 表情风格枚举
 * 支持多种风格：传统代码绘制风格 + SVG 数码风格
 */
enum class ExpressionStyle(val description: String) {
    MALE("男款·丹凤眼风格"),
    FEMALE("女款·杏眼风格"),
    SHAPE_MALE("形状男款·方形Figma设计"),
    SHAPE_FEMALE("形状女款·圆形Figma设计")
}
