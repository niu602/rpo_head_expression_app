/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.style

import com.roboparty.rpoexpression.expression.ExpressionStyle

/**
 * 眼部风格工厂
 * 管理所有风格渲染器实例，支持动态切换
 */
object EyeStyleFactory {

    private val renderers = mapOf(
        ExpressionStyle.MALE to MaleEyeRenderer(),
        ExpressionStyle.FEMALE to FemaleEyeRenderer()
    )

    /**
     * 获取指定风格的渲染器
     */
    fun getRenderer(style: ExpressionStyle): EyeStyleRenderer {
        return renderers[style] ?: renderers[ExpressionStyle.FEMALE]!!
    }
}
