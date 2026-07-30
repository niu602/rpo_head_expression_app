/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * 情绪状态枚举
 * 与嵌入式 head_control.proto 定义严格一致
 *
 * 板端动作库按此索引查表执行对应动作：
 * - EMOTION_NONE(0): 停止/回中
 * - EMOTION_HAPPY(1): 开心 → 点头
 * - EMOTION_CONFUSION(2): 疑惑 → 摇头
 * - EMOTION_SAD(3): 难过 → 低头
 * - EMOTION_ANGRY(4): 生气 → 快摇
 * - EMOTION_SURPRISED(5): 惊讶 → 抬头
 * - EMOTION_SHY(6): 害羞 → 低头偏头
 */
enum class EmotionType(val value: Int) {
    EMOTION_NONE(0),        // 无情绪/停止动作
    EMOTION_HAPPY(1),       // 开心 → 点头
    EMOTION_CONFUSION(2),   // 疑惑 → 摇头
    EMOTION_SAD(3),         // 难过 → 低头
    EMOTION_ANGRY(4),       // 生气 → 快摇
    EMOTION_SURPRISED(5),   // 惊讶 → 抬头
    EMOTION_SHY(6);         // 害羞 → 低头偏头

    companion object {
        fun fromValue(value: Int): EmotionType {
            return values().find { it.value == value } ?: EMOTION_NONE
        }
    }
}
