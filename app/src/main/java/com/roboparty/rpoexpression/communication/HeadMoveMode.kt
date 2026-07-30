/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * 头部运动模式枚举
 *
 * @deprecated 新协议（v2）已移除 move_mode 字段，板端根据 emotion 自动映射动作。
 * 保留此文件仅为兼容旧代码引用，新代码请直接使用 EmotionType。
 */
@Deprecated("新协议已移除move_mode，板端根据emotion自动映射动作", ReplaceWith("EmotionType"))
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
