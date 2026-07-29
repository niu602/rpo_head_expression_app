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
    HAPPY,               // 开心：暖金色光点缓缓飘动，明亮光斑
    CONFUSED,            // 疑惑：瞳孔内问号虚影
    RECOGNIZING,         // 识别中：扫描纹路游走
    SHY,                 // 害羞：粉色雾状纹理，光斑收敛
    SYSTEM_ERROR,        // 系统故障：碎闪色块，乱跳斑点
    RESPONSE             // 应答反馈：银色月牙+浮动星光
}
