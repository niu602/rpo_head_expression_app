/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.roboparty.rpoexpression.expression.EnergyFieldParameters

/**
 * 能量场渲染器接口
 *
 * 与现有 [EyeStyleRenderer] 平行，提供能量面部模式的渲染管线。
 * ExpressionRenderer 根据 ExpressionMode 选择调用传统眼部渲染或能量场渲染。
 */
interface EnergyFieldRenderer {

    /**
     * 渲染能量场（分层能量场 · 方案E）
     *
     * 绘制顺序（从下到上）：
     * 1. 深色背景（纯黑 + 极深蓝紫径向渐变）
     * 2. L2 流体光场（Blob + 渐变填充 + 边缘发光）
     * 3. L1 能量焦点（径向渐变光晕 + 核心亮点）
     * 4. 效果叠加层（扫描线 / 进度条 / 全屏闪）
     *
     * @param params    能量场参数
     * @param centerX   画布中心 X
     * @param centerY   画布中心 Y
     * @param size      画布尺寸
     * @param time      当前时间（秒），驱动动画
     */
    fun DrawScope.renderField(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float,
    )
}
