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
    const val DEFAULT_MASTER_IP = "198.43.2.89"
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
}
