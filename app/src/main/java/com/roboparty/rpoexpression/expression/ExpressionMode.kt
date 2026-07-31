/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 表情渲染模式枚举
 *
 * 与 ExpressionStyle（MALE/FEMALE）正交：
 * - ExpressionStyle 是同一渲染管线内的风格变体
 * - ExpressionMode 是完全不同的渲染管线选择
 */
enum class ExpressionMode {
    TRADITIONAL,  // 传统眼部表情模式（现有）
    ENERGY        // 能量面部模式（新增）
}
