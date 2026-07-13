/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.roboparty.rpoexpression.RpoExpressionApp
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.sensor.AmbientLightManager
import com.roboparty.rpoexpression.animation.ExpressionRenderer// 关键：引入Renderer

/**
 * 表情画布Composable
 * 订阅表情状态流，实时重绘表情
 *
 * 使用方式：直接在全屏布局中放置此组件即可
 */
@Composable
fun ExpressionCanvas(
    modifier: Modifier = Modifier,
    expressionManager: ExpressionManager = ExpressionManager.getInstance()
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as RpoExpressionApp }
    val assetLoader = remember { app.assetLoader }
    val expressionState by expressionManager.expressionState.collectAsState()
    val lightConfig by app.ambientLightManager.lightConfig.collectAsState()

    // 关键修复：正确实例化 ExpressionRenderer
    val renderer = remember { ExpressionRenderer(assetLoader) }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        // 现在 renderer 拥有 render 方法，编译通过
        renderer.run {
            render(expressionState, size, lightConfig)
        }
    }
}