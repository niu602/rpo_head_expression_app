/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * 情绪状态枚举
 * 与嵌入式提供的简化版 head_control.proto 定义一致
 */
enum class EmotionType(val value: Int) {
    EMOTION_NONE(0),       // 无情绪/平静
    EMOTION_CONFUSION(1),  // 疑惑 → 触发摇头
    EMOTION_HAPPY(2);      // 开心 → 触发点头

    companion object {
        fun fromValue(value: Int): EmotionType {
            return values().find { it.value == value } ?: EMOTION_NONE
        }
    }
}
