/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.config

/**
 * 表情系统完整配置
 * 所有可调参数都在这里，支持JSON序列化/反序列化
 */
data class ExpressionConfig(
    // ========== 版本信息 ==========
    val version: String = "1.0",
    val configName: String = "default",

    // ========== 人脸检测阈值 ==========
    val faceDetection: FaceDetectionConfig = FaceDetectionConfig(),
    // ========== 睡眠系统参数 ==========
    val sleep: SleepConfig = SleepConfig(),
    // ========== 表情动画参数 ==========
    val animation: AnimationConfig = AnimationConfig(),
    // ========== 颜色配置 ==========
    val colors: ColorConfig = ColorConfig(),
    // ========== 通信参数 ==========
    val communication: CommunicationConfig = CommunicationConfig(),
    // ========== 渲染参数 ==========
    val render: RenderConfig = RenderConfig(),
    // ========== 系统状态阈值 ==========
    val systemStatus: SystemStatusConfig = SystemStatusConfig()
)

/**
 * 人脸检测相关阈值
 */
data class FaceDetectionConfig(
    // 微笑检测
    val smileThreshold: Float = 0.95f,
    val smileFrameCount: Int = 8,
    val smileCooldownMs: Long = 2500L,
    // 眨眼检测
    val eyeClosedThreshold: Float = 0.4f,
    val blinkMinDurationMs: Long = 80L,
    val blinkMaxDurationMs: Long = 500L,
    val blinkCooldownMs: Long = 300L,
    // 人脸丢失/休眠
    val noFaceSleepDelayMs: Long = 9000L,
    val sleepFrameSkip: Int = 5,
    // 多人脸
    val multiFaceConfusedThreshold: Int = 3,
    val multiFaceConfusedCooldownMs: Long = 3000L,
    val twoFaceSweepPeriodMs: Long = 2500L,
    // 距离感知
    val distancePupilMinScale: Float = 0.85f,
    val distancePupilMaxScale: Float = 1.15f,
    // 滤波
    val expressionFilterCount: Int = 5,
    val faceSmoothFactor: Float = 0.2f
)

/**
 * 睡眠系统参数
 */
data class SleepConfig(
    val yawningDurationMs: Long = 2500L,
    val lightSleepDurationMs: Long = 15000L,
    val lightSleepEyelidOpen: Float = 0.45f,
    val deepSleepEyelidOpen: Float = 0.12f,
    val lightMicroIntervalMs: Long = 1500L,
    val lightMicroAmplitude: Float = 0.12f,
    val deepSleepBreathPeriodMs: Long = 4000L,
    val deepSleepBreathAmplitude: Float = 0.02f,
    val deepSleepFps: Int = 30
)

/**
 * 表情动画参数
 */
data class AnimationConfig(
    val transitionDurationMs: Long = 600L,
    val debounceIntervalMs: Long = 100L,
    val blinkDurationMs: Long = 150L,
    val autoBlinkMinIntervalMs: Long = 2000L,
    val autoBlinkMaxIntervalMs: Long = 4000L,
    // 各表情默认时长
    val responseDurationMs: Long = 1500L,
    val happyDurationMs: Long = 1800L,
    val confusedDurationMs: Long = 1500L,
    val sadDurationMs: Long = 1800L,
    val angryDurationMs: Long = 1500L,
    val shyDurationMs: Long = 1200L,
    // 装饰元素动画
    val questionMarkSize: Float = 60f,
    val heartSize: Float = 45f,
    val blushRadiusXScale: Float = 0.4f,
    val blushRadiusYScale: Float = 0.25f
)

/**
 * 颜色配置（ARGB，十六进制）
 */
data class ColorConfig(
    val eyeWhite: Long = 0xFFFFFFFF,
    val pupil: Long = 0xFF1A1A2E,
    val iris: Long = 0xFF16213E,
    // 系统状态
    val lowBatteryColor: Long = 0xFFFFB300,
    val chargingColor: Long = 0xFF4CAF50,
    val alertColor: Long = 0xFFE53935,
    val updatingColor: Long = 0xFF2196F3,
    // 情绪装饰
    val happyHeartColor: Long = 0xFFFF4081,
    val confusedQuestionColor: Long = 0xFFFFC107,
    val shyBlushColor: Long = 0xFFFF8A95,
    val angryPulseColor: Long = 0xFFE53935,
    // 眼内特效
    val thinkingArcColor: Long = 0xFF90CAF9,
    val successStarColor: Long = 0xFFFFD700,
    val happyStarColor: Long = 0xFFFFB6C1,
    val errorPulseColor: Long = 0xFFFF5252
)

/**
 * 通信参数
 */
data class CommunicationConfig(
    val defaultHost: String = "192.168.1.100",
    val tcpPort: Int = 8899,
    val udpPort: Int = 8900,
    val heartbeatIntervalMs: Long = 3000L,
    val statusReportIntervalMs: Long = 1000L,
    val healthCheckIntervalMs: Long = 500L,
    val linkTimeoutMs: Long = 1000L,
    val linkRecoveryCheckCount: Int = 3,
    val allLinkLostAlertMs: Long = 5000L,
    val connectTimeoutMs: Long = 3000L
)

/**
 * 渲染参数
 */
data class RenderConfig(
    val eyeWidthRatio: Float = 0.28f,
    val eyeHeightRatio: Float = 0.35f,
    val eyeSeparationRatio: Float = 0.18f,
    val pupilSizeRatio: Float = 0.45f,
    val irisSizeRatio: Float = 0.7f,
    val cornerRadiusRatio: Float = 0.5f,
    val maxFps: Int = 60,
    val eyeBorderWidth: Float = 4f
)

/**
 * 系统状态阈值
 */
data class SystemStatusConfig(
    val lowBatteryThreshold: Int = 20,
    val criticalBatteryThreshold: Int = 5,
    val overTemperatureThreshold: Float = 45f,
    val alertCooldownMs: Long = 10000L
)
