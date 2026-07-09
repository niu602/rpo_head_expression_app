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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    // 权限申请后待执行的操作：是否为ASR单独测试模式
    private var pendingAsrOnlyTest = false

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
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 表情画布
                        ExpressionCanvas(modifier = Modifier.fillMaxSize())

                        // 调试面板（开发用，发布时可注释）
                        DebugPanel(
                            onStartConversation = { checkRecordPermissionAndStartAsr() },
                            onStartAsrOnlyTest = { checkRecordPermissionAndStartAsrOnly() },
                            onStartFaceDetect = { checkCameraPermissionAndStartFaceDetect() },
                            onSendText = { text -> sendTextToLlm(text) }
                        )
                    }
                }
            }
        }

        // 全屏沉浸式设置（放在setContent之后，确保decorView已创建，避免空指针）
        enableFullscreenMode()

        // 启动各个管理器
        startManagers()
    }

    /**
     * 启用全屏沉浸式模式
     * 隐藏状态栏和导航栏
     */
    private fun enableFullscreenMode() {
        // 使用AndroidX兼容API设置沉浸式，避免空指针
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    override fun onResume() {
        super.onResume()
        enableFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullscreenMode()
        }
    }

    /**
     * 处理手柄按键事件
     * 手柄X键：触发Demo
     * 手柄B键：停止Demo
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_A -> {
                    // 手柄X/A键：触发一键对话
                    checkRecordPermissionAndStartAsr()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    // 手柄B键：停止所有任务回到待机，返回键不处理
                    if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                        demoSequenceManager.stopDemo() // 停止Demo
                        audioManager.stopSpeaking() // 停止TTS播放
                        audioManager.stopListening() // 停止ASR
                        llmManager.cancelRequest() // 取消LLM请求
                        expressionManager.requestExpression(ExpressionType.IDLE, force = true) // 切回待机
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        expressionManager.stop()
        communicationManager.stop()
        brightnessManager.stop()
        audioManager.release()
        llmManager.release()
        visionManager.release()
    }

    /**
     * 检查录音权限，开始完整对话流程（ASR→LLM→TTS）
     */
    internal fun checkRecordPermissionAndStartAsr() {
        pendingAsrOnlyTest = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            // 有权限，开始识别
            startAsrRecognition(pendingAsrOnlyTest)
        }
    }

    /**
     * 检查录音权限，开始ASR单独测试（识别结果直接播报，不调用LLM）
     */
    internal fun checkRecordPermissionAndStartAsrOnly() {
        pendingAsrOnlyTest = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            // 有权限，开始ASR单独测试
            startAsrRecognition(asrOnly = true)
        }
    }

    /**
     * 开始ASR识别
     * @param asrOnly 是否仅测试ASR：true则识别结果直接播报，不调用LLM；false则走完整链路ASR→LLM→TTS
     */
    private fun startAsrRecognition(asrOnly: Boolean = false) {
        if (audioManager.isListening() || audioManager.isSpeaking()) {
            return
        }
        audioManager.startListening(object : AsrManager.AsrResultCallback {
            override fun onResult(text: String) {
                // 识别完成
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        if (asrOnly) {
                            // ASR单独测试模式：直接播报识别结果
                            audioManager.startSpeaking("识别结果：$text")
                        } else {
                            // 完整链路：发送给LLM
                            sendTextToLlm(text)
                        }
                    }
                }
            }

            override fun onError(errorMsg: String) {
                runOnUiThread {
                    audioManager.startSpeaking("抱歉，我没有听清，请再说一遍")
                }
            }
        })
    }

    /**
     * 发送文本给LLM，获取回复后TTS播报
     */
    private fun sendTextToLlm(text: String) {
        if (text.isBlank()) {
            return
        }
        llmManager.sendQuery(text, object : LlmManager.LlmCallback {
            override fun onSuccess(response: String) {
                // LLM返回成功，播报回复
                audioManager.startSpeaking(response)
            }

            override fun onError(errorMsg: String) {
                audioManager.startSpeaking("抱歉，我现在无法回答你的问题，请稍后再试")
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 权限通过，根据待执行模式启动
                    startAsrRecognition(pendingAsrOnlyTest)
                } else {
                    audioManager.startSpeaking("请授予录音权限，否则无法进行语音识别")
                }
            }
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 权限通过，开始人脸检测
                    startFaceDetect()
                } else {
                    audioManager.startSpeaking("请授予相机权限，否则无法进行人脸识别")
                }
            }
        }
    }

    /**
     * 检查相机权限，没有则申请
     */
    internal fun checkCameraPermissionAndStartFaceDetect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            // 有权限，开始检测
            startFaceDetect()
        }
    }

    /**
     * 开始人脸检测
     */
    private fun startFaceDetect() {
        visionManager.startFaceDetect(this, object : VisionManager.VisionStateCallback {
            override fun onTimeout() {
                super.onTimeout()
                // 7秒未检测到人脸：先停止检测切IDLE，再TTS语音提醒，播报完自动回IDLE
                visionManager.stopFaceDetect()
                audioManager.startSpeaking("我看不到你哦，请面向镜头")
            }
        })
    }
}

/**
 * 调试面板 - 开发测试用
 * 提供按钮手动测试所有功能，无需写代码
 * @param onStartConversation 一键开始语音对话的回调
 * @param onStartFaceDetect 开始人脸检测的回调
 * @param onSendText 发送文本给LLM的回调
 */
@Composable
private fun DebugPanel(
    onStartConversation: () -> Unit = {},
    onStartAsrOnlyTest: () -> Unit = {},
    onStartFaceDetect: () -> Unit = {},
    onSendText: (String) -> Unit = {}
) {
    // 测试时设置为true显示调试面板，发布时设置为false
    val showDebug = true

    if (showDebug) {
        val context = LocalContext.current
        val expressionManager = remember { ExpressionManager.getInstance() }
        val demoSequenceManager = remember { (context.applicationContext as RpoExpressionApp).demoSequenceManager }
        val audioManager = remember { AudioManager.getInstance(context) }
        val visionManager = remember { VisionManager.getInstance(context) }
        // ASR运行状态，用于更新按钮文字
        var isAsrListening by remember { mutableStateOf(audioManager.isListening()) }
        // 人脸检测运行状态
        var isFaceDetecting by remember { mutableStateOf(visionManager.isDetecting()) }
        // 对话测试输入文本
        var inputText by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()

        // 定时同步状态，保证按钮文字正确
        LaunchedEffect(Unit) {
            while (true) {
                isAsrListening = audioManager.isListening()
                isFaceDetecting = visionManager.isDetecting()
                delay(500)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Bottom
        ) {
            // ==================== 配置状态显示 ====================
            val asrKeyConfigured = Constants.QWEN_ASR_API_KEY.isNotBlank()
            val llmKeyConfigured = Constants.QWEN_LLM_API_KEY.isNotBlank()
            Text(
                text = buildString {
                    append("🔧 配置状态：")
                    append("ASR Key: ${if (asrKeyConfigured) "✅已配置" else "❌未配置"} | ")
                    append("LLM Key: ${if (llmKeyConfigured) "✅已配置" else "⚠️未配置(模拟模式)"}")
                },
                color = Color.White,
                fontSize = 11.sp
            )
            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // ==================== 对话测试区域 ====================
            Text("=== 对话测试 ===", color = Color.White)
            // 语音测试按钮：完整对话 + ASR单独测试
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (isAsrListening) {
                            audioManager.stopListening()
                        } else {
                            onStartConversation()
                        }
                    }
                ) {
                    Text(if (isAsrListening) "停止" else "🎤 完整对话", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (isAsrListening) {
                            audioManager.stopListening()
                        } else {
                            onStartAsrOnlyTest()
                        }
                    }
                ) {
                    Text(if (isAsrListening) "停止" else "🔍 单独测ASR", fontSize = 12.sp)
                }
            }
            // 文本输入测试（测试LLM）
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                label = { Text("输入文本测试LLM", color = Color.White, fontSize = 12.sp) },
                singleLine = true
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendText(inputText.trim())
                        inputText = ""
                    }
                }
            ) {
                Text("发送文本测试LLM并播报", fontSize = 12.sp)
            }
            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // Demo控制
            Text("=== Demo控制 ===", color = Color.White)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { demoSequenceManager.triggerStandardDemo() }
                ) {
                    Text("播放Demo", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { demoSequenceManager.stopDemo() }
                ) {
                    Text("停止Demo", fontSize = 12.sp)
                }
            }
            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // 基础表情
            Text("=== 基础表情 ===", color = Color.White)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 停止所有正在进行的任务，回到干净的待机状态
                        demoSequenceManager.stopDemo() // 停止演示序列
                        audioManager.stopSpeaking() // 停止TTS播放
                        audioManager.stopListening() // 停止ASR
                        expressionManager.requestExpression(ExpressionType.IDLE, force = true) // 强制切回待机表情
                    }
                ) {
                    Text("待机", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { expressionManager.requestExpression(ExpressionType.BLINK) }
                ) {
                    Text("眨眼", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { expressionManager.requestExpression(ExpressionType.THINKING) }
                ) {
                    Text("思考", fontSize = 12.sp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { expressionManager.requestExpression(ExpressionType.RECOGNIZING) }
                ) {
                    Text("识别中", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { expressionManager.requestExpression(ExpressionType.RESPONSE) }
                ) {
                    Text("应答", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { expressionManager.requestExpression(ExpressionType.SPEAKING) }
                ) {
                    Text("说话", fontSize = 12.sp)
                }
            }
            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // TTS测试
            Text("=== TTS语音播报测试 ===", color = Color.White)
            // 真实TTS测试播报按钮
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    audioManager.startSpeaking("你好，我是RPO智能对话机器人，有什么可以帮你的吗？")
                }
            ) {
                Text("测试播报（系统TTS）", fontSize = 12.sp)
            }
            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // 人脸识别测试
            Text("=== 人脸识别测试 ===", color = Color.White)
            // 真实ML Kit人脸检测按钮
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (isFaceDetecting) {
                        visionManager.stopFaceDetect()
                    } else {
                        isFaceDetecting = true
                        onStartFaceDetect()
                    }
                }
            ) {
                Text(if (isFaceDetecting) "停止检测" else "开始人脸检测（ML Kit）", fontSize = 12.sp)
            }
            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}
