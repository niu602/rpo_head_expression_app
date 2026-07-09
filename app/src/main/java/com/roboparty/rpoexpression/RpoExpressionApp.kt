/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.roboparty.rpoexpression.animation.ExpressionAssetLoader
import com.roboparty.rpoexpression.audio.AudioManager
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.expression.DemoSequenceManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.llm.LlmManager
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.sensor.ImuManager
import com.roboparty.rpoexpression.util.Constants
import com.roboparty.rpoexpression.vision.VisionManager

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
    lateinit var demoSequenceManager: DemoSequenceManager
        private set
    lateinit var assetLoader: ExpressionAssetLoader
        private set
    lateinit var imuManager: ImuManager
        private set
    lateinit var audioManager: AudioManager
        private set
    lateinit var visionManager: VisionManager
        private set
    lateinit var llmManager: LlmManager
        private set

    override fun onCreate() {
        super.onCreate()

        // 初始化核心管理器
        expressionManager = ExpressionManager.getInstance()
        communicationManager = CommunicationManager.getInstance()
        brightnessManager = BrightnessManager.getInstance(this)
        demoSequenceManager = DemoSequenceManager(expressionManager)
        assetLoader = ExpressionAssetLoader.getInstance(this)
        assetLoader.init() // 初始化素材检查
        imuManager = ImuManager.getInstance(this)

        // 初始化第二阶段新增模块
        audioManager = AudioManager.getInstance(this)
        audioManager.init()
        visionManager = VisionManager.getInstance(this)
        visionManager.init()
        llmManager = LlmManager.getInstance(this)
        llmManager.init()

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
        brightnessManager.stop()
        imuManager.stop()
        communicationManager.stop()
    }

    companion object {
        const val CHANNEL_ID = "rpo_expression_service"
    }
}
