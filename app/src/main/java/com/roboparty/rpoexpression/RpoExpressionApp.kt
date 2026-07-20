/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.roboparty.rpoexpression.audio.AudioManager
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.expression.DemoSequenceManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.integration.ExpressionControlReceiver
import com.roboparty.rpoexpression.llm.LlmManager
import com.roboparty.rpoexpression.sensor.AmbientLightManager
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.sensor.ImuManager
import com.roboparty.rpoexpression.system.SystemStatusMonitor
import com.roboparty.rpoexpression.util.Constants
import com.roboparty.rpoexpression.vision.VisionManager
import android.content.Context
import android.util.Log
/**
 * Application入口
 * 初始化各个管理器单例
 */
class RpoExpressionApp : Application() {

    lateinit var expressionManager: ExpressionManager
        private set
    lateinit var communicationManager: CommunicationManager
        private set
    lateinit var brightnessManager: BrightnessManager
        private set
    lateinit var ambientLightManager: AmbientLightManager
        private set
    lateinit var demoSequenceManager: DemoSequenceManager
        private set
    lateinit var imuManager: ImuManager
        private set
    lateinit var audioManager: AudioManager
        private set
    lateinit var visionManager: VisionManager
        private set
    lateinit var llmManager: LlmManager
        private set
    lateinit var systemStatusMonitor: SystemStatusMonitor
        private set
    private lateinit var expressionControlReceiver: ExpressionControlReceiver
    private val appScope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()

        // 初始化配置管理器（最先初始化，其他模块读取配置）
        com.roboparty.rpoexpression.config.ConfigManager.getInstance().init(this)

        // 初始化核心管理器
        expressionManager = ExpressionManager.getInstance()
        communicationManager = CommunicationManager.getInstance()
        try {
            brightnessManager = BrightnessManager.getInstance(this)
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "BrightnessManager init failed", e)
        }
        try {
            ambientLightManager = AmbientLightManager.getInstance(this)
            ambientLightManager.start()
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "AmbientLightManager init failed", e)
        }
        demoSequenceManager = DemoSequenceManager(expressionManager)
        try {
            imuManager = ImuManager.getInstance(this)
            imuManager.start()
            // 注册IMU头部动作监听
            imuManager.setPoseListener { pose ->
                when (pose.currentAction) {
                    ImuManager.HeadAction.NOD -> {
                        // 点头：触发眨眼
                        if (!expressionManager.isBlinking()) {
                            expressionManager.performBlink()
                        }
                    }
                    ImuManager.HeadAction.BOW_DOWN -> {
                        // 低头：半闭眼1.5秒
                        expressionManager.setTempEyelidOffset(-0.4f)
                        expressionManager.scope.launch {
                            kotlinx.coroutines.delay(1500)
                            expressionManager.setTempEyelidOffset(0f)
                        }
                    }
                    ImuManager.HeadAction.LOOK_UP -> {
                        // 抬头：睁大眼1.5秒
                        expressionManager.setTempEyelidOffset(0.3f)
                        expressionManager.scope.launch {
                            kotlinx.coroutines.delay(1500)
                            expressionManager.setTempEyelidOffset(0f)
                        }
                    }
                    ImuManager.HeadAction.SHAKE -> {
                        // 摇头：瞳孔左右晃动3次
                        expressionManager.scope.launch {
                            repeat(3) {
                                expressionManager.setTempPupilShakeX(0.3f)
                                kotlinx.coroutines.delay(100)
                                expressionManager.setTempPupilShakeX(-0.3f)
                                kotlinx.coroutines.delay(100)
                            }
                            expressionManager.setTempPupilShakeX(0f)
                        }
                    }
                    ImuManager.HeadAction.SHAKE_HARD -> {
                        // 剧烈晃动：触发惊讶表情
                        expressionManager.requestExpression(com.roboparty.rpoexpression.expression.ExpressionType.SURPRISED, 1000)
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "ImuManager init failed, skip IMU perception", e)
        }

        // 初始化第二阶段新增模块
        try {
            audioManager = AudioManager.getInstance(this)
            audioManager.init()
            // 监听音频状态，实现情绪眼型和声源跟踪
            appScope.launch {
                try {
                    audioManager.audioVolume.collect { db ->
                        val eyeShape = when {
                            db > 60f -> 1f // 大音量：锐利眼型
                            db < 40f -> -1f // 小音量：柔和弯月
                            else -> 0f // 正常
                        }
                        expressionManager.setAudioEyeShape(eyeShape)
                    }
                } catch (e: Exception) {
                    Log.e("RpoExpressionApp", "Audio volume collect failed", e)
                }
            }
            appScope.launch {
                try {
                    audioManager.soundDirection.collect { dir ->
                        expressionManager.setSoundPupilOffset(dir)
                    }
                } catch (e: Exception) {
                    Log.e("RpoExpressionApp", "Sound direction collect failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "AudioManager init failed", e)
        }

        // 风格-音色联动：切换渲染风格时同步切换 TTS 音色，保持视觉听觉统一
        try {
            expressionManager.addStyleChangeCallback { style ->
                audioManager.applyVoiceStyle(style)
            }
            // 初始化时应用一次默认风格（女款）对应的音色
            audioManager.applyVoiceStyle(expressionManager.getCurrentStyle())
            Log.d("RpoExpressionApp", "Style-voice sync registered")
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "Style-voice sync init failed", e)
        }
        try {
            visionManager = VisionManager.getInstance(this)
            visionManager.init()
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "VisionManager init failed", e)
        }
        try {
            llmManager = LlmManager.getInstance(this)
            llmManager.init()
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "LlmManager init failed", e)
        }

        // 初始化系统状态监控器（电量、充电状态自动同步表情）
        try {
            systemStatusMonitor = SystemStatusMonitor.getInstance()
            systemStatusMonitor.init(this)
            systemStatusMonitor.start()
            Log.d("RpoExpressionApp", "SystemStatusMonitor started")
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "SystemStatusMonitor init failed", e)
        }

        // 动态注册表情控制广播接收器（解决高版本Android静态广播限制）
        expressionControlReceiver = ExpressionControlReceiver()
        val controlFilter = IntentFilter().apply {
            addAction(ExpressionControlReceiver.ACTION_SET_EXPRESSION)
            addAction(ExpressionControlReceiver.ACTION_CLEAR_EXPRESSION)
            addAction(ExpressionControlReceiver.ACTION_SET_STYLE)
            addAction(ExpressionControlReceiver.ACTION_SET_INTENSITY)
            // 系统状态测试 Action
            addAction(ExpressionControlReceiver.ACTION_TEST_LOW_BATTERY)
            addAction(ExpressionControlReceiver.ACTION_TEST_CHARGING)
            addAction(ExpressionControlReceiver.ACTION_TEST_ALERT)
            addAction(ExpressionControlReceiver.ACTION_TEST_SLEEPING)
            addAction(ExpressionControlReceiver.ACTION_REFRESH_SYSTEM_STATUS)
        }
        try {
            registerReceiver(expressionControlReceiver, controlFilter, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "Register receiver failed, skip adb control", e)
        }

        // 配置通信地址
        communicationManager.setMasterAddress(
            Constants.DEFAULT_MASTER_IP,
            Constants.DEFAULT_MASTER_PORT
        )

        // 创建通知渠道（前台服务保活用）
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RPO表情显示服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持表情显示后台运行"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // 释放所有模块资源
        audioManager.release()
        visionManager.release()
        llmManager.release()
        unregisterReceiver(expressionControlReceiver)
        systemStatusMonitor.stop()
        brightnessManager.stop()
        ambientLightManager.stop()
        imuManager.stop()
        communicationManager.stop()
    }

    companion object {
        const val CHANNEL_ID = "rpo_expression_service"
    }
}
