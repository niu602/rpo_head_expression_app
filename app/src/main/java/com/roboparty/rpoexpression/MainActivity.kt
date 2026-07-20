/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.roboparty.rpoexpression.animation.ExpressionCanvas
import com.roboparty.rpoexpression.audio.AudioManager
import com.roboparty.rpoexpression.audio.AsrManager
import com.roboparty.rpoexpression.audio.TtsManager
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.llm.LlmManager
import com.roboparty.rpoexpression.vision.VisionManager
import com.roboparty.rpoexpression.expression.DemoSequenceManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.integration.ModuleStateManager
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.ui.theme.RpoRobotExpressionTheme
import com.roboparty.rpoexpression.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import android.annotation.SuppressLint
import androidx.compose.ui.Modifier
import com.roboparty.rpoexpression.expression.ExpressionState
import com.roboparty.rpoexpression.expression.ExpressionStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
/**
 * 主Activity
 * 实现全屏沉浸式表情显示，完成ASR→LLM→TTS全链路对话
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: RpoExpressionApp
    private lateinit var expressionManager: ExpressionManager
    private lateinit var communicationManager: CommunicationManager
    private lateinit var brightnessManager: BrightnessManager
    private lateinit var demoSequenceManager: DemoSequenceManager
    private lateinit var audioManager: AudioManager
    private lateinit var llmManager: LlmManager
    private lateinit var visionManager: VisionManager
    private val REQUEST_RECORD_AUDIO_PERMISSION = 1001
    private val REQUEST_CAMERA_PERMISSION = 1002
    private val REQUEST_ALL_PERMISSIONS = 1003

    // 权限申请后待执行的操作：是否为ASR单独测试模式
    private var pendingAsrOnlyTest = false

    // 语音唤醒相关
    private var isWakeUpListening = false // 是否处于唤醒监听状态
    private var lastWakeTime = 0L // 上次唤醒时间，冷却防重复
    private val WAKE_COOLDOWN_MS = 3000L // 唤醒冷却3秒
    private val wakeWords = listOf(
        "萝卜", "萝卜萝卜", "罗布", "罗波",   // 中文宽松
        "hiro", "hirobot", "hi bot"           // 英文宽松
    )

    // 连续对话相关
    private var isInContinuousConversation = false // 是否处于连续对话模式（唤醒后无需重复唤醒）
    private val exitWords = listOf(
        "结束对话", "结束", "拜拜", "拜", "再见", "再会", "退下"  // 结束词
    )

    // ========== [新增] 连续对话空闲超时：一段时间没说话自动退出连续对话 ==========
    private val CONTINUOUS_CONVERSATION_TIMEOUT_MS = 30000L // 30秒
    private var continuousConversationTimeoutJob: kotlinx.coroutines.Job? = null

    // ========== [新增] TTS播放结束回调监听器，解决原来只执行一次的问题 ==========
    private var ttsFinishListener: (() -> Unit)? = null

    /**
     * 宽松唤醒词匹配
     * 规则：
     * 1. 中文：匹配≥2个字；英文：匹配≥3个字母
     * 2. 支持：开头匹配 / 结尾匹配 / 中间包含
     * 3. 自动过滤太短的识别结果（<3字直接忽略）
     */
    private fun isFuzzyMatch(input: String, wakeWord: String): Boolean {
        // 太短直接不匹配，防误触
        if (input.length < 3) return false

        val minMatchLen = if (wakeWord.any { it.isChinese() }) 2 else 3

        // 情况1：输入包含唤醒词（原逻辑）
        if (input.contains(wakeWord, ignoreCase = true)) return true

        // 情况2：开头匹配（如：萝卜、萝卜萝）
        if (input.startsWith(wakeWord.take(minMatchLen), ignoreCase = true)) return true

        // 情况3：结尾匹配（如：萝卜、卜萝卜）
        if (input.endsWith(wakeWord.takeLast(minMatchLen), ignoreCase = true)) return true

        // 情况4：唤醒词包含输入片段，降低漏触发
        if (wakeWord.contains(input.take(minMatchLen), ignoreCase = true)) return true

        return false
    }

    // 辅助：判断是否汉字
    private fun Char.isChinese(): Boolean = this in '\u4e00'..'\u9fa5'

    /**
     * 结束词宽松匹配
     * 规则：
     * 1. 只判断清洗后的文本
     * 2. 输入包含结束词才命中
     * 3. 去掉原来反向包含，减少误触
     */
    private fun isExitWord(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.length < 2) return false

        // ========== [修改] 只做正向包含，避免“结束工作”“再见一面”这类句子误触发 ==========
        return exitWords.any { word ->
            trimmed.contains(word, ignoreCase = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化
        app = application as RpoExpressionApp
        expressionManager = app.expressionManager
        communicationManager = app.communicationManager
        brightnessManager = app.brightnessManager
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
                    // 全屏表情画布，无调试面板
                    ExpressionCanvas(modifier = Modifier.fillMaxSize())
                }
            }
        }

        // 全屏沉浸式设置
        enableFullscreenMode()

        // 启动各个管理器
        startManagers()

        initTtsFinishCallback()

        // 启动自动申请权限，开启摄像头和麦克风
        autoStartPerception()
    }

    /**
     * 启动语音唤醒持续监听
     * 后台常驻监听唤醒词，命中后触发对话
     */
    private fun startWakeUpListening() {
        // 严格防重复启动
        if (isWakeUpListening || audioManager.isListening() || audioManager.isSpeaking()) {
            return
        }

        // ========== [修改] 只有明确进入唤醒监听时，才退出连续对话 ==========
        if (isInContinuousConversation) {
            exitContinuousConversation()
        }

        // 先确保上一次的ASR已经停止
        try {
            audioManager.stopListening()
        } catch (e: Exception) {
            // 忽略停止异常
        }
        isWakeUpListening = true

        try {
            // 语音唤醒模式：不触发识别中表情，避免常驻显示识别中
            audioManager.startListening(
                callback = object : AsrManager.AsrResultCallback {
                    override fun onResult(text: String) {
                        runOnUiThread {
                            isWakeUpListening = false
                            try {
                                if (text.isNotEmpty()) {
                                    // 清洗：只保留汉字+英文，转小写
                                    val raw = text.lowercase()
                                        .replace(Regex("[^a-z\\u4e00-\\u9fa5]"), "")
                                    //宽松匹配：任一唤醒词满足“部分匹配+最小长度”
                                    val hitWakeWord = wakeWords.any { word ->
                                        isFuzzyMatch(raw, word)
                                    }
                                    // 冷却判断：3秒内不重复唤醒
                                    val now = System.currentTimeMillis()
                                    if (hitWakeWord && now - lastWakeTime > WAKE_COOLDOWN_MS) {
                                        lastWakeTime = now
                                        triggerWakeUp()
                                    } else {
                                        // 未命中或冷却中，继续监听
                                        continueWakeUpListen()
                                    }
                                } else {
                                    // 空结果，继续监听
                                    continueWakeUpListen()
                                }
                            } catch (e: Exception) {
                                // 异常后继续监听，避免崩溃
                                continueWakeUpListen()
                            }
                        }
                    }

                    override fun onError(errorMsg: String) {
                        runOnUiThread {
                            isWakeUpListening = false
                            // 出错后延迟再监听，避免频繁重试占资源
                            lifecycleScope.launch {
                                delay(500)
                                continueWakeUpListen()
                            }
                        }
                    }
                }, triggerExpression = false)
        } catch (e: Exception) {
            isWakeUpListening = false
            // 启动失败，延迟重试
            lifecycleScope.launch {
                delay(1000)
                startWakeUpListening()
            }
        }
    }

    /**
     * 继续唤醒监听（内部调用，避免重复判断）
     */
    private fun continueWakeUpListen() {
        // 已经在监听或正在说话就不重复启动
        if (isWakeUpListening || audioManager.isSpeaking()) {
            return
        }
        // 延迟200ms再启动，避免频繁调用占资源
        lifecycleScope.launch {
            delay(200)
            startWakeUpListening()
        }
    }

    /**
     * 触发唤醒：转向音源，应答，进入对话
     */
    private fun triggerWakeUp() {
        isWakeUpListening = false
        isInContinuousConversation = true // 进入连续对话模式，后续无需重复唤醒

        // ========== [新增] 启动连续对话空闲超时计时 ==========
        resetContinuousConversationTimeout()

        // 1. 瞳孔转向音源方向
        val soundDir = audioManager.soundDirection.value
        expressionManager.setPupilOffset(soundDir, 0f)
        // 2. 触发应答表情
        expressionManager.requestExpression(ExpressionType.RESPONSE, 800)
        // 3. 延迟后开始聆听对话
        lifecycleScope.launch {
            delay(800)
            // 重置瞳孔到中心，开始识别
            expressionManager.setPupilOffset(0f, 0f)
            checkRecordPermissionAndStartAsr()
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
            audioManager.restartVolumeDetect()
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
        brightnessManager.start()

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
    private fun initTtsFinishCallback() {
        ttsFinishListener = {
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
                    // 用户打断了当前播报
                    // 1. 取消正在进行的LLM请求
                    llmManager.cancelRequest()
                    // 2. 标记为识别中状态
                    ModuleStateManager.onAsrStartListening()
                    ModuleStateManager.onAsrFinish()
                    // 3. 进入连续对话模式（如果还没进入）
                    if (!isInContinuousConversation) {
                        isInContinuousConversation = true
                    }
                    // 4. 处理打断时的识别结果
                    processAsrResult(text, asrOnly = false)
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

        // ========== [修改] onResume不再强制打断连续对话 ==========
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (hasCamera && !visionManager.isDetecting()) {
            startFaceDetect()
        }

        if (!hasAudio || audioManager.isSpeaking() || audioManager.isListening()) {
            return
        }

        if (isInContinuousConversation) {
            // 保持连续对话，继续聆听
            startAsrRecognition()
        } else {
            // 回到唤醒监听
            startWakeUpListening()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退后台停止所有前台任务，彻底释放资源
        isWakeUpListening = false
        isInContinuousConversation = false // 退出连续对话模式
        continuousConversationTimeoutJob?.cancel()
        continuousConversationTimeoutJob = null

        try {
            audioManager.stopListening()
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
        if (audioManager.isListening() || audioManager.isSpeaking()) {
            return
        }

        // ========== [修改] 先重置连续对话空闲超时，避免每次说话后很快退出 ==========
        if (isInContinuousConversation) {
            resetContinuousConversationTimeout()
        }

        // 对话开始，暂停唤醒监听
        isWakeUpListening = false
        // 切换到识别中表情
        ModuleStateManager.onAsrStartListening()

        audioManager.startListening(object : AsrManager.AsrResultCallback {
            override fun onResult(text: String) {
                runOnUiThread {
                    ModuleStateManager.onAsrFinish()
                    processAsrResult(text, asrOnly)
                }
            }

            override fun onError(errorMsg: String) {
                runOnUiThread {
                    ModuleStateManager.onAsrFinish()

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

                expressionManager.requestExpression(ExpressionType.HAPPY, duration = 1200, force = true)
                audioManager.startSpeaking("好的，随时可以叫我，拜拜")
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
     * 发送文本给LLM
     */
    private fun sendTextToLlm(text: String) {
        if (text.isBlank()) {
            return
        }

        // ========== [修改] 开始请求LLM时也重置超时，避免思考和回复期间误退出 ==========
        if (isInContinuousConversation) {
            resetContinuousConversationTimeout()
        }

        // 切换到思考表情
        //expressionManager.requestExpression(ExpressionType.THINKING)
        ModuleStateManager.onLlmStartThinking()

        llmManager.sendQuery(text, object : LlmManager.LlmCallback {
            override fun onSuccess(response: String) {
                runOnUiThread {
                    ModuleStateManager.onLlmFinish()

                    // ========== [修改] TTS开始前再次重置超时，确保回复过程中不退出 ==========
                    if (isInContinuousConversation) {
                        resetContinuousConversationTimeout()
                    }

                    // 切换到说话表情，开始播报
                    expressionManager.requestExpression(ExpressionType.SPEAKING)
                    audioManager.startSpeaking(response)
                }
            }

            override fun onError(errorMsg: String) {
                runOnUiThread {
                    ModuleStateManager.onLlmFinish()

                    // ========== [修改] 请求失败也重置超时，避免连续对话异常退出 ==========
                    if (isInContinuousConversation) {
                        resetContinuousConversationTimeout()
                    }

                    expressionManager.requestExpression(ExpressionType.SAD)
                    audioManager.startSpeaking("抱歉，我现在无法回答你的问题，请稍后再试")
                }
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
                // 录音权限通过就重启音量检测和唤醒监听
                if (audioGranted) {
                    audioManager.restartVolumeDetect()
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
            }

            override fun onTimeout() {
                super.onTimeout()
                // 超时，重置瞳孔到中心
                expressionManager.setPupilOffset(0f, 0f)
                visionManager.stopFaceDetect()
                expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                audioManager.startSpeaking("我看不到你哦，请面向镜头")
            }

            override fun onStop() {
                super.onStop()
                // 停止检测，重置瞳孔到中心
                expressionManager.setPupilOffset(0f, 0f)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放所有资源
        demoSequenceManager.stopDemo()
        audioManager.stopSpeaking()
        audioManager.stopListening()
        llmManager.cancelRequest()
        visionManager.stopFaceDetect()
        expressionManager.stop()
        communicationManager.stop()
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