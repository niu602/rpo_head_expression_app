/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.util

import com.roboparty.rpoexpression.BuildConfig

/**
 * 常量定义
 */
object Constants {
    // 通信配置
    const val DEFAULT_MASTER_IP = "10.43.2.69"
    const val DEFAULT_MASTER_PORT = 8899
    const val HEAD_CONTROL_PORT = 8765 // 头部电机控制独立端口
    const val SOCKET_TIMEOUT = 5000

    // 性能配置
    const val TARGET_FPS = 60
    const val FRAME_DELAY_MS = 1000L / TARGET_FPS
    const val MAX_RESPONSE_LATENCY_MS = 100L  // 表情切换响应延迟要求

    // 表情动画配置
    const val BLINK_DURATION_MS = 200L
    const val RESPONSE_DURATION_MS = 1000L
    const val TRANSITION_DURATION_MS = 150L

    // 眨眼间隔
    const val BLINK_MIN_INTERVAL_MS = 3000L
    const val BLINK_MAX_INTERVAL_MS = 8000L

    // 资源路径
    const val ASSETS_EXPRESSION_PATH = "expressions/"

    // ==================== 通义千问API配置 ====================
    // API Key 从 local.properties 注入 BuildConfig，不硬编码在代码中
    val QWEN_ASR_API_KEY: String get() = BuildConfig.QWEN_ASR_API_KEY
    val QWEN_LLM_API_KEY: String get() = BuildConfig.QWEN_LLM_API_KEY
    // LLM配置：ali-qwen3.6-flash
    const val QWEN_LLM_BASE_URL = "https://ai-gateway.roboparty.com/v1"
    const val QWEN_LLM_MODEL = "ali-qwen3.6-flash"
    const val QWEN_LLM_CHAT_ENDPOINT = "/chat/completions"
    // ASR配置：qwen3-asr-flash-realtime 实时语音识别（阿里云官方地址）
    const val QWEN_ASR_MODEL = "qwen3-asr-flash-realtime"
    const val QWEN_ASR_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$QWEN_ASR_MODEL"
    const val ENABLE_MOCK_LLM_WHEN_NO_KEY = true
    // 音频采集配置（匹配ASR要求：16kHz 16bit 单声道PCM）
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
    const val AUDIO_BUFFER_SIZE_MULTIPLIER = 2 // 缓冲区倍数，避免溢出

    // ==================== 云端TTS配置 ====================
    // DashScope 官方HTTP API TTS（非流式，稳定可靠）
    const val CLOUD_TTS_HTTP_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
    // WebSocket流式TTS地址（暂未使用，预留备用）
    const val CLOUD_TTS_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
    // CosyVoice 公共模型名（使用v3-flash，音色兼容性最好）
    const val COSYVOICE_V35_MODEL = "cosyvoice-v3-flash"
    // 默认女声音色：龙颜（清亮少女，萝卜人格用）
    const val CLOUD_TTS_VOICE_ID = "longyan_v3"
    // 男声音色：龙飞（阿冷人格用，标杆音色，合成速度快）
    const val CLOUD_TTS_VOICE_ID_MALE = "longfei_v3"
    // TTS 采样率
    const val CLOUD_TTS_SAMPLE_RATE = 16000
    // TTS 音频格式
    const val CLOUD_TTS_FORMAT = "wav"

    val CLOUD_TTS_APP_KEY: String get() = BuildConfig.QWEN_ASR_API_KEY
    const val CLOUD_TTS_ENABLE = true // 设为true启用云端TTS，false时使用系统TTS
}
