/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * 头部运动模式枚举
 * 与嵌入式提供的简化版 head_control.proto 定义一致
 */
enum class HeadMoveMode(val value: Int) {
    MOVE_NONE(0),   // 停止所有头部动作
    MOVE_SHAKE(1),  // 摇头（疑惑时触发）
    MOVE_NOD(2);    // 点头（开心时触发）

    companion object {
        fun fromValue(value: Int): HeadMoveMode {
            return values().find { it.value == value } ?: MOVE_NONE
        }
    }
}
