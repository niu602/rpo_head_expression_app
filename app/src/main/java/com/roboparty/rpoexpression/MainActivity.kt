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
import android.os.BatteryManager
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
import com.roboparty.rpoexpression.audio.ConversationCoordinator
import com.roboparty.rpoexpression.audio.InterruptReason
import com.roboparty.rpoexpression.audio.LocalIntentResolver
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
import com.roboparty.rpoexpression.expression.EmotionParser
import com.roboparty.rpoexpression.expression.ExpressionSource
import com.roboparty.rpoexpression.util.Constants
import com.roboparty.rpoexpression.util.PersonaRegistry
import com.roboparty.rpoexpression.util.PersonaSwitchManager
import com.roboparty.rpoexpression.util.PersonaSwitcher
import com.roboparty.rpoexpression.util.VoiceOption
import com.roboparty.rpoexpression.util.VoicePreferenceStore
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
    private lateinit var conversationCoordinator: ConversationCoordinator
    private val REQUEST_RECORD_AUDIO_PERMISSION = 1001
    private val REQUEST_CAMERA_PERMISSION = 1002
    private val REQUEST_ALL_PERMISSIONS = 1003

    // 权限申请后待执行的操作：是否为ASR单独测试模式
    private var pendingAsrOnlyTest = false

    // ========== 语音交互状态机 ==========
    // ========== 会话统一管理（P0：委托 AudioManager）==========
    // 会话ID生成、currentSessionId、conversationJob 统一由 AudioManager 管理
    // - 新会话：audioManager.startNewSession()
    // - 中断会话：audioManager.interruptConversation(reason)
    // - 校验回调：sessionId == audioManager.getCurrentSessionId()
    // - 独立TTS临时ID：audioManager.generateTempSessionId()

    // ========== 人脸注册状态：注册期间阻止TTS finish/barge-in干扰 ==========
    @Volatile
    private var isFaceRegistering = false

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

        // 初始化对话协调器（P2：语音对话逻辑集中管理）
        conversationCoordinator = ConversationCoordinator(
            context = this,
            audioManager = audioManager,
            llmManager = llmManager,
            expressionManager = expressionManager,
            voicePreferenceStore = app.voicePreferenceStore,
            scope = lifecycleScope
        )

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

        // 初始化对话协调器回调（替代原 initTtsFinishCallback）
        conversationCoordinator.init(object : ConversationCoordinator.Callback {
            override fun getLastRecognizedName(): String? = app.lastRecognizedName
            override fun isFaceRegistering(): Boolean = this@MainActivity.isFaceRegistering
            override fun onStartFaceRegistration(userName: String) {
                startFaceRegistration(userName)
            }
            override fun onNeedCheckPermissionAndStartAsr() {
                checkRecordPermissionAndStartAsr()
            }
            override fun onNeedStartFaceDetect() {
                startFaceDetect()
            }
        })
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
    /**
     * 启动KWS关键词唤醒监听（委托给 ConversationCoordinator）
     */
    private fun startWakeUpListening() {
        conversationCoordinator.startWakeUpListening()
    }

    /**
     * 触发唤醒（委托给 ConversationCoordinator）
     */
    private fun triggerWakeUp(persona: LlmManager.Persona = LlmManager.Persona.LUOBO) {
        conversationCoordinator.onWakeWordDetected(persona)
    }

    /**
     * 对话中切换人格（委托给 ConversationCoordinator）
     */
    private fun switchPersona(target: LlmManager.Persona) {
        conversationCoordinator.switchPersona(target)
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
                        audioManager.interruptConversation(InterruptReason.HARDWARE_COMMAND)  // 取消所有进行中的会话
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

    override fun onResume() {
        super.onResume()
        enableFullscreenMode()

        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (hasCamera && !visionManager.isDetecting()) {
            startFaceDetect()
        }

        if (hasAudio) {
            conversationCoordinator.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        // 语音部分委托给 Coordinator
        conversationCoordinator.onPause()
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
                    conversationCoordinator.interrupt(InterruptReason.HARDWARE_COMMAND)
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
            conversationCoordinator.enterContinuousConversation() // 手动触发也进入连续对话模式
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
     * 开始ASR识别（委托给 ConversationCoordinator）
     */
    private fun startAsrRecognition(asrOnly: Boolean = false) {
        conversationCoordinator.startAsrRecognition(asrOnly)
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
        val regSessionId = audioManager.generateTempSessionId()
        audioManager.startSpeaking("好的$userName，请看我这边，我来记住你~", regSessionId)
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
                    val successSessionId = audioManager.generateTempSessionId()
                    audioManager.startSpeaking("太好了，我记住你啦，$userName！以后我就能认出你了~", successSessionId)
                    Log.i(TAG, "Face registration success: ${profile.name}")
                    DebugVisionState.updateRegister(false)
                    conversationCoordinator.refreshContinuousConversationTimeout()
                }
            }

            override fun onRegisterFailed(reason: String) {
                runOnUiThread {
                    isFaceRegistering = false
                    DebugVisionState.updateRegister(false)
                    expressionManager.setDistancePupilScale(1f)
                    expressionManager.requestExpression(ExpressionType.CONFUSED, 1500)
                    val failSessionId = audioManager.generateTempSessionId()
                    audioManager.startSpeaking("不好意思，我没看清你的脸，请正对镜头再说一次名字吧~", failSessionId)
                    Log.w(TAG, "Face registration failed: $reason")
                    conversationCoordinator.refreshContinuousConversationTimeout()
                }
            }

            override fun onRegisterTimeout() {
                runOnUiThread {
                    isFaceRegistering = false
                    DebugVisionState.updateRegister(false)
                    expressionManager.setDistancePupilScale(1f)
                    expressionManager.requestExpression(ExpressionType.CONFUSED, 1500)
                    val timeoutSessionId = audioManager.generateTempSessionId()
                    audioManager.startSpeaking("注册超时啦，请正对镜头再试一次吧~", timeoutSessionId)
                    conversationCoordinator.refreshContinuousConversationTimeout()
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
                    val permSessionId = audioManager.generateTempSessionId()
                    audioManager.startSpeaking("请授予录音权限，否则无法进行语音识别", permSessionId)
                }
            }
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startFaceDetect()
                } else {
                    val permSessionId = audioManager.generateTempSessionId()
                    audioManager.startSpeaking("请授予相机权限，否则无法进行人脸识别", permSessionId)
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
                    val faceSessionId = audioManager.generateTempSessionId()
                    audioManager.startSpeaking(text, faceSessionId)
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
            // 停止视觉
            visionManager.stopFaceDetect()
            // 重置表情状态
            expressionManager.softReset()
            // 语音部分委托给 Coordinator（内部会停止音频/LLM，延迟后重启唤醒）
            conversationCoordinator.performSoftReset()
            delay(300) // 等待 Coordinator 完成语音重置
            // 重启视觉
            try { startFaceDetect() } catch (e: Exception) { Log.e(TAG, "Restart face detect failed", e) }
            Log.i(TAG, "Soft reset complete")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放所有资源
        demoSequenceManager.stopDemo()
        conversationCoordinator.onDestroy()
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
                            val debugSessionId = audioManager.generateTempSessionId()
                            audioManager.startSpeaking("你好，我是RPO智能对话机器人，现在正在测试纯眼部表情系统。", debugSessionId)
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











