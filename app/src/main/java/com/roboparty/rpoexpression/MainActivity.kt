/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.roboparty.rpoexpression.animation.ExpressionCanvas
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.ui.theme.RpoRobotExpressionTheme
import kotlinx.coroutines.launch

/**
 * 主Activity
 * 实现全屏沉浸式表情显示
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: RpoExpressionApp
    private lateinit var expressionManager: ExpressionManager
    private lateinit var communicationManager: CommunicationManager
    private lateinit var brightnessManager: BrightnessManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化
        app = application as RpoExpressionApp
        expressionManager = app.expressionManager
        communicationManager = app.communicationManager
        brightnessManager = app.brightnessManager

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

    override fun onDestroy() {
        super.onDestroy()
        expressionManager.stop()
        communicationManager.stop()
        brightnessManager.stop()
    }
}

/**
 * 调试面板 - 开发测试用
 * 提供按钮手动切换表情，验证功能
 */
@Composable
private fun DebugPanel() {
    // 发布时设置为false隐藏调试面板
    val showDebug = false

    if (showDebug) {
        val expressionManager = remember { ExpressionManager.getInstance() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Button(
                onClick = { expressionManager.requestExpression(ExpressionType.IDLE, force = true) }
            ) {
                Text("待机")
            }
            Button(
                onClick = { expressionManager.requestExpression(ExpressionType.SPEAKING, force = true) }
            ) {
                Text("说话")
            }
            Button(
                onClick = { expressionManager.requestExpression(ExpressionType.THINKING, force = true) }
            ) {
                Text("思考")
            }
            Button(
                onClick = { expressionManager.requestExpression(ExpressionType.RECOGNIZING, force = true) }
            ) {
                Text("识别中")
            }
            Button(
                onClick = { expressionManager.requestExpression(ExpressionType.RESPONSE, force = true) }
            ) {
                Text("应答")
            }
        }
    }
}
