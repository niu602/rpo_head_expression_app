/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.util

/**
 * 常量定义
 */
object Constants {
    // 通信配置
    const val DEFAULT_MASTER_IP = "10.43.2.69"
    const val DEFAULT_MASTER_PORT = 8899
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
    // TODO: 请替换为你自己的ASR服务API Key（qwen3-asr-flash-realtime）
    const val QWEN_ASR_API_KEY = "sk-ws-H.EMRPRRD.K53l.MEUCIQDViW_CUwu3JjtdnwobxlfRxTV1YZNdJL-9u-W6iMO5OAIgKFFA-FMiLbtB0d_KeP7ektyETvyQCX_vmMiIA-kgQbo"
    // TODO: 请替换为你自己的LLM服务API Key（qwen3.6-plus），未配置时自动使用模拟回复测试
    const val QWEN_LLM_API_KEY = "sk-SHWccSkDo5tMYWrrhcu_cQ"
    // LLM配置：ali-qwen3.6-plus
    const val QWEN_LLM_BASE_URL = "https://ai-gateway.roboparty.com/v1"
    const val QWEN_LLM_MODEL = "ali-qwen3.6-plus"
    const val QWEN_LLM_CHAT_ENDPOINT = "/chat/completions"
    // ASR配置：qwen3-asr-flash-realtime 实时语音识别（阿里云官方地址）
    const val QWEN_ASR_MODEL = "qwen3-asr-flash-realtime"
    const val QWEN_ASR_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$QWEN_ASR_MODEL"
    // 测试模式配置：LLM Key未配置时自动启用模拟回复，无需真实Key即可测试全链路
    const val ENABLE_MOCK_LLM_WHEN_NO_KEY = true
    // 音频采集配置（匹配ASR要求：16kHz 16bit 单声道PCM）
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
    const val AUDIO_BUFFER_SIZE_MULTIPLIER = 2 // 缓冲区倍数，避免溢出
}
