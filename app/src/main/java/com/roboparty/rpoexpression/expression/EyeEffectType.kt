/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 眼内特效类型
 * 所有特效严格限定在瞳孔和眼周区域，不超出眼睛范围
 */
enum class EyeEffectType {
    NONE,                // 无特效
    THINKING_LOADING,    // 思考：旋转加载线
    RECOGNIZE_SUCCESS,   // 识别成功：星芒高光
    HAPPY_STARLIGHT,     // 开心：细碎星光
    ERROR_PULSE          // 报错：红色脉冲光
}
