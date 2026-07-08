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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.roboparty.rpoexpression.RpoExpressionApp
import com.roboparty.rpoexpression.expression.ExpressionManager
import kotlinx.coroutines.launch

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
    val renderer = remember { ExpressionRenderer(assetLoader) }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        renderer.run {
            render(expressionState, size)
        }
    }
}
