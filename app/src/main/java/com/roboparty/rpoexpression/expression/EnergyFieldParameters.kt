/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 粒子运动模式枚举（L3 粒子环带层）
 */
enum class ParticleMode {
    ORBIT,      // 轨道运动（圆周）
    ROTATE,     // 旋转（带角速度的螺旋）
    CONVERGE,   // 汇聚（向中心收缩）
    DIVERGE,    // 发散（从中心向外）
    BROWNIAN,   // 布朗运动（随机混乱）
    FALL,       // 下落（重力感）
    RISE        // 上升（飘升感）
}

/**
 * 能量场参数数据类（分层能量场 · 方案E）
 *
 * 三层结构：
 * - L1 内核：能量焦点（注意力层）
 * - L2 中层：流体光场（情绪强度层）
 * - L3 外层：粒子环带（情绪标签层）
 *
 * 参数范围统一为 0f~1f（位置类为 -1f~1f），便于强度控制和插值
 */
data class EnergyFieldParameters(
    // ==================== L1 内核层 ====================
    /** 焦点数量（1 或 2） */
    val coreCount: Int = 1,
    /** 焦点 X 偏移（-1~1 归一化） */
    val corePositionX: Float = 0f,
    /** 焦点 Y 偏移（-1~1 归一化） */
    val corePositionY: Float = 0f,
    /** 焦点大小（0~1） */
    val coreSize: Float = 0.3f,
    /** 焦点亮度（0~1） */
    val coreBrightness: Float = 0.8f,
    /** 颤动频率（Hz） */
    val coreTremorFreq: Float = 0f,
    /** 颤动幅度 */
    val coreTremorAmp: Float = 0f,

    // ==================== L2 流体层 ====================
    /** 超椭圆指数 n（2~6，2=正圆，6=方圆之间） */
    val blobExponent: Float = 2.5f,
    /** 宽度比（0~1） */
    val blobWidthRatio: Float = 0.6f,
    /** 高度比（0~1） */
    val blobHeightRatio: Float = 0.6f,
    /** 边缘扰动幅度（0~1） */
    val blobDistortion: Float = 0.1f,
    /** 扰动速度 */
    val blobDistortionSpeed: Float = 0.5f,
    /** 倾斜角（-1~1，正=上扬，负=下垂） */
    val blobTilt: Float = 0f,
    /** 脉动频率 */
    val blobPulseFreq: Float = 0.33f,
    /** 脉动幅度 */
    val blobPulseAmp: Float = 0.05f,
    /** 内部流动纹理速度 */
    val flowTextureSpeed: Float = 0.3f,
    /** 流动纹理可见度（0~1） */
    val flowTextureOpacity: Float = 0.3f,

    // ==================== L3 粒子层 ====================
    /** 粒子数量（0~60） */
    val particleCount: Int = 0,
    /** 运动模式 */
    val particleMode: ParticleMode = ParticleMode.ORBIT,
    /** 运动速度 */
    val particleSpeed: Float = 0.5f,
    /** 粒子大小 */
    val particleSize: Float = 0.3f,
    /** 粒子透明度 */
    val particleOpacity: Float = 0.6f,

    // ==================== 颜色（全局 HSL） ====================
    /** 主色相（HSL 的 H，0~360） */
    val primaryHue: Float = 200f,
    /** 饱和度（0~1） */
    val primarySaturation: Float = 0.6f,
    /** 亮度（0~1） */
    val primaryLightness: Float = 0.48f,
    /** 辅色色相偏移（用于内核/粒子） */
    val secondaryHueOffset: Float = 20f,

    // ==================== 效果叠加 ====================
    /** 水平扫描线（识别中） */
    val scanLineActive: Boolean = false,
    /** 进度值（升级中，0~1） */
    val progressValue: Float = 0f,
    /** 全屏闪烁强度（0~1） */
    val flashIntensity: Float = 0f
) {
    /**
     * 线性插值：将当前参数向 [target] 插值，进度为 [progress]（0~1）。
     * 用于表情过渡动画。
     */
    fun lerp(target: EnergyFieldParameters, progress: Float): EnergyFieldParameters {
        val t = progress.coerceIn(0f, 1f)
        return EnergyFieldParameters(
            coreCount = if (t < 0.5f) this.coreCount else target.coreCount,
            corePositionX = lerpF(this.corePositionX, target.corePositionX, t),
            corePositionY = lerpF(this.corePositionY, target.corePositionY, t),
            coreSize = lerpF(this.coreSize, target.coreSize, t),
            coreBrightness = lerpF(this.coreBrightness, target.coreBrightness, t),
            coreTremorFreq = lerpF(this.coreTremorFreq, target.coreTremorFreq, t),
            coreTremorAmp = lerpF(this.coreTremorAmp, target.coreTremorAmp, t),
            blobExponent = lerpF(this.blobExponent, target.blobExponent, t),
            blobWidthRatio = lerpF(this.blobWidthRatio, target.blobWidthRatio, t),
            blobHeightRatio = lerpF(this.blobHeightRatio, target.blobHeightRatio, t),
            blobDistortion = lerpF(this.blobDistortion, target.blobDistortion, t),
            blobDistortionSpeed = lerpF(this.blobDistortionSpeed, target.blobDistortionSpeed, t),
            blobTilt = lerpF(this.blobTilt, target.blobTilt, t),
            blobPulseFreq = lerpF(this.blobPulseFreq, target.blobPulseFreq, t),
            blobPulseAmp = lerpF(this.blobPulseAmp, target.blobPulseAmp, t),
            flowTextureSpeed = lerpF(this.flowTextureSpeed, target.flowTextureSpeed, t),
            flowTextureOpacity = lerpF(this.flowTextureOpacity, target.flowTextureOpacity, t),
            particleCount = lerpF(this.particleCount.toFloat(), target.particleCount.toFloat(), t).toInt().coerceIn(0, 60),
            particleMode = if (t < 0.5f) this.particleMode else target.particleMode,
            particleSpeed = lerpF(this.particleSpeed, target.particleSpeed, t),
            particleSize = lerpF(this.particleSize, target.particleSize, t),
            particleOpacity = lerpF(this.particleOpacity, target.particleOpacity, t),
            primaryHue = lerpHue(this.primaryHue, target.primaryHue, t),
            primarySaturation = lerpF(this.primarySaturation, target.primarySaturation, t),
            primaryLightness = lerpF(this.primaryLightness, target.primaryLightness, t),
            secondaryHueOffset = lerpF(this.secondaryHueOffset, target.secondaryHueOffset, t),
            scanLineActive = if (t < 0.5f) this.scanLineActive else target.scanLineActive,
            progressValue = lerpF(this.progressValue, target.progressValue, t),
            flashIntensity = lerpF(this.flashIntensity, target.flashIntensity, t)
        )
    }

    private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** 色相插值（沿最短弧线，0~360 环形空间） */
    private fun lerpHue(h1: Float, h2: Float, t: Float): Float {
        val diff = ((h2 - h1 + 540f) % 360f) - 180f
        return (h1 + diff * t + 360f) % 360f
    }
}
