/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.animation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.roboparty.rpoexpression.RpoExpressionApp
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.sensor.VolumeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
// 滑动区域枚举
private enum class SwipeSide { NONE, LEFT_BRIGHTNESS, RIGHT_VOLUME }

/**
 * 表情画布Composable
 * 订阅表情状态流，实时重绘表情
 *
 * 触摸分区规则（横屏视频播放器交互）：
 * ┌─────────────────────────────────┐
 * │ 左边缘15% │      中间70%      │ 右边缘15% │
 * │ 亮度调节  │  表情交互区域     │ 音量调节  │
 * │ 上下滑动  │ 点击/长按/捏合    │ 上下滑动  │
 * └─────────────────────────────────┘
 *
 * 中间区域触摸：
 * 1. 长按【额头顶部20%】4秒 → 关机/开机
 * 2. 单击【眉心区域】→ 疑惑表情
 * 3. 单击【左眼区】→ 瞳孔向右躲闪
 * 4. 单击【右眼区】→ 瞳孔向左躲闪
 * 5. 单击【脸颊区域】→ 害羞表情
 * 6. 双指捏合【双眼区域】→ 切换男/女风格
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
    val brightnessManager = remember { app.brightnessManager }
    val volumeManager = remember { app.volumeManager }
    val highlightIntensity by brightnessManager.highlightIntensity.collectAsState()
    val currentEyeEffect by expressionManager.currentEffect.collectAsState()

    // 渲染器实例：remember缓存，避免每次重组新建
    val renderer = remember { ExpressionRenderer() }

    val coroutineScope = rememberCoroutineScope()

    // 边缘滑动状态
    var isEdgeSwipe by remember { mutableStateOf(false) }
    var swipeSide by remember { mutableStateOf(SwipeSide.NONE) }
    // 双指捏合状态
    var isPinching by remember { mutableStateOf(false) }
    // 长按电源状态
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    // 电源按钮可视化状态
    var powerProgress by remember { mutableStateOf(0f) }
    var powerButtonAlpha by remember { mutableStateOf(0f) }
    var powerRevertJob by remember { mutableStateOf<Job?>(null) }
    var powerButtonVisible by remember { mutableStateOf(false) }
    var isPoweringOff by remember { mutableStateOf(false) }
    // 刷新按钮状态
    var isRefreshCountingDown by remember { mutableStateOf(false) }
    var refreshCountdownProgress by remember { mutableStateOf(0f) }
    var isRefreshCd by remember { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // ========== 1. 点击/长按手势（优先级最高，先处理） ==========
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        // 触摸屏幕自动停止Demo演示
                        if (app.demoSequenceManager.isRunning()) {
                            app.demoSequenceManager.stopDemo()
                            return@detectTapGestures
                        }

                        // 关机状态下不响应点击
                        if (expressionManager.isPoweredOff()) return@detectTapGestures
                        // 边缘滑动时不处理点击
                        if (isEdgeSwipe) return@detectTapGestures

                        val centerX = size.width / 2f
                        val w = size.width
                        val h = size.height

                        // 区域边界配置
                        val edgeWidth = w * 0.15f
                        // 检测刷新按钮点击（下方中间区域）
                        val refreshAreaTop = h * 0.80f
                        val inRefreshArea = offset.y > refreshAreaTop && offset.x in edgeWidth..(w - edgeWidth)
                        if (inRefreshArea) {
                            if (isRefreshCd) {
                                // CD中，点击无效
                                return@detectTapGestures
                            }
                            if (isRefreshCountingDown) {
                                // 倒计时中第二次点击，执行刷新
                                isRefreshCountingDown = false
                                refreshCountdownProgress = 0f
                                
                                // 立即触发视觉反馈：快速眨眼+应答表情，让用户明确感知到点击生效
                                expressionManager.performBlink()
                                expressionManager.requestExpression(ExpressionType.RESPONSE, duration = 800, force = true)
                                
                                // 立即停止Demo和重置核心表情状态（不依赖广播，确保即时生效）
                                app.demoSequenceManager.stopDemo()
                                expressionManager.softReset()
                                
                                // 发送软重置广播，触发完整重置
                                context.sendBroadcast(android.content.Intent("com.roboparty.rpoexpression.SOFT_RESET"))
                                // 刷新后进入短CD
                                isRefreshCd = true
                                coroutineScope.launch {
                                    delay(1500)
                                    isRefreshCd = false
                                }
                            } else {
                                // 第一次点击，启动1秒倒计时
                                isRefreshCountingDown = true
                                refreshCountdownProgress = 1f
                                coroutineScope.launch {
                                    val startTime = System.currentTimeMillis()
                                    while (isActive && isRefreshCountingDown && refreshCountdownProgress > 0f) {
                                        val elapsed = System.currentTimeMillis() - startTime
                                        refreshCountdownProgress = 1f - (elapsed / 1000f).coerceIn(0f, 1f)
                                        if (refreshCountdownProgress <= 0f) {
                                            // 超时，进入2秒CD
                                            isRefreshCountingDown = false
                                            isRefreshCd = true
                                            delay(2000)
                                            isRefreshCd = false
                                            break
                                        }
                                        delay(16)
                                    }
                                }
                            }
                            return@detectTapGestures
                        }

                        // 只处理中间区域的点击（排除左右边缘）
                        if (offset.x < edgeWidth || offset.x > w - edgeWidth) {
                            return@detectTapGestures
                        }

                        val confuseBottomY = h * 0.40f
                        val eyeBottomY = h * 0.60f
                        val cheekBottomY = h * 0.82f

                        val centerHalfWidth = w * 0.25f
                        val eyeInnerX = centerX - w * 0.08f
                        val eyeOuterLeftX = w * 0.08f
                        val eyeOuterRightX = w * 0.92f
                        val cheekLeftX = w * 0.20f
                        val cheekRightX = w * 0.80f

                        // 1. 单击眉心/额头 → 疑惑
                        val inConfuseArea = offset.x in (centerX - centerHalfWidth)..(centerX + centerHalfWidth)
                                && offset.y in 0f..confuseBottomY
                        if (inConfuseArea) {
                            expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 1000, force = true)
                            return@detectTapGestures
                        }

                        // 2. 单击左眼 → 瞳孔右躲
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

                        // 3. 单击右眼 → 瞳孔左躲
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

                        // 4. 单击脸颊 → 害羞
                        val inCheekArea = offset.x in cheekLeftX..cheekRightX
                                && offset.y in eyeBottomY..cheekBottomY
                        if (inCheekArea) {
                            expressionManager.requestExpression(ExpressionType.SHY, duration = 1200, force = true)
                            return@detectTapGestures
                        }
                    },
                    onPress = { offset ->
                        // 判断是否在额头区域（顶部20%，排除左右边缘）
                        val w = size.width
                        val h = size.height
                        val edgeWidth = w * 0.15f
                        val isPoweredOff = expressionManager.isPoweredOff()
                        val inForeheadArea = offset.y < h * 0.20f && offset.x in edgeWidth..(w - edgeWidth)

                        // 只有额头区域才处理长按，其他区域直接返回不拦截事件
                        if (!inForeheadArea) {
                            // 非电源区域触摸，停止Demo
                            if (app.demoSequenceManager.isRunning()) {
                                app.demoSequenceManager.stopDemo()
                            }
                            return@detectTapGestures
                        }

                        // 性别切换中不响应长按
                        if (expressionManager.isGenderSwitching()) {
                            return@detectTapGestures
                        }
                        // 取消之前的回退动画
                        powerRevertJob?.cancel()
                        isPoweringOff = false
                        powerButtonVisible = true

                        // 按钮淡入
                        coroutineScope.launch {
                            while (powerButtonAlpha < 1f && isActive) {
                                powerButtonAlpha = (powerButtonAlpha + 0.15f).coerceAtMost(1f)
                                delay(16)
                            }
                        }

                        // 开始4秒长按计时，从当前进度继续（支持续接）
                        longPressJob?.cancel()
                        val pressStartTime = System.currentTimeMillis()
                        val startProgress = powerProgress
                        val longPressDuration = 4000L
                        val remainDuration = (longPressDuration * (1f - startProgress)).toLong()

                        longPressJob = coroutineScope.launch {
                            var elapsed = 0L
                            while (elapsed < remainDuration && isActive) {
                                delay(16L)
                                elapsed = System.currentTimeMillis() - pressStartTime
                                powerProgress = (startProgress + elapsed.toFloat() / longPressDuration).coerceIn(0f, 1f)
                                // 实时更新长按进度到ExpressionManager，提供眼睛慢慢闭上的视觉反馈
                                expressionManager.setLongPressProgress(powerProgress)
                            }
                            // 4秒到了，触发关机/开机
                            isPoweringOff = true
                            // 按钮淡出
                            coroutineScope.launch {
                                while (powerButtonAlpha > 0f && isActive) {
                                    powerButtonAlpha = (powerButtonAlpha - 0.1f).coerceAtLeast(0f)
                                    delay(16)
                                }
                                powerProgress = 0f
                                powerButtonVisible = false
                                isPoweringOff = false
                            }
                            if (isPoweredOff) {
                                expressionManager.powerOn()
                            } else {
                                expressionManager.powerOff()
                            }
                        }

                        // 等待手指抬起
                        val released = tryAwaitRelease()
                        longPressJob?.cancel()
                        longPressJob = null

                        // 松手后进度匀速回退，按钮淡出
                        powerRevertJob = coroutineScope.launch {
                            while (powerProgress > 0f && isActive) {
                                delay(16L)
                                powerProgress = (powerProgress - 0.02f).coerceAtLeast(0f)
                                powerButtonAlpha = (powerButtonAlpha - 0.05f).coerceAtLeast(0f)
                                expressionManager.setLongPressProgress(powerProgress)
                            }
                            powerProgress = 0f
                            powerButtonAlpha = 0f
                            powerButtonVisible = false
                        }
                    }
                )
            }
            // ========== 2. 双指捏合手势：双眼区域捏合切换性别 ==========
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    // 关机状态下不响应捏合
                    if (expressionManager.isPoweredOff()) return@detectTransformGestures

                    // 只在双眼区域响应捏合（排除左右边缘15%，垂直中间40%区域）
                    val w = size.width
                    val h = size.height
                    val edgeWidth = w * 0.15f
                    val eyeAreaTop = h * 0.3f
                    val eyeAreaBottom = h * 0.7f

                    val inEyeArea = centroid.x in edgeWidth..(w - edgeWidth) &&
                            centroid.y in eyeAreaTop..eyeAreaBottom

                    if (inEyeArea && !isEdgeSwipe && !expressionManager.isGenderSwitching()) {
                        // 检测捏合动作：先捏小（zoom < 0.7）再张开（zoom > 0.9）
                        if (zoom < 0.7f) {
                            isPinching = true
                        } else if (isPinching && zoom > 0.9f) {
                            // 捏合后张开，触发性别切换
                            expressionManager.toggleGender()
                            isPinching = false
                        }
                    } else if (!inEyeArea) {
                        // 移出眼睛区域，重置状态
                        isPinching = false
                    }
                }
            }
            // ========== 3. 边缘滑动手势（亮度/音量调节） ==========
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // 关机状态下不响应滑动
                        if (expressionManager.isPoweredOff()) return@detectDragGestures
                        val w = size.width
                        val edgeWidth = w * 0.15f
                        when {
                            // 左边缘：亮度调节
                            offset.x < edgeWidth -> {
                                isEdgeSwipe = true
                                swipeSide = SwipeSide.LEFT_BRIGHTNESS
                            }
                            // 右边缘：音量调节
                            offset.x > w - edgeWidth -> {
                                isEdgeSwipe = true
                                swipeSide = SwipeSide.RIGHT_VOLUME
                            }
                            // 中间区域：不处理拖动
                            else -> {
                                isEdgeSwipe = false
                                swipeSide = SwipeSide.NONE
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (isEdgeSwipe) {
                            change.consume()
                            when (swipeSide) {
                                SwipeSide.LEFT_BRIGHTNESS -> {
                                    brightnessManager.onSwipe(dragAmount.y)
                                }
                                SwipeSide.RIGHT_VOLUME -> {
                                    volumeManager.onSwipe(dragAmount.y)
                                }
                                else -> {}
                            }
                        }
                    },
                    onDragEnd = {
                        if (isEdgeSwipe) {
                            when (swipeSide) {
                                SwipeSide.LEFT_BRIGHTNESS -> brightnessManager.onSwipeEnd()
                                SwipeSide.RIGHT_VOLUME -> volumeManager.onSwipeEnd()
                                else -> {}
                            }
                            isEdgeSwipe = false
                            swipeSide = SwipeSide.NONE
                        }
                    },
                    onDragCancel = {
                        if (isEdgeSwipe) {
                            when (swipeSide) {
                                SwipeSide.LEFT_BRIGHTNESS -> brightnessManager.onSwipeEnd()
                                SwipeSide.RIGHT_VOLUME -> volumeManager.onSwipeEnd()
                                else -> {}
                            }
                            isEdgeSwipe = false
                            swipeSide = SwipeSide.NONE
                        }
                    }
                )
            }
    ) {
        // 使用手动调节的高光强度
        val adjustedLightConfig = lightConfig.copy(highlight = highlightIntensity)
        renderer.run {
            render(expressionState, size, adjustedLightConfig, currentEyeEffect)
        // ========== 绘制可视化电源按钮 ==========
        val isPoweredOff = expressionManager.isPoweredOff()
        // 关机状态下默认显示微弱呼吸灯，提示用户开机位置；非关机只有长按时显示
        val baseBreath = 0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 1200f)
        val effectiveAlpha = when {
            powerButtonAlpha > 0.01f -> powerButtonAlpha  // 长按交互时正常亮度
            isPoweredOff -> 0.22f * baseBreath            // 关机状态：微弱呼吸灯
            else -> 0f                                    // 非关机无交互：不显示
        }

        if (effectiveAlpha > 0.01f) {
            val btnSize = 60f * density
            val btnX = size.width / 2f
            val btnY = size.height * 0.12f
            val btnRadius = 16f * density
            val progressWidth = 6f * density
            val breath = 0.7f + 0.3f * kotlin.math.sin(System.currentTimeMillis() / 300f)

            // 按钮呼吸光晕
            drawCircle(
                color = Color(0xFF4FC3F7).copy(alpha = 0.3f * effectiveAlpha * breath),
                radius = btnSize * 0.8f,
                center = Offset(btnX, btnY)
            )

            // 按钮背景：圆角方形
            drawRoundRect(
                color = if (isPoweringOff) Color(0xFF424242).copy(alpha = effectiveAlpha)
                            else Color(0xFFE0E0E0).copy(alpha = effectiveAlpha),
                topLeft = Offset(btnX - btnSize/2, btnY - btnSize/2),
                size = Size(btnSize, btnSize),
                cornerRadius = CornerRadius(btnRadius, btnRadius),
                style = Fill
            )

            // 电源图标（竖线+上半圆）
            val iconColor = if (isPoweringOff) Color(0xFF9E9E9E).copy(alpha = effectiveAlpha)
                            else Color(0xFF616161).copy(alpha = effectiveAlpha)
            // 电源竖线
            drawLine(
                color = iconColor,
                start = Offset(btnX, btnY - btnSize*0.15f),
                end = Offset(btnX, btnY + btnSize*0.15f),
                strokeWidth = 4f * density,
                cap = StrokeCap.Round
            )
            // 电源上半圆
            drawArc(
                color = iconColor,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(btnX - btnSize*0.2f, btnY - btnSize*0.2f),
                size = Size(btnSize*0.4f, btnSize*0.4f),
                style = Stroke(width = 4f * density, cap = StrokeCap.Round)
            )

            // 环形进度条
            if (powerProgress > 0.01f && !isPoweringOff) {
                // 进度条轨道
                drawCircle(
                    color = Color(0xFFB0BEC5).copy(alpha = 0.3f * effectiveAlpha),
                    radius = btnSize * 0.75f,
                    center = Offset(btnX, btnY),
                    style = Stroke(width = progressWidth, cap = StrokeCap.Round)
                )
                // 进度条填充
                drawArc(
                    color = Color(0xFF4FC3F7).copy(alpha = effectiveAlpha * breath),
                    startAngle = -90f,
                    sweepAngle = 360f * powerProgress,
                    useCenter = false,
                    topLeft = Offset(btnX - btnSize*0.75f, btnY - btnSize*0.75f),
                    size = Size(btnSize*1.5f, btnSize*1.5f),
                    style = Stroke(width = progressWidth, cap = StrokeCap.Round)
                )
            }
        }
        }
        // ========== 绘制刷新按钮（下方，和电源按钮对称） ==========
        run {
            val btnSize = 60f * density
            val btnX = size.width / 2f
            val btnY = size.height * 0.88f
            val btnRadius = 16f * density
            val breath = 0.85f + 0.15f * kotlin.math.sin(System.currentTimeMillis() / 500f)

            // 按钮透明度和颜色
            val btnAlpha = when {
                isRefreshCd -> 0.2f
                isRefreshCountingDown -> 1f
                else -> 0.4f * breath
            }
            val btnBgColor = when {
                isRefreshCd -> Color(0xFF9E9E9E)
                isRefreshCountingDown -> Color(0xFFFFECB3)
                else -> Color(0xFFE0E0E0)
            }

            // 按钮背景：圆角方形
            drawRoundRect(
                color = btnBgColor.copy(alpha = btnAlpha),
                topLeft = Offset(btnX - btnSize/2, btnY - btnSize/2),
                size = Size(btnSize, btnSize),
                cornerRadius = CornerRadius(btnRadius, btnRadius),
                style = Fill
            )

            // 金色/灰色星星图标
            val starColor = when {
                isRefreshCd -> Color(0xFFBDBDBD).copy(alpha = btnAlpha)
                isRefreshCountingDown -> Color(0xFFFFA000).copy(alpha = 1f)
                else -> Color(0xFFFFC107).copy(alpha = btnAlpha)
            }
            val iconR = btnSize * 0.25f
            // 270度圆弧（刷新图标主体）
            drawArc(
                color = starColor,
                startAngle = 45f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(btnX - iconR, btnY - iconR),
                size = Size(iconR * 2, iconR * 2),
                style = Stroke(width = 4f * density, cap = StrokeCap.Round)
            )

            // 倒计时进度环
            if (isRefreshCountingDown && refreshCountdownProgress > 0.01f) {
                // 进度轨道
                drawCircle(
                    color = Color(0xFFFFE082).copy(alpha = 0.3f),
                    radius = btnSize * 0.75f,
                    center = Offset(btnX, btnY),
                    style = Stroke(width = 5f * density, cap = StrokeCap.Round)
                )
                // 倒计时进度
                drawArc(
                    color = Color(0xFFFFA000).copy(alpha = 1f),
                    startAngle = -90f,
                    sweepAngle = 360f * (1f - refreshCountdownProgress),
                    useCenter = false,
                    topLeft = Offset(btnX - btnSize*0.75f, btnY - btnSize*0.75f),
                    size = Size(btnSize*1.5f, btnSize*1.5f),
                    style = Stroke(width = 5f * density, cap = StrokeCap.Round)
                )
            }
        }
    }


}


