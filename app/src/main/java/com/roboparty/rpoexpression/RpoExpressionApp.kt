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
import kotlinx.coroutines.launch
import com.roboparty.rpoexpression.audio.AudioManager
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.communication.HeadControlManager
import com.roboparty.rpoexpression.expression.DemoSequenceManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.integration.ExpressionControlReceiver
import com.roboparty.rpoexpression.llm.LlmManager
import com.roboparty.rpoexpression.recognition.FaceIdentifier
import com.roboparty.rpoexpression.recognition.FaceProfile
import com.roboparty.rpoexpression.recognition.FaceRegisterManager
import com.roboparty.rpoexpression.recognition.FaceRecognitionResult
import com.roboparty.rpoexpression.sensor.AmbientLightManager
import com.roboparty.rpoexpression.sensor.BrightnessManager
import com.roboparty.rpoexpression.sensor.ImuManager
import com.roboparty.rpoexpression.sensor.VolumeManager
import com.roboparty.rpoexpression.system.SystemStatusMonitor
import com.roboparty.rpoexpression.util.Constants
import android.content.Context
import android.util.Log
import com.roboparty.rpoexpression.vision.DebugVisionState
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
    lateinit var volumeManager: VolumeManager
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
    lateinit var headControlManager: HeadControlManager
        private set
    lateinit var faceRegisterManager: FaceRegisterManager
        private set
    lateinit var faceIdentifier: FaceIdentifier
        private set
    /** 最近一次识别到的人脸名字（null表示陌生人/未识别），供唤醒时选择欢迎语 */
    @Volatile
    var lastRecognizedName: String? = null
        private set
    private lateinit var expressionControlReceiver: ExpressionControlReceiver

    override fun onCreate() {
        instance = this
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
            volumeManager = VolumeManager.getInstance(this)
            volumeManager.start()
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "VolumeManager init failed", e)
        }
        try {
            ambientLightManager = AmbientLightManager.getInstance(this)
            // 禁用自动光感调节，改为手势滑动调节亮度/音量
            // ambientLightManager.start()
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

        // 初始化人脸识别模块（TFLite MobileFaceNet 192维）
        try {
            Log.i("RpoExpressionApp", "========== Initializing face recognition module ==========")
            faceRegisterManager = FaceRegisterManager.getInstance(this)
            faceIdentifier = FaceIdentifier(this, faceRegisterManager)

            // 初始化FaceIdentifier（同步加载TFLite模型，确保模型就绪后再共享）
            Log.i("RpoExpressionApp", "Initializing FaceIdentifier (loads TFLite model)...")
            faceIdentifier.initialize()
            // FaceRegisterManager共享同一个特征提取器，避免重复加载模型
            // 此时模型已同步加载完成，extractor处于可用状态
            faceRegisterManager.setFeatureExtractor(faceIdentifier.getFeatureExtractor())
            faceRegisterManager.initialize()
            Log.i("RpoExpressionApp", "FaceRegisterManager initialized")

            // 连接VisionManager回调：人脸帧 → 识别/注册
            visionManager.setRecognitionFrameCallback { faceBitmap, face, fullBitmap, srcWidth, srcHeight, rotation ->
                // 注册中：收集样本
                if (faceRegisterManager.isRegistering()) {
                    faceRegisterManager.addSample(fullBitmap, face, srcWidth, srcHeight, rotation)
                } else {
                    // 空闲：尝试识别
                    faceIdentifier.identifyAsync(fullBitmap, face, srcWidth, srcHeight, rotation)
                }
            }

            // 识别结果回调
            faceIdentifier.setRecognitionListener(object : FaceIdentifier.RecognitionListener {
                override fun onRecognized(profile: FaceProfile, similarity: Float) {
                    Log.i("RpoExpressionApp", "✅ Recognized: ${profile.name}, sim=%.3f".format(similarity))
                    // 记住最近识别到的熟人名字，唤醒后用于欢迎语
                    lastRecognizedName = profile.name
//                    // 认出熟人：开心表情
//                    expressionManager.requestExpression(ExpressionType.HAPPY, 1500)
                    // 注入用户名到LLM长期记忆（去重）
                    val memoryText = "用户的名字是${profile.name}，请自然地用名字称呼用户"
                    if (!llmManager.getLongTermMemories().contains(memoryText)) {
                        llmManager.addLongTermMemory(memoryText)
                    }
                    // 不在此处TTS播报，等待唤醒词后再说欢迎语
                }

                override fun onTentativeRecognition(profile: FaceProfile, similarity: Float) {
                    Log.d("RpoExpressionApp", "🤔 Tentative: ${profile.name}, sim=%.3f".format(similarity))
                    // 低置信度：疑惑表情，不叫名字
                    expressionManager.requestExpression(ExpressionType.CONFUSED, 800)
                }

                override fun onStrangerDetected(maxSimilarity: Float) {
                    Log.d("RpoExpressionApp", "❓ Stranger, maxSim=%.3f".format(maxSimilarity))
                    // 陌生人：清除已识别名字，唤醒后用通用欢迎语
                    lastRecognizedName = null
                    // 陌生人：不做特殊表情，保持IDLE
                }

                override fun onFrameResult(result: FaceRecognitionResult) {
                    // 调试可视化：转发每帧识别结果到DebugVisionState
                    if (DebugVisionState.visible.value) {
                        val (text, sim) = when (result) {
                            is FaceRecognitionResult.Recognized ->
                                "✅ ${result.profile.name}" to result.similarity
                            is FaceRecognitionResult.Uncertain ->
                                "🤔 ${result.bestMatch?.name ?: "?"}" to result.similarity
                            is FaceRecognitionResult.Stranger ->
                                "❓ 陌生人" to result.maxSimilarity
                            is FaceRecognitionResult.Failed ->
                                "⚠ ${result.reason}" to 0f
                        }
                        val quality = when (result) {
                            is FaceRecognitionResult.Recognized -> "sim=%.3f".format(result.similarity)
                            is FaceRecognitionResult.Uncertain -> "sim=%.3f".format(result.similarity)
                            is FaceRecognitionResult.Stranger -> "maxSim=%.3f".format(result.maxSimilarity)
                            else -> ""
                        }
                        DebugVisionState.updateRecognition(text, sim, quality)
                    }
                }
            })

            Log.d("RpoExpressionApp", "Face recognition initialized, ${faceRegisterManager.getRegisteredCount()} users registered")
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "Face recognition init failed", e)
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

        // 初始化机器人头部控制管理器（TCP + Protobuf）
        try {
            headControlManager = HeadControlManager.getInstance()
            // 设置目标设备地址（RDK板子）
            headControlManager.setTarget(
                Constants.DEFAULT_MASTER_IP,
                Constants.HEAD_CONTROL_PORT
            )
            // 注册表情变化回调：表情切换时自动发送对应情绪指令给板端
            // 板端根据 emotion 自动查动作库执行对应动作（点头/摇头/低头等）
            expressionManager.addExpressionChangeCallback { type ->
                when (type) {
                    ExpressionType.HAPPY -> {
                        // 开心 → 点头
                        headControlManager.sendHappy()
                    }
                    ExpressionType.CONFUSED -> {
                        // 疑惑 → 摇头
                        headControlManager.sendConfusion()
                    }
                    ExpressionType.SAD -> {
                        // 难过 → 低头
                        headControlManager.sendSad()
                    }
                    ExpressionType.ANGRY -> {
                        // 生气 → 快摇
                        headControlManager.sendAngry()
                    }
                    ExpressionType.SURPRISED -> {
                        // 惊讶 → 抬头
                        headControlManager.sendSurprised()
                    }
                    ExpressionType.SHY -> {
                        // 害羞 → 低头偏头
                        headControlManager.sendShy()
                    }
                    ExpressionType.IDLE, ExpressionType.BLINK -> {
                        // 待机/眨眼 → 停止动作，电机回中
                        headControlManager.sendStop()
                    }
                    else -> {
                        // 其他表情（思考/识别/说话/系统状态等）→ 默认停止摆头
                        headControlManager.sendStop()
                    }
                }
            }
            // 建立TCP长连接（异步，自动重连）
            headControlManager.connect()
            Log.d("RpoExpressionApp", "HeadControlManager initialized")
        } catch (e: Exception) {
            Log.e("RpoExpressionApp", "HeadControlManager init failed, skip head control", e)
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
        volumeManager.stop()
        ambientLightManager.stop()
        imuManager.stop()
        communicationManager.stop()
        headControlManager.release()
        faceIdentifier.release()
        faceRegisterManager.release()
    }

    companion object {
        lateinit var instance: RpoExpressionApp
            private set
        const val CHANNEL_ID = "rpo_expression_service"
    }
}


