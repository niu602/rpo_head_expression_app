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

    private val renderers = mutableMapOf<ExpressionStyle, EyeStyleRenderer>()

    init {
        // 注册男女两款风格
        renderers[ExpressionStyle.MALE] = MaleEyeRenderer()
        renderers[ExpressionStyle.FEMALE] = FemaleEyeRenderer()
    }

    /**
     * 获取指定风格的渲染器
     */
    fun getRenderer(style: ExpressionStyle): EyeStyleRenderer {
        return renderers[style] ?: renderers[ExpressionStyle.FEMALE]!!
    }

    /**
     * 注册自定义风格渲染器
     * 支持动态加载第三方风格包
     */
    fun registerStyle(style: ExpressionStyle, renderer: EyeStyleRenderer) {
        renderers[style] = renderer
    }

    /**
     * 获取所有可用风格
     */
    fun getAvailableStyles(): List<ExpressionStyle> {
        return renderers.keys.toList()
    }
}
