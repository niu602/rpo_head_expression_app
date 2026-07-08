/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.roboparty.rpoexpression.animation.ExpressionCanvas
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.expression.DemoSequenceManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.integration.ModuleStateManager
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.ui.theme.RpoRobotExpressionTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import android.annotation.SuppressLint
/**
 * 主Activity
 * 实现全屏沉浸式表情显示
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: RpoExpressionApp
    private lateinit var expressionManager: ExpressionManager
    private lateinit var communicationManager: CommunicationManager
    private lateinit var brightnessManager: BrightnessManager
    private lateinit var demoSequenceManager: DemoSequenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化
        app = application as RpoExpressionApp
        expressionManager = app.expressionManager
        communicationManager = app.communicationManager
        brightnessManager = app.brightnessManager
        demoSequenceManager = app.demoSequenceManager

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
                        DebugPanel()
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
                    // 手柄X/A键：触发演示Demo
                    demoSequenceManager.triggerStandardDemo()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    // 手柄B键：停止Demo，返回键不处理
                    if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                        demoSequenceManager.stopDemo()
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
    }
}

/**
 * 调试面板 - 开发测试用
 * 提供按钮手动测试所有功能，无需写代码
 */
@Composable
private fun DebugPanel() {
    // 测试时设置为true显示调试面板，发布时设置为false
    val showDebug = false

    if (showDebug) {
        val context = LocalContext.current
        val expressionManager = remember { ExpressionManager.getInstance() }
        val demoSequenceManager = remember { (context.applicationContext as RpoExpressionApp).demoSequenceManager }
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Demo控制
            Text("=== Demo控制 ===", color = androidx.compose.ui.graphics.Color.White)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { demoSequenceManager.triggerStandardDemo() }
                ) {
                    Text("播放Demo")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { demoSequenceManager.stopDemo() }
                ) {
                    Text("停止Demo")
                }
            }
            Divider(color = androidx.compose.ui.graphics.Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // 基础表情
            Text("=== 基础表情 ===", color = androidx.compose.ui.graphics.Color.White)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { expressionManager.requestExpression(ExpressionType.IDLE, force = true) }
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
            Divider(color = androidx.compose.ui.graphics.Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // ASR测试
            Text("=== ASR语音识别测试 ===", color = androidx.compose.ui.graphics.Color.White)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onAsrStartListening() }
                ) {
                    Text("开始识别", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onAsrProcessing() }
                ) {
                    Text("识别中", fontSize = 12.sp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onAsrFinish() }
                ) {
                    Text("识别完成", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onAsrError() }
                ) {
                    Text("识别错误", fontSize = 12.sp)
                }
            }
            Divider(color = androidx.compose.ui.graphics.Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // TTS测试
            Text("=== TTS语音播报测试 ===", color = androidx.compose.ui.graphics.Color.White)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onTtsStartSpeaking() }
                ) {
                    Text("开始播报", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onTtsFinishSpeaking() }
                ) {
                    Text("播报完成", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onTtsError() }
                ) {
                    Text("播报错误", fontSize = 12.sp)
                }
            }
            Divider(color = androidx.compose.ui.graphics.Color.Gray, modifier = Modifier.padding(vertical = 4.dp))

            // 人脸识别测试
            Text("=== 人脸识别测试 ===", color = androidx.compose.ui.graphics.Color.White)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onFaceDetectStart() }
                ) {
                    Text("开始识别", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onFaceDetectSuccess() }
                ) {
                    Text("识别成功", fontSize = 12.sp)
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { ModuleStateManager.onFaceDetectFailed() }
                ) {
                    Text("识别失败", fontSize = 12.sp)
                }
            }
        }
    }
}
