/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 能量场参数映射器
 * 为每种表情类型定义对应的能量场参数（分层能量场 · 方案E §2.2）
 *
 * 与现有 EyeParameterMapper 并行，复用相同的 ExpressionType 枚举。
 * 所有参数预加载缓存，避免每帧创建新对象。
 */
object EnergyFieldParameterMapper {

    /** 预加载缓存：18 种表情的能量场参数在初始化时一次性创建 */
    private val paramsCache: Map<ExpressionType, EnergyFieldParameters>

    init {
        paramsCache = mapOf(
            // 功能类
            ExpressionType.IDLE to idleParams(),
            ExpressionType.BLINK to blinkParams(),
            ExpressionType.SPEAKING to speakingParams(),
            ExpressionType.THINKING to thinkingParams(),
            ExpressionType.RECOGNIZING to recognizingParams(),
            ExpressionType.RESPONSE to responseParams(),
            // 情绪类
            ExpressionType.HAPPY to happyParams(),
            ExpressionType.CONFUSED to confusedParams(),
            ExpressionType.SURPRISED to surprisedParams(),
            ExpressionType.SAD to sadParams(),
            ExpressionType.ANGRY to angryParams(),
            ExpressionType.SLEEPY to sleepyParams(),
            ExpressionType.SHY to shyParams(),
            // 系统状态类
            ExpressionType.LOW_BATTERY to lowBatteryParams(),
            ExpressionType.CHARGING to chargingParams(),
            ExpressionType.ALERT to alertParams(),
            ExpressionType.SLEEPING to sleepingParams(),
            ExpressionType.UPDATING to updatingParams()
        )
    }

    /**
     * 获取指定表情类型的基础能量场参数（从缓存读取，零分配）
     */
    fun map(type: ExpressionType): EnergyFieldParameters {
        return paramsCache[type] ?: idleParams()
    }

    // ==================== 功能类参数实现 ====================

    /**
     * 待机：居中、冰蓝、缓慢呼吸
     */
    private fun idleParams(): EnergyFieldParameters = EnergyFieldParameters(
        // L1
        coreCount = 1,
        corePositionX = 0f,
        corePositionY = 0f,
        coreSize = 0.32f,
        coreBrightness = 1.0f,
        // L2
        blobExponent = 2f, // 标准正圆，无棱角
        blobWidthRatio = 0.82f, // 放大，占短边82%
        blobHeightRatio = 0.82f,
        blobDistortion = 0.08f,
        blobDistortionSpeed = 0.3f,
        blobTilt = 0f,
        blobPulseFreq = 0.33f,   // 3s 周期
        blobPulseAmp = 0.05f,
        // L3
        particleCount = 16,
        particleMode = ParticleMode.ORBIT,
        particleSpeed = 0.4f,
        particleSize = 0.35f,
        particleOpacity = 0.5f,
        // 颜色：科技蓝
        primaryHue = 200f,
        primarySaturation = 0.6f,
        primaryLightness = 0.5f,
        secondaryHueOffset = 40f
    )

    /**
     * 眨眼：焦点快速收缩消失再恢复（由 Renderer 内部动画处理，此处给收缩态）
     */
    private fun blinkParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.05f,
        coreBrightness = 0.1f,
        blobExponent = 2.5f,
        blobWidthRatio = 0.72f,
        blobHeightRatio = 0.22f,   // 纵向压扁
        blobDistortion = 0f,
        blobPulseFreq = 0f,
        blobPulseAmp = 0f,
        particleCount = 0,
        primaryHue = 200f,
        primarySaturation = 0.5f,
        primaryLightness = 0.4f,
        secondaryHueOffset = 40f
    )

    /**
     * 说话：焦点微颤、亮青、Blob 随节奏脉动
     */
    private fun speakingParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.32f,
        coreBrightness = 1.0f,
        coreTremorFreq = 6f,
        coreTremorAmp = 0.15f,
        blobExponent = 2f, // 标准正圆
        blobWidthRatio = 0.80f,
        blobHeightRatio = 0.80f,
        blobDistortion = 0.06f,
        blobDistortionSpeed = 0.4f,
        blobPulseFreq = 1.5f,     // 语速节奏感
        blobPulseAmp = 0.07f,
        particleCount = 12,
        particleMode = ParticleMode.ORBIT,
        particleSpeed = 0.5f,
        particleSize = 0.35f,
        particleOpacity = 0.55f,
        primaryHue = 195f,
        primarySaturation = 0.55f,
        primaryLightness = 0.52f,
        secondaryHueOffset = 40f
    )

    /**
     * 思考：焦点游走、深紫、粒子缓慢旋转
     * （色调从金色改为深紫，与开心/应答的暖金色拉开距离）
     */
    private fun thinkingParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.2f,
        coreBrightness = 0.7f,
        coreTremorFreq = 0.5f,    // 缓慢游走
        coreTremorAmp = 0.4f,
        blobExponent = 2.8f,
        blobWidthRatio = 0.72f,
        blobHeightRatio = 0.75f,
        blobDistortion = 0.1f,
        blobDistortionSpeed = 0.5f,
        blobPulseFreq = 0.5f,
        blobPulseAmp = 0.05f,
        particleCount = 16,
        particleMode = ParticleMode.ROTATE,
        particleSpeed = 0.35f,     // 缓慢旋转（沉思感）
        particleSize = 0.32f,
        particleOpacity = 0.5f,
        primaryHue = 265f,         // 深紫，与识别紫(280°)区分
        primarySaturation = 0.55f,
        primaryLightness = 0.45f,
        secondaryHueOffset = 45f
    )

    /**
     * 识别中：焦点高亮聚焦、扫描紫、粒子汇聚
     */
    private fun recognizingParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.3f,
        coreBrightness = 1.0f,
        coreTremorFreq = 8f,
        coreTremorAmp = 0.08f,
        blobExponent = 2f, // 标准正圆
        blobWidthRatio = 0.75f,
        blobHeightRatio = 0.75f,
        blobDistortion = 0.05f,
        blobDistortionSpeed = 0.3f,
        blobPulseFreq = 1f,
        blobPulseAmp = 0.04f,
        particleCount = 20,
        particleMode = ParticleMode.CONVERGE,
        particleSpeed = 0.8f,
        particleSize = 0.3f,
        particleOpacity = 0.65f,
        scanLineActive = true,
        primaryHue = 280f,
        primarySaturation = 0.7f,
        primaryLightness = 0.52f,
        secondaryHueOffset = 40f
    )

    /**
     * 应答反馈：焦点明亮闪烁、应答金、粒子放射后回收
     */
    private fun responseParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.26f,
        coreBrightness = 1f,
        blobExponent = 2.5f,
        blobWidthRatio = 0.82f,
        blobHeightRatio = 0.82f,
        blobDistortion = 0.08f,
        blobDistortionSpeed = 0.5f,
        blobPulseFreq = 2f,
        blobPulseAmp = 0.1f,
        particleCount = 16,
        particleMode = ParticleMode.DIVERGE,
        particleSpeed = 1.0f,
        particleSize = 0.35f,
        particleOpacity = 0.7f,
        flashIntensity = 0.15f,
        primaryHue = 55f,
        primarySaturation = 0.85f,
        primaryLightness = 0.62f,
        secondaryHueOffset = 45f
    )

    // ==================== 情绪类参数实现 ====================

    /**
     * 开心：焦点上移、暖金、Blob 上扬膨胀、粒子上升
     */
    private fun happyParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        corePositionY = -0.1f,    // 焦点稍微上移
        coreSize = 0.35f,
        coreBrightness = 1.0f,
        blobExponent = 2f, // 标准正圆
        blobWidthRatio = 0.88f,
        blobHeightRatio = 0.88f,
        blobDistortion = 0.12f,
        blobDistortionSpeed = 0.6f,
        blobTilt = 0.3f,          // 轻微上扬
        blobPulseFreq = 1f,
        blobPulseAmp = 0.1f,
        particleCount = 20,
        particleMode = ParticleMode.RISE,
        particleSpeed = 0.8f,
        particleSize = 0.38f,
        particleOpacity = 0.75f,
        primaryHue = 50f,         // 明亮暖金
        primarySaturation = 0.9f,
        primaryLightness = 0.63f,
        secondaryHueOffset = 50f
    )

    /**
     * 疑惑：焦点偏移抖动、暖橙红、Blob 不规则扭曲、粒子布朗运动
     * （色相从30°下移至15°，与开心金(50°)拉开35°差距）
     */
    private fun confusedParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        corePositionX = 0.15f,
        coreSize = 0.2f,
        coreBrightness = 0.7f,
        coreTremorFreq = 3f,
        coreTremorAmp = 0.25f,
        blobExponent = 3f,
        blobWidthRatio = 0.75f,
        blobHeightRatio = 0.78f,
        blobDistortion = 0.28f,   // 不规则扭曲（增强）
        blobDistortionSpeed = 1.0f,
        blobPulseFreq = 0.8f,
        blobPulseAmp = 0.06f,
        particleCount = 16,
        particleMode = ParticleMode.BROWNIAN,
        particleSpeed = 0.7f,
        particleSize = 0.32f,
        particleOpacity = 0.65f,
        primaryHue = 15f,         // 偏红橙，与开心金拉开距离
        primarySaturation = 0.8f,
        primaryLightness = 0.45f,
        secondaryHueOffset = 45f
    )

    /**
     * 惊讶：焦点急速放大、亮青绿、Blob 瞬间膨胀到最大
     * （色相从180°调至165°，更偏翠绿，区分度更高）
     */
    private fun surprisedParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.45f,         // 放大但不再过大
        coreBrightness = 1f,
        blobExponent = 2f, // 标准正圆
        blobWidthRatio = 0.92f,
        blobHeightRatio = 0.92f,
        blobDistortion = 0.08f,
        blobDistortionSpeed = 0.4f,
        blobPulseFreq = 0.5f,     // 添加脉动，增强动感
        blobPulseAmp = 0.06f,
        particleCount = 24,
        particleMode = ParticleMode.DIVERGE,
        particleSpeed = 1.5f,     // 爆发式
        particleSize = 0.4f,
        particleOpacity = 0.8f,
        primaryHue = 165f,        // 翠青，鲜艳醒目
        primarySaturation = 0.9f,
        primaryLightness = 0.62f,
        secondaryHueOffset = 45f
    )

    /**
     * 难过：焦点下移变暗、灰蓝、Blob 下垂收缩
     */
    private fun sadParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        corePositionY = 0.25f,    // 焦点下沉
        coreSize = 0.18f,
        coreBrightness = 0.4f,
        blobExponent = 2.8f,
        blobWidthRatio = 0.68f,   // 收缩（但整体放大）
        blobHeightRatio = 0.70f,
        blobDistortion = 0.06f,
        blobDistortionSpeed = 0.2f,
        blobTilt = -0.5f,         // 下垂
        blobPulseFreq = 0.2f,
        blobPulseAmp = 0.03f,
        particleCount = 12,
        particleMode = ParticleMode.FALL,
        particleSpeed = 0.2f,
        particleSize = 0.28f,
        particleOpacity = 0.4f,
        primaryHue = 220f,
        primarySaturation = 0.4f,
        primaryLightness = 0.35f,
        secondaryHueOffset = 35f
    )

    /**
     * 生气：焦点收缩高频颤动、红色、Blob 上端尖锐化、高频脉动
     */
    private fun angryParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.15f,         // 瞳孔收缩
        coreBrightness = 0.9f,
        coreTremorFreq = 12f,     // 高频颤动
        coreTremorAmp = 0.3f,
        blobExponent = 4.5f,      // 尖锐化（棱角）
        blobWidthRatio = 0.78f,
        blobHeightRatio = 0.80f,
        blobDistortion = 0.2f,
        blobDistortionSpeed = 1.2f,
        blobTilt = 0.1f,
        blobPulseFreq = 4f,       // 高频脉动
        blobPulseAmp = 0.1f,
        particleCount = 20,
        particleMode = ParticleMode.BROWNIAN,
        particleSpeed = 1.3f,
        particleSize = 0.32f,
        particleOpacity = 0.65f,
        flashIntensity = 0.08f,   // 微弱闪烁区分于疑惑
        primaryHue = 5f,
        primarySaturation = 0.95f,
        primaryLightness = 0.45f,
        secondaryHueOffset = 30f
    )

    /**
     * 困倦：焦点缓慢下沉变暗、暗紫、极慢呼吸
     */
    private fun sleepyParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        corePositionY = 0.15f,
        coreSize = 0.18f,
        coreBrightness = 0.45f,
        blobExponent = 2.8f,
        blobWidthRatio = 0.72f,
        blobHeightRatio = 0.55f,  // 纵向压扁
        blobDistortion = 0.04f,
        blobDistortionSpeed = 0.15f,
        blobPulseFreq = 0.2f,     // 5s 周期
        blobPulseAmp = 0.03f,
        particleCount = 6,
        particleMode = ParticleMode.ORBIT,
        particleSpeed = 0.1f,     // 几乎静止
        particleSize = 0.25f,
        particleOpacity = 0.25f,
        primaryHue = 230f,
        primarySaturation = 0.35f,
        primaryLightness = 0.28f,
        secondaryHueOffset = 30f
    )

    /**
     * 害羞：焦点偏移亮度降低、粉色、Blob 略微收缩
     */
    private fun shyParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        corePositionX = -0.1f,
        coreSize = 0.18f,
        coreBrightness = 0.55f,
        blobExponent = 2.5f,
        blobWidthRatio = 0.70f,
        blobHeightRatio = 0.72f,
        blobDistortion = 0.07f,
        blobDistortionSpeed = 0.4f,
        blobPulseFreq = 0.8f,
        blobPulseAmp = 0.04f,
        particleCount = 10,
        particleMode = ParticleMode.CONVERGE,
        particleSpeed = 0.3f,
        particleSize = 0.3f,
        particleOpacity = 0.5f,
        primaryHue = 330f,
        primarySaturation = 0.75f,
        primaryLightness = 0.55f,
        secondaryHueOffset = 40f
    )

    // ==================== 系统状态类参数实现 ====================

    /**
     * 低电量：焦点暗淡间歇闪烁、低电黄、Blob 衰减呼吸
     */
    private fun lowBatteryParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.16f,
        coreBrightness = 0.35f,
        coreTremorFreq = 1f,
        coreTremorAmp = 0.1f,
        blobExponent = 3f,
        blobWidthRatio = 0.68f,
        blobHeightRatio = 0.70f,
        blobDistortion = 0.05f,
        blobDistortionSpeed = 0.2f,
        blobPulseFreq = 0.5f,
        blobPulseAmp = 0.03f,
        particleCount = 4,
        particleMode = ParticleMode.ORBIT,
        particleSpeed = 0.2f,
        particleSize = 0.25f,
        particleOpacity = 0.3f,
        primaryHue = 40f,
        primarySaturation = 0.8f,
        primaryLightness = 0.42f,
        secondaryHueOffset = 35f
    )

    /**
     * 充电中：焦点有节奏亮起、充电绿、Blob 缓慢膨胀
     */
    private fun chargingParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.22f,
        coreBrightness = 0.8f,
        coreTremorFreq = 1.5f,    // 充电脉冲
        coreTremorAmp = 0.15f,
        blobExponent = 2.5f,
        blobWidthRatio = 0.78f,
        blobHeightRatio = 0.80f,
        blobDistortion = 0.06f,
        blobDistortionSpeed = 0.3f,
        blobPulseFreq = 0.5f,     // 缓慢膨胀
        blobPulseAmp = 0.06f,
        particleCount = 12,
        particleMode = ParticleMode.CONVERGE,
        particleSpeed = 0.4f,     // 从外向内流动
        particleSize = 0.3f,
        particleOpacity = 0.55f,
        primaryHue = 130f,
        primarySaturation = 0.65f,
        primaryLightness = 0.48f,
        secondaryHueOffset = 40f
    )

    /**
     * 告警：极亮红、高频闪烁、粒子爆发
     */
    private fun alertParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.25f,
        coreBrightness = 1f,
        coreTremorFreq = 15f,
        coreTremorAmp = 0.4f,
        blobExponent = 3.5f,
        blobWidthRatio = 0.82f,
        blobHeightRatio = 0.85f,
        blobDistortion = 0.15f,
        blobDistortionSpeed = 1.5f,
        blobPulseFreq = 6f,       // 高频震荡
        blobPulseAmp = 0.12f,
        particleCount = 24,
        particleMode = ParticleMode.DIVERGE,
        particleSpeed = 1.8f,
        particleSize = 0.38f,
        particleOpacity = 0.8f,
        flashIntensity = 0.4f,    // 全屏闪烁
        primaryHue = 0f,
        primarySaturation = 1.0f,
        primaryLightness = 0.45f,
        secondaryHueOffset = 25f
    )

    /**
     * 休眠：焦点极微弱、深夜紫、极慢呼吸、粒子几乎静止
     */
    private fun sleepingParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.06f,
        coreBrightness = 0.1f,
        blobExponent = 3f,
        blobWidthRatio = 0.48f,
        blobHeightRatio = 0.48f,
        blobDistortion = 0.02f,
        blobDistortionSpeed = 0.05f,
        blobPulseFreq = 0.17f,    // 6s 周期
        blobPulseAmp = 0.02f,
        particleCount = 2,
        particleMode = ParticleMode.ORBIT,
        particleSpeed = 0.05f,    // 几乎静止
        particleSize = 0.2f,
        particleOpacity = 0.15f,
        primaryHue = 230f,
        primarySaturation = 0.3f,
        primaryLightness = 0.18f,
        secondaryHueOffset = 30f
    )

    /**
     * 固件升级：进度条形态、升级蓝
     */
    private fun updatingParams(): EnergyFieldParameters = EnergyFieldParameters(
        coreCount = 1,
        coreSize = 0.1f,
        coreBrightness = 0.6f,
        blobExponent = 5f,        // 接近矩形
        blobWidthRatio = 0.72f,
        blobHeightRatio = 0.18f,  // 进度条形态
        blobDistortion = 0f,
        blobDistortionSpeed = 0f,
        blobPulseFreq = 0f,
        blobPulseAmp = 0f,
        particleCount = 8,
        particleMode = ParticleMode.ROTATE,
        particleSpeed = 0.5f,
        particleSize = 0.25f,
        particleOpacity = 0.5f,
        progressValue = 0.3f,     // 初始进度（实际由 Renderer 动态更新）
        primaryHue = 210f,
        primarySaturation = 0.7f,
        primaryLightness = 0.5f,
        secondaryHueOffset = 35f
    )
}
