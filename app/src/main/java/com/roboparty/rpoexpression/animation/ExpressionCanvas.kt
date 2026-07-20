/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.roboparty.rpoexpression.RpoExpressionApp
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.sensor.AmbientLightManager
import com.roboparty.rpoexpression.animation.ExpressionRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 表情画布Composable
 * 订阅表情状态流，实时重绘表情
 *
 * 触摸分区规则（从上到下）：
 * 1. 长按【额头顶部区域】→ 进入休眠
 * 2. 单击【眉心区域】→ 疑惑表情
 * 3. 单击【左眼区】→ 瞳孔向右躲闪
 * 4. 单击【右眼区】→ 瞳孔向左躲闪
 * 5. 单击【脸颊区域】→ 害羞表情
 * 所有区域横向/纵向严格分层，互不干涉
 */
@Composable
fun ExpressionCanvas(
    modifier: Modifier = Modifier,
    expressionManager: ExpressionManager = ExpressionManager.getInstance()
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as RpoExpressionApp }
    val expressionState by expressionManager.expressionState.collectAsState()
    val lightConfig by app.ambientLightManager.lightConfig.collectAsState()

    // 渲染器实例：remember缓存，避免每次重组新建
    val renderer = remember { ExpressionRenderer() }

    val coroutineScope = rememberCoroutineScope()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val centerX = size.width / 2f
                        val w = size.width
                        val h = size.height

                        // ========== 区域边界配置（纵向严格分层，无重叠） ==========
                        // 纵向边界（从上到下）
                        val sleepBottomY = h * 0.20f       // 额头休眠区下边界
                        val confuseBottomY = h * 0.40f     // 疑惑区下边界
                        val eyeBottomY = h * 0.60f         // 眼睛区下边界
                        val cheekBottomY = h * 0.82f       // 脸颊区下边界

                        // 横向边界
                        val centerHalfWidth = w * 0.25f    // 眉心居中半宽
                        val eyeInnerX = centerX - w * 0.08f  // 眼睛内侧边界（中间留16%间距）
                        val eyeOuterLeftX = w * 0.08f      // 左眼外侧边界
                        val eyeOuterRightX = w * 0.92f     // 右眼外侧边界
                        val cheekLeftX = w * 0.20f         // 脸颊左边界
                        val cheekRightX = w * 0.80f        // 脸颊右边界

                        // ========== 1. 单击眉心 → 疑惑（纯视觉反馈，force绕过防抖） ==========
                        val inConfuseArea = offset.x in (centerX - centerHalfWidth)..(centerX + centerHalfWidth)
                                && offset.y in sleepBottomY..confuseBottomY
                        if (inConfuseArea) {
                            expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 1000, force = true)
                            return@detectTapGestures
                        }

                        // ========== 2. 单击左眼 → 瞳孔右躲 ==========
                        val inLeftEyeArea = offset.x in eyeOuterLeftX..eyeInnerX
                                && offset.y in confuseBottomY..eyeBottomY
                        if (inLeftEyeArea) {
                            expressionManager.setTouchPupilOffset(0.5f, 0f)
                            coroutineScope.launch {
                                delay(500)
                                expressionManager.setTouchPupilOffset(0f, 0f)
                            }
                            return@detectTapGestures
                        }

                        // ========== 3. 单击右眼 → 瞳孔左躲 ==========
                        val inRightEyeArea = offset.x in (centerX + w * 0.08f)..eyeOuterRightX
                                && offset.y in confuseBottomY..eyeBottomY
                        if (inRightEyeArea) {
                            expressionManager.setTouchPupilOffset(-0.5f, 0f)
                            coroutineScope.launch {
                                delay(500)
                                expressionManager.setTouchPupilOffset(0f, 0f)
                            }
                            return@detectTapGestures
                        }

                        // ========== 4. 单击脸颊 → 害羞（纯视觉反馈，force绕过防抖） ==========
                        val inCheekArea = offset.x in cheekLeftX..cheekRightX
                                && offset.y in eyeBottomY..cheekBottomY
                        if (inCheekArea) {
                            expressionManager.requestExpression(ExpressionType.SHY, duration = 1200, force = true)
                            return@detectTapGestures
                        }
                    },
                    onLongPress = { offset ->
                        val h = size.height
                        // 长按额头顶部区域：触发休眠（纯视觉状态切换，force绕过防抖）
                        if (offset.y < h * 0.20f) {
                            expressionManager.requestExpression(ExpressionType.SLEEPING, force = true)
                        }
                    }
                )
            }
    ) {
        renderer.run {
            render(expressionState, size, lightConfig)
        }
    }
}