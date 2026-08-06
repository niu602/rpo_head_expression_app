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
import androidx.compose.ui.graphics.nativeCanvas
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
    // 订阅渲染模式 StateFlow：模式变化时自动触发重组重绘
    val currentMode by expressionManager.currentMode.collectAsState()

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

    // 设置面板状态
    var isSettingsPanelVisible by remember { mutableStateOf(false) }

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

                        // 检测设置按钮点击（左下角，原模式切换按钮升级为设置入口）
                        val settingsBtnSize = 50f * density
                        if (offset.x < settingsBtnSize * 1.5f && offset.y > h - settingsBtnSize * 1.5f) {
                            // 切换设置面板显示状态
                            isSettingsPanelVisible = !isSettingsPanelVisible
                            return@detectTapGestures
                        }

                        // 如果设置面板显示中，点击其他区域关闭面板
                        if (isSettingsPanelVisible) {
                            // 计算面板区域（居中显示，宽度为屏幕的 70%，高度为 60%）
                            val panelWidth = w * 0.7f
                            val panelHeight = h * 0.6f
                            val panelLeft = (w - panelWidth) / 2f
                            val panelTop = (h - panelHeight) / 2f
                            val panelRight = panelLeft + panelWidth
                            val panelBottom = panelTop + panelHeight

                            // 点击面板外区域，关闭面板
                            if (offset.x < panelLeft || offset.x > panelRight ||
                                offset.y < panelTop || offset.y > panelBottom) {
                                isSettingsPanelVisible = false
                                return@detectTapGestures
                            }

                            // 检测关闭按钮点击（右上角 X）
                            val closeBtnSize = 30f * density
                            val closeBtnX = panelRight - 25f * density
                            val closeBtnY = panelTop + 25f * density
                            val closeBtnLeft = closeBtnX - closeBtnSize / 2f
                            val closeBtnRight = closeBtnX + closeBtnSize / 2f
                            val closeBtnTop = closeBtnY - closeBtnSize / 2f
                            val closeBtnBottom = closeBtnY + closeBtnSize / 2f

                            if (offset.x >= closeBtnLeft && offset.x <= closeBtnRight &&
                                offset.y >= closeBtnTop && offset.y <= closeBtnBottom) {
                                isSettingsPanelVisible = false
                                return@detectTapGestures
                            }

                            // 点击面板内区域，处理音色选择点击
                            // 获取当前人格对应的音色列表
                            val currentPersona = app.llmManager.getPersona()
                            val config = com.roboparty.rpoexpression.util.PersonaRegistry.getConfig(currentPersona)
                            val voiceList = com.roboparty.rpoexpression.util.VoiceLibrary.getVoices(config.expressionStyle)
                            val currentVoiceId = app.voicePreferenceStore.getSelectedVoice(currentPersona)
                                ?: com.roboparty.rpoexpression.util.VoiceLibrary.getDefault(config.expressionStyle).voiceId

                            // 音色按钮布局：纵向排列，每个按钮高度 50dp
                            val voiceBtnHeight = 50f * density
                            val voiceBtnMargin = 10f * density
                            val voiceListTop = panelTop + 80f * density // 留出标题空间

                            for (i in voiceList.indices) {
                                val voice = voiceList[i]
                                val btnTop = voiceListTop + i * (voiceBtnHeight + voiceBtnMargin)
                                val btnBottom = btnTop + voiceBtnHeight
                                val btnLeft = panelLeft + 20f * density
                                val btnRight = panelRight - 20f * density

                                if (offset.y >= btnTop && offset.y <= btnBottom &&
                                    offset.x >= btnLeft && offset.x <= btnRight) {
                                    // 点击了音色按钮
                                    // 区分是点击左侧选择区域还是右侧试听按钮
                                    val trialBtnWidth = 60f * density
                                    val trialBtnLeft = btnRight - trialBtnWidth - 10f * density

                                    if (offset.x >= trialBtnLeft) {
                                        // 点击试听按钮
                                        // 先切换到该音色，然后播放试听句
                                        app.audioManager.stopSpeaking()
                                        app.audioManager.setTtsVoice(voice.voiceId)
                                        app.audioManager.startSpeaking(voice.sampleText)
                                    } else {
                                        // 点击选择区域，切换音色
                                        // 保存用户选择
                                        app.voicePreferenceStore.setSelectedVoice(currentPersona, voice.voiceId)
                                        // 切换 TTS 音色
                                        app.audioManager.setTtsVoice(voice.voiceId)
                                        // 播放试听句
                                        app.audioManager.stopSpeaking()
                                        app.audioManager.startSpeaking(voice.sampleText)

                                        // 预缓存当前人格的固定句式
                                        // 后台执行，不阻塞主线程
                                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val config = com.roboparty.rpoexpression.util.PersonaRegistry.getConfig(currentPersona)
                                                val phrases = config.fixedPhrases
                                                for (phrase in phrases) {
                                                    app.audioManager.preloadTts(phrase)
                                                }
                                                android.util.Log.d("ExpressionCanvas", "Preloaded ${phrases.size} phrases for voice ${voice.voiceId}")
                                            } catch (e: Exception) {
                                                android.util.Log.e("ExpressionCanvas", "Preload failed", e)
                                            }
                                        }
                                    }
                                    return@detectTapGestures
                                }
                            }

                            return@detectTapGestures
                        }

                        // 检测切换男女按钮点击（底部中间，和刷新键一行、和关机键一列）
                        val genderBtnSize = 60f * density
                        val genderBtnX = w / 2f
                        val genderBtnY = h - genderBtnSize * 0.75f
                        val inGenderArea = offset.y > genderBtnY - genderBtnSize &&
                                offset.x > genderBtnX - genderBtnSize &&
                                offset.y < genderBtnY + genderBtnSize &&
                                offset.x < genderBtnX + genderBtnSize
                        if (inGenderArea) {
                            // 关机状态下不响应
                            if (expressionManager.isPoweredOff()) {
                                return@detectTapGestures
                            }
                            // 切换中不响应（防重入）
                            if (expressionManager.isGenderSwitching()) {
                                return@detectTapGestures
                            }
                            // 触发男女切换
                            expressionManager.toggleGender()
                            return@detectTapGestures
                        }

                        // 区域边界配置
                        val edgeWidth = w * 0.15f
                        // 检测刷新按钮点击（右下角）
                        val refreshBtnSize = 60f * density
                        val refreshBtnX = w - refreshBtnSize * 0.75f
                        val refreshBtnY = h - refreshBtnSize * 0.75f
                        val inRefreshArea = offset.y > refreshBtnY - refreshBtnSize &&
                                offset.x > refreshBtnX - refreshBtnSize &&
                                offset.y < refreshBtnY + refreshBtnSize &&
                                offset.x < refreshBtnX + refreshBtnSize
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
        // ========== 绘制刷新按钮（右下角） ==========
        run {
            val btnSize = 60f * density
            val btnX = size.width - btnSize * 0.75f
            val btnY = size.height - btnSize * 0.75f
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

        // ========== 绘制切换男女按钮（底部中间，和刷新键一行、和关机键一列） ==========
        run {
            val btnSize = 60f * density
            val btnX = size.width / 2f
            val btnY = size.height - btnSize * 0.75f
            val btnRadius = 16f * density
            val breath = 0.85f + 0.15f * kotlin.math.sin(System.currentTimeMillis() / 500f)

            // 按钮透明度和颜色
            val isSwitching = expressionManager.isGenderSwitching()
            val btnAlpha = when {
                isSwitching -> 0.5f  // 切换中半透明
                else -> 0.4f * breath
            }
            val btnBgColor = when {
                isSwitching -> Color(0xFFE0E0E0)
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

            // 性别切换图标（左右箭头 + 中间圆点，表示切换）
            val iconColor = when {
                isSwitching -> Color(0xFF9E9E9E).copy(alpha = btnAlpha)
                else -> Color(0xFF616161).copy(alpha = btnAlpha)
            }
            val iconHalfWidth = btnSize * 0.25f
            val iconHalfHeight = btnSize * 0.12f

            // 左箭头
            drawLine(
                color = iconColor,
                start = Offset(btnX - iconHalfWidth * 0.3f, btnY - iconHalfHeight),
                end = Offset(btnX - iconHalfWidth, btnY),
                strokeWidth = 3.5f * density,
                cap = StrokeCap.Round
            )
            drawLine(
                color = iconColor,
                start = Offset(btnX - iconHalfWidth * 0.3f, btnY + iconHalfHeight),
                end = Offset(btnX - iconHalfWidth, btnY),
                strokeWidth = 3.5f * density,
                cap = StrokeCap.Round
            )

            // 右箭头
            drawLine(
                color = iconColor,
                start = Offset(btnX + iconHalfWidth * 0.3f, btnY - iconHalfHeight),
                end = Offset(btnX + iconHalfWidth, btnY),
                strokeWidth = 3.5f * density,
                cap = StrokeCap.Round
            )
            drawLine(
                color = iconColor,
                start = Offset(btnX + iconHalfWidth * 0.3f, btnY + iconHalfHeight),
                end = Offset(btnX + iconHalfWidth, btnY),
                strokeWidth = 3.5f * density,
                cap = StrokeCap.Round
            )

            // 中间连接线
            drawLine(
                color = iconColor,
                start = Offset(btnX - iconHalfWidth * 0.3f, btnY),
                end = Offset(btnX + iconHalfWidth * 0.3f, btnY),
                strokeWidth = 3.5f * density,
                cap = StrokeCap.Round
            )

            // 切换中动画：旋转效果
            if (isSwitching) {
                val rotation = (System.currentTimeMillis() / 10f) % 360f
                // 可以在这里添加旋转动画，暂时用简单的呼吸效果代替
            }
        }

        // ========== 绘制设置按钮（左下角，原模式切换按钮升级为设置入口） ==========
        run {
            val btnSize = 50f * density
            val btnX = btnSize * 0.75f
            val btnY = size.height - btnSize * 0.75f
            val btnRadius = 14f * density
            val breath = 0.85f + 0.15f * kotlin.math.sin(System.currentTimeMillis() / 600f)
            val alpha = 0.35f * breath

            // 按钮背景：圆角方形
            drawRoundRect(
                color = Color(0xFF1A1A2E).copy(alpha = alpha + 0.15f),
                topLeft = Offset(btnX - btnSize / 2f, btnY - btnSize / 2f),
                size = Size(btnSize, btnSize),
                cornerRadius = CornerRadius(btnRadius, btnRadius),
                style = Fill
            )
            // 边框
            drawRoundRect(
                color = if (isSettingsPanelVisible) {
                    Color(0xFF4FC3F7).copy(alpha = alpha + 0.25f)
                } else {
                    Color(0xFF9E9E9E).copy(alpha = alpha)
                },
                topLeft = Offset(btnX - btnSize / 2f, btnY - btnSize / 2f),
                size = Size(btnSize, btnSize),
                cornerRadius = CornerRadius(btnRadius, btnRadius),
                style = Stroke(width = 2f * density)
            )

            // 齿轮图标（简化版：外圈 + 中心圆 + 8个齿）
            val iconColor = if (isSettingsPanelVisible) {
                Color(0xFF4FC3F7).copy(alpha = alpha + 0.35f)
            } else {
                Color(0xFFE0E0E0).copy(alpha = alpha + 0.2f)
            }
            val outerR = btnSize * 0.22f
            val innerR = btnSize * 0.10f
            val toothLength = btnSize * 0.06f

            // 绘制 8 个齿（上下左右 + 四个对角）
            val toothWidth = btnSize * 0.04f
            for (i in 0 until 8) {
                val angle = i * 45f
                val rad = Math.toRadians(angle.toDouble()).toFloat()
                val cos = kotlin.math.cos(rad)
                val sin = kotlin.math.sin(rad)

                // 齿的位置：从外圈向外延伸
                val startX = btnX + cos * outerR
                val startY = btnY + sin * outerR
                val endX = btnX + cos * (outerR + toothLength)
                val endY = btnY + sin * (outerR + toothLength)

                drawLine(
                    color = iconColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = toothWidth,
                    cap = StrokeCap.Round
                )
            }

            // 外圈圆
            drawCircle(
                color = iconColor,
                radius = outerR,
                center = Offset(btnX, btnY),
                style = Stroke(width = 2.5f * density)
            )

            // 中心圆
            drawCircle(
                color = iconColor,
                radius = innerR,
                center = Offset(btnX, btnY),
                style = Fill
            )
        }

        // ========== 绘制设置面板（覆盖在表情画布之上） ==========
        if (isSettingsPanelVisible) {
            val w = size.width
            val h = size.height
            val panelWidth = w * 0.7f
            val panelHeight = h * 0.6f
            val panelLeft = (w - panelWidth) / 2f
            val panelTop = (h - panelHeight) / 2f
            val panelRight = panelLeft + panelWidth
            val panelBottom = panelTop + panelHeight
            val cornerRadius = 20f * density

            // 半透明遮罩
            drawRect(
                color = Color(0x000000).copy(alpha = 0.5f),
                size = size
            )

            // 面板背景
            drawRoundRect(
                color = Color(0xFF1A1A2E).copy(alpha = 0.95f),
                topLeft = Offset(panelLeft, panelTop),
                size = Size(panelWidth, panelHeight),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Fill
            )

            // 面板边框
            drawRoundRect(
                color = Color(0xFF4FC3F7).copy(alpha = 0.5f),
                topLeft = Offset(panelLeft, panelTop),
                size = Size(panelWidth, panelHeight),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = 2f * density)
            )

            // 面板标题
            // 用 nativeCanvas 绘制文字
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val titlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#4FC3F7")
                textSize = 18f * density
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val titleY = panelTop + 35f * density
            nativeCanvas.drawText("音色选择", w / 2f, titleY, titlePaint)

            // 分隔线
            drawLine(
                color = Color(0xFF4FC3F7).copy(alpha = 0.5f),
                start = Offset(panelLeft + 20f * density, titleY + 15f * density),
                end = Offset(panelRight - 20f * density, titleY + 15f * density),
                strokeWidth = 1f * density
            )

            // 音色列表
            // 获取当前人格对应的音色列表
            val currentPersona = app.llmManager.getPersona()
            val config = com.roboparty.rpoexpression.util.PersonaRegistry.getConfig(currentPersona)
            val voiceList = com.roboparty.rpoexpression.util.VoiceLibrary.getVoices(config.expressionStyle)
            val currentVoiceId = app.voicePreferenceStore.getSelectedVoice(currentPersona)
                ?: com.roboparty.rpoexpression.util.VoiceLibrary.getDefault(config.expressionStyle).voiceId

            // 音色按钮布局：纵向排列
            val voiceBtnHeight = 50f * density
            val voiceBtnMargin = 10f * density
            val voiceListTop = titleY + 35f * density

            val namePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#E0E0E0")
                textSize = 14f * density
                textAlign = android.graphics.Paint.Align.LEFT
            }
            val tagPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#9E9E9E")
                textSize = 11f * density
                textAlign = android.graphics.Paint.Align.LEFT
            }
            val trialPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#4FC3F7")
                textSize = 12f * density
                textAlign = android.graphics.Paint.Align.CENTER
            }

            for (i in voiceList.indices) {
                val voice = voiceList[i]
                val btnTop = voiceListTop + i * (voiceBtnHeight + voiceBtnMargin)
                val btnBottom = btnTop + voiceBtnHeight
                val btnLeft = panelLeft + 20f * density
                val btnRight = panelRight - 20f * density
                val btnWidth = btnRight - btnLeft

                val isSelected = voice.voiceId == currentVoiceId

                // 按钮背景
                drawRoundRect(
                    color = if (isSelected) {
                        Color(0xFF4FC3F7).copy(alpha = 0.2f)
                    } else {
                        Color(0xFF2A2A3E).copy(alpha = 0.5f)
                    },
                    topLeft = Offset(btnLeft, btnTop),
                    size = Size(btnWidth, voiceBtnHeight),
                    cornerRadius = CornerRadius(10f * density, 10f * density),
                    style = Fill
                )

                // 选中边框
                if (isSelected) {
                    drawRoundRect(
                        color = Color(0xFF4FC3F7).copy(alpha = 0.8f),
                        topLeft = Offset(btnLeft, btnTop),
                        size = Size(btnWidth, voiceBtnHeight),
                        cornerRadius = CornerRadius(10f * density, 10f * density),
                        style = Stroke(width = 1.5f * density)
                    )
                }

                // 选中圆点
                val dotRadius = 6f * density
                val dotX = btnLeft + 15f * density
                val dotY = btnTop + voiceBtnHeight / 2f
                drawCircle(
                    color = if (isSelected) Color(0xFF4FC3F7) else Color(0xFF616161),
                    radius = dotRadius,
                    center = Offset(dotX, dotY),
                    style = if (isSelected) Fill else Stroke(width = 1.5f * density)
                )

                // 音色名称
                val nameX = btnLeft + 35f * density
                val nameY = btnTop + 20f * density
                namePaint.color = if (isSelected) {
                    android.graphics.Color.parseColor("#4FC3F7")
                } else {
                    android.graphics.Color.parseColor("#E0E0E0")
                }
                nativeCanvas.drawText(voice.displayName, nameX, nameY, namePaint)

                // 风格标签
                val tagX = btnLeft + 35f * density
                val tagY = btnTop + 38f * density
                nativeCanvas.drawText(voice.tag, tagX, tagY, tagPaint)

                // 试听按钮
                val trialBtnWidth = 50f * density
                val trialBtnHeight = 28f * density
                val trialBtnLeft = btnRight - trialBtnWidth - 10f * density
                val trialBtnTop = btnTop + (voiceBtnHeight - trialBtnHeight) / 2f
                val trialBtnRight = btnRight - 10f * density
                val trialBtnBottom = trialBtnTop + trialBtnHeight

                drawRoundRect(
                    color = Color(0xFF4FC3F7).copy(alpha = 0.3f),
                    topLeft = Offset(trialBtnLeft, trialBtnTop),
                    size = Size(trialBtnWidth, trialBtnHeight),
                    cornerRadius = CornerRadius(6f * density, 6f * density),
                    style = Fill
                )

                // 试听文字
                val trialX = trialBtnLeft + trialBtnWidth / 2f
                val trialY = trialBtnTop + trialBtnHeight / 2f + 4f * density
                nativeCanvas.drawText("试听", trialX, trialY, trialPaint)
            }

            // 底部提示
            val hintPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#616161")
                textSize = 10f * density
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val hintY = panelBottom - 20f * density
            nativeCanvas.drawText("点击左侧选择，点击右侧试听", w / 2f, hintY, hintPaint)

            // 关闭按钮提示（右上角）
            val closeBtnSize = 30f * density
            val closeBtnX = panelRight - 25f * density
            val closeBtnY = panelTop + 25f * density
            drawLine(
                color = Color(0xFF9E9E9E).copy(alpha = 0.8f),
                start = Offset(closeBtnX - closeBtnSize/2, closeBtnY - closeBtnSize/2),
                end = Offset(closeBtnX + closeBtnSize/2, closeBtnY + closeBtnSize/2),
                strokeWidth = 2f * density,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF9E9E9E).copy(alpha = 0.8f),
                start = Offset(closeBtnX + closeBtnSize/2, closeBtnY - closeBtnSize/2),
                end = Offset(closeBtnX - closeBtnSize/2, closeBtnY + closeBtnSize/2),
                strokeWidth = 2f * density,
                cap = StrokeCap.Round
            )
        }
    }


}


