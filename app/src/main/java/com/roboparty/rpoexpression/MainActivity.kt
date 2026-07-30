/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.roboparty.rpoexpression.animation.ExpressionCanvas
import com.roboparty.rpoexpression.audio.AudioManager
import com.roboparty.rpoexpression.audio.AsrManager
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.llm.LlmManager
import com.roboparty.rpoexpression.expression.DemoSequenceManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.integration.ModuleStateManager
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.sensor.VolumeManager
import com.roboparty.rpoexpression.ui.ConnectionStatusIndicator
import com.roboparty.rpoexpression.ui.theme.RpoRobotExpressionTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import androidx.compose.ui.Modifier
import com.roboparty.rpoexpression.expression.ExpressionStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.foundation.verticalScroll
import com.roboparty.rpoexpression.vision.DebugVisionOverlay
import com.roboparty.rpoexpression.vision.DebugVisionState
import com.roboparty.rpoexpression.vision.VisionManager
import com.roboparty.rpoexpression.audio.KwsManager
/**
 * 主Activity
 * 实现全屏沉浸式表情显示，完成ASR→LLM→TTS全链路对话
 */
private const val TAG = "MainActivity"
class MainActivity : ComponentActivity() {


    private lateinit var app: RpoExpressionApp
    private lateinit var expressionManager: ExpressionManager
    private lateinit var communicationManager: CommunicationManager
    private lateinit var brightnessManager: BrightnessManager
    private lateinit var volumeManager: VolumeManager
    private lateinit var demoSequenceManager: DemoSequenceManager
    private lateinit var audioManager: AudioManager
    private lateinit var llmManager: LlmManager
    private lateinit var visionManager: VisionManager
    private val REQUEST_RECORD_AUDIO_PERMISSION = 1001
    private val REQUEST_CAMERA_PERMISSION = 1002
    private val REQUEST_ALL_PERMISSIONS = 1003

    // 权限申请后待执行的操作：是否为ASR单独测试模式
    private var pendingAsrOnlyTest = false

    // ========== 语音交互状态机 ==========
    /**
     * 语音交互状态（6状态）：
     * DORMANT    - 休眠监听：KWS本地唤醒词检测，零网络，低功耗
     * AWAKENING  - 唤醒确认：播放唤醒音效，切换KWS→ASR
     * LISTENING  - 对话聆听：ASR全量识别中
     * PROCESSING - LLM推理中
     * SPEAKING   - TTS播报中
     * COOLDOWN   - 冷却防抖：3秒后回到DORMANT
     */
    private enum class VoiceState {
        DORMANT, AWAKENING, LISTENING, PROCESSING, SPEAKING, COOLDOWN
    }
    @Volatile
    private var currentVoiceState = VoiceState.DORMANT

    // 连续对话相关
    private var isInContinuousConversation = false // 是否处于连续对话模式（唤醒后无需重复唤醒）
    private val exitWords = listOf(
        "结束对话", "结束", "拜拜", "拜", "再见", "再会", "退下"  // 结束词
    )

    // ========== 连续对话空闲超时：一段时间没说话自动退出连续对话 ==========
    private val CONTINUOUS_CONVERSATION_TIMEOUT_MS = 30000L // 30秒
    private var continuousConversationTimeoutJob: kotlinx.coroutines.Job? = null

    // ========== TTS播放结束回调监听器 ==========
    private var ttsFinishListener: (() -> Unit)? = null

    // ========== 人脸注册状态：注册期间阻止TTS finish/barge-in干扰 ==========
    @Volatile
    private var isFaceRegistering = false

    /**
     * 结束词宽松匹配
     * 规则：
     * 1. 只判断清洗后的文本
     * 2. 输入包含结束词才命中
     */
    private fun isExitWord(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.length < 2) return false
        return exitWords.any { word ->
            trimmed.contains(word, ignoreCase = true)
        }
    }

    /**
     * 是否是关机关键词
     */
    private fun isPowerOffWord(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed == "关机" || trimmed.contains("关机")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化
        app = application as RpoExpressionApp
        expressionManager = app.expressionManager
        communicationManager = app.communicationManager
        brightnessManager = app.brightnessManager
        volumeManager = app.volumeManager
        demoSequenceManager = app.demoSequenceManager
        // 复用Application中已初始化的实例
        audioManager = app.audioManager
        llmManager = app.llmManager
        visionManager = app.visionManager

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RpoRobotExpressionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 全屏表情画布（底层）
                        ExpressionCanvas(modifier = Modifier.fillMaxSize())

                        // 右上角连接状态指示器（上层）
                        ConnectionStatusIndicator(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 12.dp, end = 12.dp),
                            commManager = communicationManager,
                            headControlManager = app.headControlManager
                        )

                        // 左上角：调试窗口 + 开关按钮
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 12.dp, start = 12.dp)
                        ) {
                            // 开关按钮（始终可见）
                            val debugVisible by DebugVisionState.visible.collectAsState()
                            androidx.compose.material3.TextButton(
                                onClick = { DebugVisionState.toggleVisible() },
                                modifier = Modifier
                                    .background(
                                        if (debugVisible) ComposeColor(0x882196F3) else ComposeColor(0x66000000),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 2.dp)
                            ) {
                                Text(
                                    text = if (debugVisible) "👁 关闭视角" else "👁 机器人视角",
                                    color = ComposeColor.White,
                                    fontSize = 9.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }

                            // 调试窗口（仅在开启时显示）
                            DebugVisionOverlay()
                        }
                    }
                }
            }
        }

        // 全屏沉浸式设置
        enableFullscreenMode()

        // 启动各个管理器
        startManagers()

        initTtsFinishCallback()
        initPowerStateCallback()

        // 启动自动申请权限，开启摄像头和麦克风
        autoStartPerception()
        // 注册ADB广播接收器，支持命令行启动/停止Demo
        val demoFilter = IntentFilter().apply {
            addAction("com.roboparty.rpoexpression.START_DEMO")
            addAction("com.roboparty.rpoexpression.STOP_DEMO")
            addAction("com.roboparty.rpoexpression.SOFT_RESET")
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.roboparty.rpoexpression.START_DEMO" -> {
                        Log.i(TAG, "Received START_DEMO broadcast")
                        demoSequenceManager.triggerStandardDemo()
                    }
                    "com.roboparty.rpoexpression.STOP_DEMO" -> {
                        Log.i(TAG, "Received STOP_DEMO broadcast")
                        demoSequenceManager.stopDemo()
                        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                    }
                    "com.roboparty.rpoexpression.SOFT_RESET" -> {
                        Log.i(TAG, "Received SOFT_RESET broadcast, performing soft reset")
                        performSoftReset()
                    }
                }
            }
        }, demoFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * 启动KWS关键词唤醒监听（DORMANT状态）
     *
     * 新架构：
     * - 使用本地KWS引擎（sherpa-onnx），完全离线，零网络
     * - 独立AudioRecord通道，与ASR严格互斥
     * - 4层误触防护：置信度→冷却→TTS回声保护→二次确认
     * - 低功耗：亮屏200ms推理，息屏500ms推理
     *
     * 对比旧架构（ASR模糊匹配）：
     * - 唤醒延迟：1.5-3s → <200ms（↓90%）
     * - CPU占用：8-12% → 1-3%（↓80%）
     * - 网络流量：50-100KB/s → 0（↓100%）
     */
    private fun startWakeUpListening() {
        if (currentVoiceState == VoiceState.DORMANT && audioManager.isKwsListening()) {
            return // 已经在KWS监听
        }

        // 退出连续对话模式
        if (isInContinuousConversation) {
            exitContinuousConversation()
        }

        // 确保ASR已停止
        try {
            audioManager.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Stop ASR before KWS failed: ${e.message}")
        }

        currentVoiceState = VoiceState.DORMANT
        Log.i(TAG, "Starting KWS wake word listening (DORMANT state)")

        try {
            audioManager.startKwsListening(object : KwsManager.KwsCallback {
                override fun onKeywordDetected(keyword: String, confidence: Float) {
                    runOnUiThread {
                        Log.i(TAG, "Wake word detected: '$keyword', confidence=$confidence")
                        triggerWakeUp()
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        Log.e(TAG, "KWS error: $error, restarting in 1s...")
                        currentVoiceState = VoiceState.COOLDOWN
                        lifecycleScope.launch {
                            delay(1000)
                            startWakeUpListening()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Start KWS failed", e)
            // 启动失败，延迟重试
            lifecycleScope.launch {
                delay(1000)
                startWakeUpListening()
            }
        }
    }

    /**
     * 触发唤醒（AWAKENING状态）
     *
     * 状态切换时序：
     * 1. 停止KWS监听，释放AudioRecord
     * 2. 播放唤醒音效"嗯？"，给用户即时反馈
     * 3. 800ms延迟（等待AudioRecord完全释放 + WS连接建立）
     * 4. 启动ASR，进入LISTENING状态
     *
     * 这800ms是KWS→ASR切换的空白期，用唤醒音效填补，
     * 既给用户"我听到了"的即时反馈，又掩盖了切换延迟
     */
    private fun triggerWakeUp() {
        // 防止重复触发
        if (currentVoiceState != VoiceState.DORMANT) {
            Log.w(TAG, "triggerWakeUp ignored, current state=$currentVoiceState")
            return
        }

        currentVoiceState = VoiceState.AWAKENING
        isInContinuousConversation = true
        Log.i(TAG, "Wake up triggered! State: DORMANT → AWAKENING")

        // 停止KWS，释放录音通道给ASR
        audioManager.stopKwsListening()

        // 唤醒应答"嗯？"是短系统提示音（约300ms），不需要barge-in打断功能
        // 临时关闭barge-in，避免barge-in ASR残留阻塞后续正常ASR启动
        val wasBargeInEnabled = audioManager.bargeInEnabled
        audioManager.bargeInEnabled = false

        // 唤醒应答表情 + 欢迎语（根据人脸识别结果选择称呼）
        val greeting = app.lastRecognizedName?.let { "${it}你好呀！" } ?: "你好，请问有什么事情吗？"
        expressionManager.requestExpression(ExpressionType.RESPONSE, duration = 800, force = true)
        audioManager.startSpeaking(greeting)

        // 启动连续对话超时计时
        resetContinuousConversationTimeout()

        // 兜底：1.2秒后如果还没进入LISTENING状态（TTS回调丢失等异常），强制启动ASR
        // 正常流程：TTS"嗯？"播完 → ttsFinishListener → 自动启动ASR（约800ms）
        lifecycleScope.launch {
            delay(1200)
            // 恢复barge-in设置
            audioManager.bargeInEnabled = wasBargeInEnabled
            if (currentVoiceState == VoiceState.AWAKENING) {
                Log.w(TAG, "Fallback: TTS finish callback missed, force starting ASR")
                currentVoiceState = VoiceState.LISTENING
                checkRecordPermissionAndStartAsr()
            }
        }
    }

    /**
     * 自动启动感知模块：申请权限后开启摄像头和麦克风
     */
    private fun autoStartPerception() {
        // 先申请录音权限
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (hasAudio && hasCamera) {
            // 权限都有，直接启动
            startFaceDetect()
            startWakeUpListening() // 启动语音唤醒
        } else {
            // 申请权限
            val permissions = mutableListOf<String>()
            if (!hasAudio) permissions.add(Manifest.permission.RECORD_AUDIO)
            if (!hasCamera) permissions.add(Manifest.permission.CAMERA)
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_ALL_PERMISSIONS)
        }
    }

    /**
     * 启用全屏沉浸式模式
     */
    private fun enableFullscreenMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * 启动所有管理器
     */
    private fun startManagers() {
        expressionManager.start()
        communicationManager.start()
        brightnessManager.attachActivity(this@MainActivity)
        brightnessManager.start()
        // 设置亮度/音量边界眨眼回调
        brightnessManager.onBoundaryReached = { expressionManager.performBlink() }
        volumeManager.onBoundaryReached = { expressionManager.performBlink() }

        // 监听亮度变化，更新屏幕亮度
        lifecycleScope.launch {
            brightnessManager.brightness.collect { brightness ->
                brightnessManager.setBrightness(this@MainActivity, brightness)
            }
        }
    }

    /**
     * TTS播报结束回调注册
     * 修复原来只在onCreate执行一次的问题
     */

    /**
     * 电源状态回调：关机时停止所有传感器，开机时启动
     */
    private fun initPowerStateCallback() {
        expressionManager.addPowerStateCallback { isPowerOff ->
            lifecycleScope.launch {
                if (isPowerOff) {
                    // 关机：停止所有外设
                    Log.i(TAG, "Power off: stopping all sensors...")
                    try {
                        audioManager.stopListening()
                        audioManager.stopKwsListening()
                        audioManager.stopSpeaking()
                        currentVoiceState = VoiceState.COOLDOWN
                        isInContinuousConversation = false
                        continuousConversationTimeoutJob?.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "Stop audio failed", e)
                    }
                    try {
                        visionManager.stopFaceDetect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Stop vision failed", e)
                    }
                    try {
                        communicationManager.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Stop communication failed", e)
                    }
                    Log.i(TAG, "Power off complete")
                } else {
                    // 开机：启动所有外设
                    Log.i(TAG, "Power on: starting all sensors...")
                    try {
                        communicationManager.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Start communication failed", e)
                    }
                    try {
                        checkCameraPermissionAndStartFaceDetect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Start vision failed", e)
                    }
                    delay(1000)
                    try {
                        startWakeUpListening()
                    } catch (e: Exception) {
                        Log.e(TAG, "Start wake up failed", e)
                    }
                    Log.i(TAG, "Power on complete")
                }
            }
        }
    }
    private fun initTtsFinishCallback() {
        ttsFinishListener = action@{
            // 人脸注册期间的TTS提示音播放结束不应该触发连续对话/唤醒流程
            if (isFaceRegistering) {
                Log.d(TAG, "TTS finish during face registration, suppressed")
                return@action
            }

            ModuleStateManager.onTtsFinishSpeaking()

            lifecycleScope.launch {
                delay(500)

                if (isInContinuousConversation) {
                    // 连续对话模式：自动继续聆听，无需唤醒词
                    startAsrRecognition()
                } else {
                    // 非连续对话：回到唤醒监听状态
                    startWakeUpListening()
                }
            }
        }
        // 注册到 AudioManager，TTS 结束时会自动触发
        audioManager.setOnSpeakFinishListener(object : AudioManager.OnSpeakFinishListener {
            override fun onSpeakFinsh() {
                runOnUiThread {
                    ttsFinishListener?.invoke()
                }
            }
        })

        // ===== 注册打断（Barge-in）回调 =====
        audioManager.setOnBargeInListener(object : AudioManager.OnBargeInListener {
            override fun onBargeIn(text: String) {
                runOnUiThread {
                    Log.d(TAG, "Barge-in received, text=[$text]")
                    // 人脸注册期间忽略打断，避免干扰采集流程
                    if (isFaceRegistering) {
                        Log.d(TAG, "Barge-in suppressed during face registration")
                        return@runOnUiThread
                    }
                    // 1. 取消正在进行的LLM请求
                    llmManager.cancelRequest()
                    // 2. 进入连续对话模式（如果还没进入）
                    if (!isInContinuousConversation) {
                        isInContinuousConversation = true
                    }
                    resetContinuousConversationTimeout()

                    // 3. 如果已有识别文本，直接处理；否则启动新的ASR识别让用户继续说
                    if (text.isNotBlank()) {
                        processAsrResult(text, asrOnly = false)
                    } else {
                        // speech_started触发的打断：用户刚开口，启动完整ASR识别接收后续内容
                        startAsrRecognition()
                    }
                }
            }
        })
    }

    /**
     * 重置连续对话空闲超时
     */
    private fun resetContinuousConversationTimeout() {
        continuousConversationTimeoutJob?.cancel()

        continuousConversationTimeoutJob = lifecycleScope.launch {
            delay(CONTINUOUS_CONVERSATION_TIMEOUT_MS)

            if (isInContinuousConversation && !audioManager.isSpeaking() && !audioManager.isListening()) {
                exitContinuousConversation()
                expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                startWakeUpListening()
            }
        }
    }

    /**
     * 退出连续对话模式
     */
    private fun exitContinuousConversation() {
        isInContinuousConversation = false
        continuousConversationTimeoutJob?.cancel()
        continuousConversationTimeoutJob = null
    }

    override fun onResume() {
        super.onResume()
        enableFullscreenMode()

        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (hasCamera && !visionManager.isDetecting()) {
            startFaceDetect()
        }

        if (!hasAudio || audioManager.isSpeaking() || audioManager.isListening() || audioManager.isKwsListening()) {
            return
        }

        if (isInContinuousConversation) {
            // 保持连续对话，继续聆听
            startAsrRecognition()
        } else {
            // 回到KWS唤醒监听
            startWakeUpListening()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退后台停止所有前台任务，彻底释放资源
        currentVoiceState = VoiceState.COOLDOWN
        isInContinuousConversation = false // 退出连续对话模式
        continuousConversationTimeoutJob?.cancel()
        continuousConversationTimeoutJob = null

        try {
            audioManager.stopListening()
            audioManager.stopKwsListening()
            audioManager.stopSpeaking()
        } catch (e: Exception) {
            // 忽略停止异常
        }
        try {
            visionManager.stopFaceDetect()
        } catch (e: Exception) {
            // 忽略停止异常
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullscreenMode()
        }
    }

    // 手柄组合键状态
    private var isSelectPressed = false
    private var isStartPressed = false

    // 手柄切换表情用的索引
    private var currentExpressionIndex = 0
    private val expressionList = ExpressionType.values().filter { it != ExpressionType.BLINK }.toList()

    /**
     * 处理手柄按键事件
     *
     * 按键映射总览：
     * ┌─────────────────────────────────────────────────┐
     * │ ABXY 核心操作区                                  │
     * │   A / X  → 开始语音对话                          │
     * │   B      → 停止所有操作，回到待机                 │
     * │   Y      → 循环切换渲染风格                       │
     * ├─────────────────────────────────────────────────┤
     * │ 肩键 表情快捷区                                   │
     * │   L1     → 上一个表情                            │
     * │   R1     → 下一个表情                            │
     * │   L2     → 快捷触发「开心」表情                   │
     * │   R2     → 快捷触发「疑惑」表情                   │
     * ├─────────────────────────────────────────────────┤
     * │ 十字键 参数调节区                                 │
     * │   ↑ 上   → 表情强度 +10%                         │
     * │   ↓ 下   → 表情强度 -10%                         │
     * │   ← 左   → 上一种渲染风格                        │
     * │   → 右   → 下一种渲染风格                        │
     * ├─────────────────────────────────────────────────┤
     * │ 菜单键 系统功能区                                 │
     * │   Start  → 开启/关闭人脸识别                     │
     * │   Select → 播放演示Demo序列                      │
     * │   L3     → 开关「机器人视角」调试窗口              │
     * └─────────────────────────────────────────────────┘
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // ========== ABXY 核心操作区 ==========
                // X/A键：开始语音对话
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_A -> {
                    checkRecordPermissionAndStartAsr()
                    return true
                }
                // B键：停止所有操作，回到待机
                KeyEvent.KEYCODE_BUTTON_B -> {
                    demoSequenceManager.stopDemo()
                    exitContinuousConversation() // 退出连续对话模式
                    audioManager.stopSpeaking()
                    audioManager.stopListening()
                    llmManager.cancelRequest()
                    visionManager.stopFaceDetect()
                    expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                    return true
                }
                // Y键：循环切换渲染风格
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    val styles = ExpressionStyle.values()
                    val currentStyle = expressionManager.getCurrentStyle()
                    val nextIndex = (currentStyle.ordinal + 1) % styles.size
                    expressionManager.setStyle(styles[nextIndex])
                    return true
                }

                // ========== 肩键 表情快捷区 ==========
                // L1键：上一个表情
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    currentExpressionIndex = (currentExpressionIndex - 1 + expressionList.size) % expressionList.size
                    expressionManager.requestExpression(expressionList[currentExpressionIndex], force = true)
                    return true
                }
                // R1键：下一个表情
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    currentExpressionIndex = (currentExpressionIndex + 1) % expressionList.size
                    expressionManager.requestExpression(expressionList[currentExpressionIndex], force = true)
                    return true
                }
                // L2键：快捷触发开心表情（正向反馈）
                KeyEvent.KEYCODE_BUTTON_L2 -> {
                    expressionManager.requestExpression(ExpressionType.HAPPY, duration = 1500)
                    return true
                }
                // R2键：快捷触发疑惑表情（疑问反馈）
                KeyEvent.KEYCODE_BUTTON_R2 -> {
                    expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 1200)
                    return true
                }

                // ========== 十字键 参数调节区 ==========
                // 十字键上：增加表情强度
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val currentIntensity = (expressionManager.expressionState.value.intensity + 10).coerceIn(0, 100)
                    expressionManager.requestExpression(
                        type = expressionManager.expressionState.value.type,
                        intensity = currentIntensity,
                        force = true
                    )
                    return true
                }
                // 十字键下：减小表情强度
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val currentIntensity = (expressionManager.expressionState.value.intensity - 10).coerceIn(0, 100)
                    expressionManager.requestExpression(
                        type = expressionManager.expressionState.value.type,
                        intensity = currentIntensity,
                        force = true
                    )
                    return true
                }
                // 十字键左：上一种渲染风格
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val styles = ExpressionStyle.values()
                    val currentStyle = expressionManager.getCurrentStyle()
                    val prevIndex = (currentStyle.ordinal - 1 + styles.size) % styles.size
                    expressionManager.setStyle(styles[prevIndex])
                    return true
                }
                // 十字键右：下一种渲染风格
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val styles = ExpressionStyle.values()
                    val currentStyle = expressionManager.getCurrentStyle()
                    val nextIndex = (currentStyle.ordinal + 1) % styles.size
                    expressionManager.setStyle(styles[nextIndex])
                    return true
                }

                // ========== 菜单键 系统功能区 ==========
                // Start键：开始/停止人脸识别
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                    if (visionManager.isDetecting()) {
                        visionManager.stopFaceDetect()
                    } else {
                        checkCameraPermissionAndStartFaceDetect()
                    }
                    return true
                }
                // Select键：播放演示Demo
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SEARCH -> {
                    demoSequenceManager.triggerStandardDemo()
                    return true
                }
                // L3（左摇杆按下）：开关调试视角窗口
                KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                    DebugVisionState.toggleVisible()
                    Log.i(TAG, "Debug vision overlay toggled: ${DebugVisionState.visible.value}")
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    // 返回键不拦截，交给系统处理
                    return super.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 检查录音权限，开始完整对话流程
     */
    internal fun checkRecordPermissionAndStartAsr() {
        pendingAsrOnlyTest = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            isInContinuousConversation = true // 手动触发也进入连续对话模式
            resetContinuousConversationTimeout() // 重置空闲超时
            startAsrRecognition(pendingAsrOnlyTest)
        }
    }

    /**
     * 检查录音权限，开始ASR单独测试
     */
    internal fun checkRecordPermissionAndStartAsrOnly() {
        pendingAsrOnlyTest = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            startAsrRecognition(asrOnly = true)
        }
    }

    /**
     * 开始ASR识别
     */
    private fun startAsrRecognition(asrOnly: Boolean = false) {
        // 恢复barge-in功能（唤醒时临时关闭了）
        audioManager.bargeInEnabled = true

        // 如果正在听（可能是barge-in残留的ASR会话），先停止再启动新的
        if (audioManager.isListening()) {
            Log.d(TAG, "startAsrRecognition: ASR already listening (barge-in residue?), stopping first...")
            audioManager.stopListening()
        }
        // 如果正在说话（TTS刚结束状态可能还没更新），先停止TTS再继续
        if (audioManager.isSpeaking()) {
            Log.d(TAG, "startAsrRecognition: TTS still speaking, stopping first...")
            audioManager.stopSpeaking()
        }

        // 更新状态为LISTENING
        currentVoiceState = VoiceState.LISTENING
        Log.i(TAG, "State: → LISTENING, starting ASR")

        // ========== 先重置连续对话空闲超时，避免每次说话后很快退出 ==========
        if (isInContinuousConversation) {
            resetContinuousConversationTimeout()
        }

        // 注意：识别中表情（瞳孔放大）由AsrManager在真正开始录音时触发
        // 这里不提前调用，避免WebSocket连接期间用户误以为可以说话

        // 等待100ms确保旧的AudioRecord和WebSocket完全释放，避免资源竞争
        lifecycleScope.launch {
            delay(100)
            // 延迟后再次检查状态，避免期间状态已变化（比如被关机/打断）
            if (currentVoiceState != VoiceState.LISTENING) {
                Log.d(TAG, "startAsrRecognition: state changed during delay, aborting")
                return@launch
            }
            audioManager.startListening(object : AsrManager.AsrResultCallback {
                override fun onResult(text: String) {
                    runOnUiThread {
                        // ASR结束表情由AsrManager内部统一触发，这里不重复调用
                        processAsrResult(text, asrOnly)
                    }
                }

                override fun onError(errorMsg: String) {
                    runOnUiThread {
                        // ASR错误表情由AsrManager内部统一触发，这里不重复调用
                        if (isInContinuousConversation) {
                            // 连续对话模式：出错后自动继续聆听
                            expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 1000, force = true)
                            lifecycleScope.launch {
                                delay(1000)
                                startAsrRecognition()
                            }
                        } else {
                            expressionManager.requestExpression(ExpressionType.CONFUSED)
                            audioManager.startSpeaking("抱歉，我没有听清，请再说一遍")
                        }
                    }
                }
            })
        }
    }

    /**
     * 统一处理ASR识别结果（正常识别 / 打断场景共用）
     */
    private fun processAsrResult(text: String, asrOnly: Boolean = false) {
        if (asrOnly) {
            audioManager.startSpeaking("识别结果：$text")
            return
        }

        if (text.isNotEmpty()) {
            // 重置连续对话空闲超时
            if (isInContinuousConversation) {
                resetContinuousConversationTimeout()
            }

            // 统一清洗文本
            val raw = text.lowercase()
                .replace(Regex("[^a-z\\u4e00-\\u9fa5]"), "")

            // 结束词检测
            if (isInContinuousConversation && isExitWord(raw)) {
                exitContinuousConversation()
                llmManager.exitConversation() // 同时清空LLM对话历史

                expressionManager.requestExpression(ExpressionType.HAPPY, duration = 1200, force = true)
                audioManager.startSpeaking("好的，随时可以叫我，拜拜")
                return
            }


            // 关机关键词检测
            if (isPowerOffWord(raw)) {
                expressionManager.powerOff()
                exitContinuousConversation()
                llmManager.exitConversation() // 关机也清空对话
                audioManager.stopSpeaking()
                return
            }
            sendTextToLlm(text)
        } else {
            // 未识别到内容
            if (isInContinuousConversation) {
                // 连续对话模式：没听清就继续听，不退出
                expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 800, force = true)
                lifecycleScope.launch {
                    delay(800)
                    startAsrRecognition()
                }
            } else {
                expressionManager.requestExpression(ExpressionType.IDLE, force = true)
            }
        }
    }

    /**
     * 发送文本给LLM（流式流水线：LLM SSE → 逐句 TTS）
     *
     * 优化后：LLM 边生成边回调 → 收到第一句话立刻开始播 → 后续边播边收
     * 新增：多轮对话记忆管理
     * - 自动维护对话历史（由LlmManager内部处理）
     * - 新增指令："退出对话"/"清除记忆" → 清空短期记忆
     * - 简单长期记忆："我叫XX" → 记住用户名
     *
     * 注意："再见"/"拜拜"等结束词已在ASR结果阶段拦截处理，不会走到这里
     */
    private fun sendTextToLlm(text: String) {
        if (text.isBlank()) {
            return
        }

        // 更新状态为PROCESSING（LLM推理中）
        currentVoiceState = VoiceState.PROCESSING

        val trimmedText = text.trim()

        // ========== 指令检测：清空记忆（"再见/拜拜"已在前面拦截） ==========
        if (trimmedText.contains("退出对话") || trimmedText.contains("清除记忆") ||
            trimmedText.contains("忘了我") || trimmedText.contains("重置对话")) {
            llmManager.exitConversation()
            exitContinuousConversation()
            audioManager.startSpeaking("好的，我已经清空对话记忆啦，我们重新开始吧~")
            expressionManager.requestExpression(ExpressionType.HAPPY)
            return
        }

        // ========== 简单长期记忆提取（关键词匹配）+ 人脸注册触发 ==========
        val namePattern1 = Regex("我叫([\\u4e00-\\u9fa5a-zA-Z]{2,4})")
        val namePattern2 = Regex("我是([\\u4e00-\\u9fa5a-zA-Z]{2,4})")
        val nameMatch = namePattern1.find(trimmedText) ?: namePattern2.find(trimmedText)
        if (nameMatch != null) {
            val userName = nameMatch.groupValues[1]
            val invalidNames = setOf("一个", "你的", "机器", "机器人", "AI", "ai", "你朋友", "学生", "好人")
            if (userName !in invalidNames) {
                llmManager.addLongTermMemory("用户的名字是$userName")
                Log.i(TAG, "Name command detected: $userName, starting face registration")

                // 触发人脸注册流程，注册流程自己处理TTS和表情，不走LLM
                startFaceRegistration(userName)
                return  // 关键：直接返回，不继续走LLM，避免表情和TTS被覆盖
            }
        }

        // ========== 开始请求LLM时重置超时，避免思考和回复期间误退出 ==========
        if (isInContinuousConversation) {
            resetContinuousConversationTimeout()
        }

        // 切换到思考表情
        ModuleStateManager.onLlmStartThinking()

        // 初始化流式播报会话（等第一个完整句子到达就开始播）
        audioManager.startStreaming()

        llmManager.sendQueryStream(text, object : LlmManager.StreamCallback {
            override fun onDelta(delta: String) {
                // LLM 增量文本：切到主线程喂给 TTS（TextToSpeech 必须主线程）
                runOnUiThread {
                    audioManager.feedStreamChunk(delta)
                }
            }

            override fun onComplete(fullText: String) {
                runOnUiThread {
                    ModuleStateManager.onLlmFinish()

                    if (isInContinuousConversation) {
                        resetContinuousConversationTimeout()
                    }

                    // 把 buffer 中剩余的碎片全部播出去
                    audioManager.finishStreaming()
                }
            }

            override fun onError(errorMsg: String) {
                runOnUiThread {
                    ModuleStateManager.onLlmFinish()

                    if (isInContinuousConversation) {
                        resetContinuousConversationTimeout()
                    }

                    // 停掉可能已经入队的半截内容
                    audioManager.stopSpeaking()
                    expressionManager.requestExpression(ExpressionType.SAD)
                    audioManager.startSpeaking("抱歉，我现在无法回答你的问题，请稍后再试")
                }
            }
        })
    }

    /**
     * 开始人脸注册流程
     *
     * 用户说"我叫XXX"后触发：
     * 1. 语音提示用户正对镜头
     * 2. 切换到识别中表情
     * 3. 调用FaceRegisterManager开始采集样本
     * 4. 注册过程中根据进度反馈
     * 5. 注册成功→开心表情+语音提示；失败→疑惑表情+提示重试
     */
    private fun startFaceRegistration(userName: String) {
        val registerManager = app.faceRegisterManager
        if (registerManager.isRegistering()) {
            Log.w(TAG, "Already registering, skip")
            return
        }

        Log.i(TAG, "Start face registration for: $userName")

        // 标记进入人脸注册状态，阻止TTS-finish/barge-in干扰流程
        isFaceRegistering = true

        // 停止ASR识别，释放音频通道给TTS提示音
        audioManager.stopListening()

        // 语音提示
        audioManager.startSpeaking("好的$userName，请看我这边，我来记住你~")
        // 切换到识别中表情（在TTS提示音播放时就显示识别中）
        expressionManager.requestExpression(ExpressionType.RECOGNIZING)

        registerManager.startRegistration(userName, object : com.roboparty.rpoexpression.recognition.FaceRegisterManager.RegisterCallback {
            override fun onRegisterStart(name: String) {
                Log.d(TAG, "Registration started for: $name")
                DebugVisionState.updateRegister(
                    isRegistering = true,
                    name = name,
                    collected = 0,
                    total = com.roboparty.rpoexpression.recognition.FaceProfile.REQUIRED_SAMPLE_COUNT
                )
            }

            override fun onSampleCollected(collected: Int, total: Int) {
                // 采集进度反馈（可选：可以做进度条动画）
                Log.d(TAG, "Registration sample: $collected/$total")
                DebugVisionState.updateRegister(true, userName, collected, total)
                runOnUiThread {
                    // 采集过程中保持识别中表情，瞳孔微微放大表示正在"看"
                    expressionManager.requestExpression(ExpressionType.RECOGNIZING, force = true)
                    expressionManager.setDistancePupilScale(1.05f + collected * 0.02f)
                }
            }

            override fun onRegisterSuccess(profile: com.roboparty.rpoexpression.recognition.FaceProfile) {
                runOnUiThread {
                    isFaceRegistering = false
                    // 注册成功：开心表情
                    expressionManager.setDistancePupilScale(1f)
                    expressionManager.requestExpression(ExpressionType.HAPPY, 1500)
                    audioManager.startSpeaking("太好了，我记住你啦，$userName！以后我就能认出你了~")
                    Log.i(TAG, "Face registration success: ${profile.name}")
                    DebugVisionState.updateRegister(false)
                    resetContinuousConversationTimeout()
                }
            }

            override fun onRegisterFailed(reason: String) {
                runOnUiThread {
                    isFaceRegistering = false
                    DebugVisionState.updateRegister(false)
                    expressionManager.setDistancePupilScale(1f)
                    expressionManager.requestExpression(ExpressionType.CONFUSED, 1500)
                    audioManager.startSpeaking("不好意思，我没看清你的脸，请正对镜头再说一次名字吧~")
                    Log.w(TAG, "Face registration failed: $reason")
                    resetContinuousConversationTimeout()
                }
            }

            override fun onRegisterTimeout() {
                runOnUiThread {
                    isFaceRegistering = false
                    DebugVisionState.updateRegister(false)
                    expressionManager.setDistancePupilScale(1f)
                    expressionManager.requestExpression(ExpressionType.CONFUSED, 1500)
                    audioManager.startSpeaking("注册超时啦，请正对镜头再试一次吧~")
                    resetContinuousConversationTimeout()
                }
            }
        })
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION", "TYPE_MISMATCH")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startAsrRecognition(pendingAsrOnlyTest)
                } else {
                    audioManager.startSpeaking("请授予录音权限，否则无法进行语音识别")
                }
            }
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startFaceDetect()
                } else {
                    audioManager.startSpeaking("请授予相机权限，否则无法进行人脸识别")
                }
            }
            REQUEST_ALL_PERMISSIONS -> {
                // 检查所有权限结果
                val audioGranted = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
                val cameraGranted = grantResults.getOrNull(1) == PackageManager.PERMISSION_GRANTED
                // 相机权限通过就启动人脸识别
                if (cameraGranted) {
                    startFaceDetect()
                }
                // 录音权限通过就启动唤醒监听
                if (audioGranted) {
                    startWakeUpListening()
                }
            }
        }
    }

    /**
     * 检查相机权限
     */
    internal fun checkCameraPermissionAndStartFaceDetect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            startFaceDetect()
        }
    }

    /**
     * 开始人脸检测
     * 注意：TCP连接由 HeadControlManager 在 RpoExpressionApp 中统一管理，无需在此处连接
     */
    private fun startFaceDetect() {
        visionManager.startFaceDetect(this, object : VisionManager.VisionStateCallback {
            override fun onFaceDetected(faceOffsetX: Float, faceOffsetY: Float, faceSize: Float) {
                super.onFaceDetected(faceOffsetX, faceOffsetY, faceSize)
                // 实时更新瞳孔偏移，实现视线跟踪
                expressionManager.setPupilOffset(faceOffsetX, faceOffsetY)
            }

            override fun onUserSmile(smileProb: Float) {
                super.onUserSmile(smileProb)
            }

            override fun onUserBlink() {
                super.onUserBlink()
            }
            override fun onUserSurprised() {
                super.onUserSurprised()
            }

            override fun onFaceLost() {
                super.onFaceLost()
                // 人脸离开，重置瞳孔到中心
                expressionManager.setPupilOffset(0f, 0f)
                // 清除识别冷却，使此人重新出现时能被立即识别
                app.faceIdentifier.onFaceLost()
            }

            override fun onSpeakRequest(text: String) {
                // 多人场景进入时的语音播报（如"哇，好多人呀！"）
                // 不打断正在进行的对话，只有空闲时才说
                if (!audioManager.isSpeaking() && !audioManager.isListening()) {
                    audioManager.startSpeaking(text)
                }
            }
        })
    }

    /**
     * 执行软重置：重置所有模块，不重启APP，无黑屏
     */
    private fun performSoftReset() {
        Log.i(TAG, "Performing full soft reset...")
        lifecycleScope.launch {
            // 停止Demo
            demoSequenceManager.stopDemo()
            // 停止音频和LLM
            audioManager.stopListening()
            audioManager.stopKwsListening()
            audioManager.stopSpeaking()
            llmManager.cancelRequest()
            llmManager.exitConversation() // 软重置时清空对话记忆
            // 停止视觉
            visionManager.stopFaceDetect()
            // 重置表情状态
            expressionManager.softReset()
            // 退出连续对话
            isInContinuousConversation = false
            currentVoiceState = VoiceState.COOLDOWN
            continuousConversationTimeoutJob?.cancel()
            delay(200)
            // 重启视觉和唤醒
            try { startFaceDetect() } catch (e: Exception) { Log.e(TAG, "Restart face detect failed", e) }
            try { startWakeUpListening() } catch (e: Exception) { Log.e(TAG, "Restart wake up failed", e) }
            Log.i(TAG, "Soft reset complete")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放所有资源
        demoSequenceManager.stopDemo()
        audioManager.stopSpeaking()
        audioManager.stopListening()
        audioManager.stopKwsListening()
        llmManager.cancelRequest()
        visionManager.stopFaceDetect()
        expressionManager.stop()
        communicationManager.stop()
        brightnessManager.detachActivity()
        brightnessManager.stop()
    }

    /**
     * 调试面板 - 开发测试用
     * 包含完整的表情、风格、强度测试功能
     */
    @Composable
    private fun DebugPanel(
        onStartConversation: () -> Unit = {},
        onStartAsrOnlyTest: () -> Unit = {},
        onStartFaceDetect: () -> Unit = {},
        onSendText: (String) -> Unit = {}
    ) {
        // 测试时设置为true显示调试面板，发布时设置为false
        val showDebug = false

        if (showDebug) {
            var isAsrListening by remember { mutableStateOf(audioManager.isListening()) }
            var isFaceDetecting by remember { mutableStateOf(visionManager.isDetecting()) }
            var inputText by remember { mutableStateOf("") }
            var currentIntensity by remember { mutableStateOf(100) }
            val expressionState by expressionManager.expressionState.collectAsState()
            val scrollState = rememberScrollState()

            LaunchedEffect(Unit) {
                while (true) {
                    isAsrListening = audioManager.isListening()
                    isFaceDetecting = visionManager.isDetecting()
                    currentIntensity = expressionState.intensity
                    delay(200)
                }
            }

            // 半透明背景面板，不遮挡眼睛显示
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.Bottom
            ) {
                // ========== 状态显示栏 ==========
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = ComposeColor(0x80000000),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Column {
                        Text(
                            text = "🤖 RPO纯眼表情系统 v1.0",
                            color = ComposeColor.White,
                            fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            text = "当前表情: ${expressionState.type.name} | 风格: ${expressionState.style.name} | 强度: ${expressionState.intensity}%",
                            color = ComposeColor(0xFFE0E0E0),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.padding(4.dp))

                // ========== 风格切换 ==========
                Text("🎨 渲染风格", color = ComposeColor.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val styles = listOf(
                        ExpressionStyle.MALE to "男款丹凤",
                        ExpressionStyle.FEMALE to "女款杏眼"
                    )
                    styles.forEach { (style, name) ->
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (expressionState.style == style)
                                    ComposeColor(0xFF2196F3) else ComposeColor(0x60606060),
                                contentColor = ComposeColor.White
                            ),
                            onClick = { expressionManager.setStyle(style) }
                        ) {
                            Text(name, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(4.dp))

                // ========== 强度调节 ==========
                Text("📊 表情强度: $currentIntensity%", color = ComposeColor.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Slider(
                    value = currentIntensity.toFloat(),
                    onValueChange = { value ->
                        currentIntensity = value.toInt()
                        expressionManager.requestExpression(
                            type = expressionState.type,
                            intensity = currentIntensity,
                            force = true
                        )
                    },
                    valueRange = 0f..100f,
                    steps = 100,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.padding(4.dp))

                // ========== 功能类表情 ==========
                Text("⚙️ 功能类表情", color = ComposeColor.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { expressionManager.requestExpression(ExpressionType.IDLE, currentIntensity, force = true) }
                    ) { Text("待机", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { expressionManager.requestExpression(ExpressionType.SPEAKING, currentIntensity) }
                    ) { Text("说话", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { expressionManager.requestExpression(ExpressionType.THINKING, currentIntensity) }
                    ) { Text("思考", fontSize = 10.sp) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { expressionManager.requestExpression(ExpressionType.RECOGNIZING, currentIntensity) }
                    ) { Text("识别中", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { expressionManager.requestExpression(ExpressionType.RESPONSE, currentIntensity) }
                    ) { Text("应答", fontSize = 10.sp) }
                }

                Spacer(modifier = Modifier.padding(4.dp))

                // ========== 情绪类表情 ==========
                Text("😊 情绪类表情", color = ComposeColor.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFFFC107)),
                        onClick = { expressionManager.requestExpression(ExpressionType.HAPPY, currentIntensity) }
                    ) { Text("开心", fontSize = 10.sp, color = ComposeColor.Black) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFFF9800)),
                        onClick = { expressionManager.requestExpression(ExpressionType.CONFUSED, currentIntensity) }
                    ) { Text("疑惑", fontSize = 10.sp, color = ComposeColor.Black) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF9C27B0)),
                        onClick = { expressionManager.requestExpression(ExpressionType.SURPRISED, currentIntensity) }
                    ) { Text("惊讶", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF2196F3)),
                        onClick = { expressionManager.requestExpression(ExpressionType.SAD, currentIntensity) }
                    ) { Text("难过", fontSize = 10.sp) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFF44336)),
                        onClick = { expressionManager.requestExpression(ExpressionType.ANGRY, currentIntensity) }
                    ) { Text("生气", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF9E9E9E)),
                        onClick = { expressionManager.requestExpression(ExpressionType.SLEEPY, currentIntensity) }
                    ) { Text("困倦", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFE91E63)),
                        onClick = { expressionManager.requestExpression(ExpressionType.SHY, currentIntensity) }
                    ) { Text("害羞", fontSize = 10.sp) }
                }

                Spacer(modifier = Modifier.padding(4.dp))

                // ========== 系统状态表情 ==========
                Text("🔔 系统状态", color = ComposeColor.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFFFEB3B)),
                        onClick = { expressionManager.requestExpression(ExpressionType.LOW_BATTERY, currentIntensity) }
                    ) { Text("低电量", fontSize = 10.sp, color = ComposeColor.Black) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF4CAF50)),
                        onClick = { expressionManager.requestExpression(ExpressionType.CHARGING, currentIntensity) }
                    ) { Text("充电中", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFD32F2F)),
                        onClick = { expressionManager.requestExpression(ExpressionType.ALERT, currentIntensity) }
                    ) { Text("告警", fontSize = 10.sp) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF424242)),
                        onClick = { expressionManager.requestExpression(ExpressionType.SLEEPING, currentIntensity) }
                    ) { Text("休眠", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF03A9F4)),
                        onClick = { expressionManager.requestExpression(ExpressionType.UPDATING, currentIntensity) }
                    ) { Text("升级中", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            demoSequenceManager.stopDemo()
                            audioManager.stopSpeaking()
                            audioManager.stopListening()
                            expressionManager.clearExpression()
                        }
                    ) { Text("清空", fontSize = 10.sp) }
                }

                Spacer(modifier = Modifier.padding(4.dp))

                // ========== 功能测试 ==========
                Text("🧪 功能测试", color = ComposeColor.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { demoSequenceManager.triggerStandardDemo() }
                    ) { Text("播放Demo", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            audioManager.startSpeaking("你好，我是RPO智能对话机器人，现在正在测试纯眼部表情系统。")
                        }
                    ) { Text("TTS测试", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { onStartConversation() }
                    ) { Text("语音对话", fontSize = 10.sp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFaceDetecting) ComposeColor(0xFFF44336) else ComposeColor(0xFF4CAF50),
                            contentColor = ComposeColor.White
                        ),
                        onClick = {
                            if (isFaceDetecting) {
                                visionManager.stopFaceDetect()
                            } else {
                                onStartFaceDetect()
                            }
                        }
                    ) { Text(if (isFaceDetecting) "停止人脸" else "人脸识别", fontSize = 10.sp) }
                }

                Spacer(modifier = Modifier.padding(8.dp))
            }
        }
    }
}











