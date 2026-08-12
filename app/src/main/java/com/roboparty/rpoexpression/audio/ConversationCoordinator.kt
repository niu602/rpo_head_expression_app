/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roboparty.rpoexpression.expression.EmotionParser
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionSource
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.integration.ModuleStateManager
import com.roboparty.rpoexpression.llm.LlmManager
import com.roboparty.rpoexpression.util.PersonaRegistry
import com.roboparty.rpoexpression.util.PersonaSwitchManager
import com.roboparty.rpoexpression.util.VoicePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 对话协调器（P2：架构重构）
 *
 * 设计目标：
 * - 状态机集中管理，MainActivity 瘦身
 * - ASR→LLM→TTS 全链路串联统一调度
 * - Barge-in 打断、连续对话超时、本地意图识别统一处理
 * - 所有状态转换可追溯、可单测
 *
 * 状态机（6状态，与重构前 VoiceState 语义一致）：
 * - DORMANT    休眠监听：KWS本地唤醒词检测（对应用户状态表的 IDLE）
 * - AWAKENING  唤醒确认：播放问候语，切换KWS→ASR（IDLE→LISTENING 的过渡态）
 * - LISTENING  对话聆听：ASR全量识别中
 * - PROCESSING LLM推理中（对应用户状态表的 THINKING）
 * - SPEAKING   TTS播报中
 * - COOLDOWN   冷却防抖：短暂过渡后回到 DORMANT（对应用户状态表的 INTERRUPTED/FAILED）
 *
 * 状态转换表（固化）：
 * ┌────────────┬──────────────┬────────────┬──────────────────────────────┐
 * │ 当前状态   │ 事件         │ 目标状态   │ 动作                         │
 * ├────────────┼──────────────┼────────────┼──────────────────────────────┤
 * │ DORMANT    │ 唤醒词       │ AWAKENING  │ 停KWS，切人格，播问候语       │
 * │ AWAKENING  │ 问候播完     │ LISTENING  │ 启ASR                        │
 * │ AWAKENING  │ 兜底超时     │ LISTENING  │ 强制启ASR                    │
 * │ LISTENING  │ ASR final    │ PROCESSING │ 停ASR，启LLM                 │
 * │ LISTENING  │ ASR error    │ DORMANT    │ 报错，连续对话则回LISTENING   │
 * │ LISTENING  │ 空闲超时     │ DORMANT    │ 回KWS                        │
 * │ PROCESSING │ LLM首句      │ SPEAKING   │ 启TTS流式                    │
 * │ PROCESSING │ LLM error    │ SPEAKING   │ 播错误提示                   │
 * │ SPEAKING   │ Barge-in     │ LISTENING  │ 全链路取消，启新ASR          │
 * │ SPEAKING   │ 播放完成     │ DORMANT    │ 回KWS（连续对话则回LISTENING）│
 * │ 任意       │ interrupt    │ COOLDOWN   │ 全链路取消                   │
 * └────────────┴──────────────┴────────────┴──────────────────────────────┘
 *
 * MainActivity 职责：
 * - UI 事件 → 调用 Coordinator 方法
 * - Coordinator 回调 → 更新 UI 状态
 * - 权限申请、人脸注册等 UI 相关逻辑保留在 MainActivity
 */
class ConversationCoordinator(
    private val context: Context,
    private val audioManager: AudioManager,
    private val llmManager: LlmManager,
    private val expressionManager: ExpressionManager,
    private val voicePreferenceStore: VoicePreferenceStore,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "ConvCoordinator"

        // ===== 唤醒兜底时间动态计算 =====
        private const val TTS_CHARS_PER_SECOND = 3.5f
        private const val TTS_SYNTHESIS_DELAY_MS = 1000L
        private const val WAKE_UP_FALLBACK_MARGIN = 1.5f
        private const val WAKE_UP_FALLBACK_MIN_MS = 2000L
        private const val WAKE_UP_FALLBACK_MAX_MS = 8000L

        // ===== 连续对话空闲超时 =====
        private const val CONTINUOUS_CONVERSATION_TIMEOUT_MS = 30000L
    }

    // ==================== 回调接口（由 MainActivity 实现） ====================

    /**
     * Coordinator 回调接口
     *
     * MainActivity 实现此接口，处理需要 UI/权限/视觉管理器的操作。
     * 所有方法均有默认空实现，MainActivity 只需实现需要的部分。
     */
    interface Callback {
        /** 获取最近识别的用户名（用于问候语个性化） */
        fun getLastRecognizedName(): String? = null

        /** 是否正在人脸注册中（注册期间抑制 TTS finish/barge-in） */
        fun isFaceRegistering(): Boolean = false

        /** 触发人脸注册流程（用户说"我叫XXX"时） */
        fun onStartFaceRegistration(userName: String) {}

        /** 需要检查录音权限并启动 ASR（唤醒兜底场景） */
        fun onNeedCheckPermissionAndStartAsr() {}

        /** 需要启动人脸识别（软重置后） */
        fun onNeedStartFaceDetect() {}

        /** 状态变化通知（用于调试或 UI 状态展示） */
        fun onStateChanged(state: ConversationState) {}
    }

    private var callback: Callback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== 状态机 ====================

    /**
     * 对话状态枚举（与原 VoiceState 语义一致）
     */
    enum class ConversationState {
        /** 休眠监听：KWS本地唤醒词检测 */
        DORMANT,
        /** 唤醒确认：播放问候语，切换KWS→ASR */
        AWAKENING,
        /** 对话聆听：ASR全量识别中 */
        LISTENING,
        /** LLM推理中 */
        PROCESSING,
        /** TTS播报中 */
        SPEAKING,
        /** 冷却防抖：短暂过渡后回到 DORMANT */
        COOLDOWN
    }

    @Volatile
    private var currentState = ConversationState.DORMANT

    // ==================== 连续对话管理 ====================

    /** 是否处于连续对话模式（唤醒后无需重复唤醒） */
    @Volatile
    private var isInContinuousConversation = false

    /** 连续对话空闲超时任务 */
    private var continuousConversationTimeoutJob: Job? = null

    /** 结束词列表 */
    private val exitWords = listOf(
        "结束对话", "结束", "拜拜", "拜", "再见", "再会", "退下"
    )

    // ==================== 初始化 ====================

    /**
     * 初始化协调器，注册 TTS 结束和 Barge-in 回调
     * 必须在 MainActivity.onCreate 中调用一次
     */
    fun init(callback: Callback) {
        this.callback = callback
        registerTtsFinishCallback()
        registerBargeInCallback()
        Log.i(TAG, "ConversationCoordinator initialized")
    }

    /**
     * 注册 TTS 播放结束回调
     */
    private fun registerTtsFinishCallback() {
        audioManager.setOnSpeakFinishListener(object : AudioManager.OnSpeakFinishListener {
            override fun onSpeakFinsh() {
                mainHandler.post {
                    onSpeakFinish()
                }
            }
        })
    }

    /**
     * 注册 Barge-in 打断回调
     */
    private fun registerBargeInCallback() {
        audioManager.setOnBargeInListener(object : AudioManager.OnBargeInListener {
            override fun onBargeIn(text: String) {
                mainHandler.post {
                    onBargeIn(text)
                }
            }
        })
    }

    // ==================== 对外 API（UI 事件入口） ====================

    /**
     * 启动 KWS 唤醒监听（进入 DORMANT 状态）
     * 对应原 MainActivity.startWakeUpListening()
     */
    fun startWakeUpListening() {
        if (currentState == ConversationState.DORMANT && audioManager.isKwsListening()) {
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

        transitionTo(ConversationState.DORMANT)
        audioManager.interruptConversation(InterruptReason.WAKE_WORD)
        Log.i(TAG, "Starting KWS wake word listening (DORMANT state)")

        try {
            audioManager.startKwsListening(object : KwsManager.KwsCallback {
                override fun onKeywordDetected(keyword: String, confidence: Float, group: KwsManager.WakeWordGroup) {
                    mainHandler.post {
                        Log.i(TAG, "Wake word detected: '$keyword', group=$group, confidence=$confidence")
                        if (PersonaSwitchManager.isSwitching) {
                            Log.i(TAG, "Wake word dropped: persona switch in progress")
                            return@post
                        }
                        val config = PersonaRegistry.getByWakeWordGroup(group)
                        val persona = config?.persona ?: LlmManager.Persona.LUOBO
                        onWakeWordDetected(persona)
                    }
                }

                override fun onError(error: String) {
                    mainHandler.post {
                        Log.e(TAG, "KWS error: $error, restarting in 1s...")
                        transitionTo(ConversationState.COOLDOWN)
                        scope.launch {
                            delay(1000)
                            startWakeUpListening()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Start KWS failed", e)
            scope.launch {
                delay(1000)
                startWakeUpListening()
            }
        }
    }

    /**
     * 唤醒词检测到，触发唤醒流程
     * 对应原 MainActivity.triggerWakeUp(persona)
     *
     * @param persona 唤醒后切换到的人格
     */
    fun onWakeWordDetected(persona: LlmManager.Persona = LlmManager.Persona.LUOBO) {
        if (currentState != ConversationState.DORMANT) {
            Log.w(TAG, "onWakeWordDetected ignored, current state=$currentState")
            return
        }

        // 快速唤醒路径：已经是目标人格，直接唤醒不切换
        val isFastWakeUp = llmManager.getPersona() == persona
        if (isFastWakeUp) {
            Log.i(TAG, "Fast WakeUp: Already in $persona persona, skipping switch")
            transitionTo(ConversationState.AWAKENING)
            isInContinuousConversation = true
            audioManager.stopKwsListening()
            startWakeUpSequence(persona)
            return
        }

        // 常规唤醒路径：需要完整切换人格
        Log.i(TAG, "Full WakeUp: Switching to $persona persona")

        if (!PersonaSwitchManager.tryStartSwitch("onWakeWordDetected")) {
            Log.w(TAG, "onWakeWordDetected: switch in progress, drop request")
            return
        }

        try {
            transitionTo(ConversationState.AWAKENING)
            isInContinuousConversation = true
            Log.i(TAG, "Wake up triggered! persona=$persona, State: DORMANT → AWAKENING")

            // 切断旧流
            audioManager.stopKwsListening()
            audioManager.stopSpeaking()
            audioManager.stopListening()
            llmManager.cancelRequest()

            // 数据层前置切换
            val config = PersonaRegistry.getConfig(persona)
            val voiceOption = voicePreferenceStore.resolveVoiceForPersona(persona)
            audioManager.setTtsVoice(voiceOption.voiceId)
            Log.d(TAG, "TTS voice set to: ${voiceOption.voiceId} (${voiceOption.displayName})")
            llmManager.setPersona(persona)

            // 渲染层切换
            expressionManager.setStyle(config.expressionStyle)
            Log.i(TAG, "Persona switched: $persona, style=${config.expressionStyle}")

            PersonaSwitchManager.finishSwitch("onWakeWordDetected")
        } catch (e: Exception) {
            Log.e(TAG, "onWakeWordDetected failed", e)
            PersonaSwitchManager.forceRelease("onWakeWordDetected error")
            return
        }

        startWakeUpSequence(persona)
    }

    /**
     * 启动 ASR 识别（进入 LISTENING 状态）
     * 对应原 MainActivity.startAsrRecognition(asrOnly)
     *
     * @param asrOnly 是否为 ASR 单独测试模式
     */
    fun startAsrRecognition(asrOnly: Boolean = false) {
        audioManager.bargeInEnabled = true

        // 确保 KWS 完全停止
        if (audioManager.isKwsListening()) {
            Log.d(TAG, "startAsrRecognition: KWS still running, stopping first...")
            audioManager.stopKwsListening()
        }
        // 停止可能残留的 ASR
        if (audioManager.isListening()) {
            Log.d(TAG, "startAsrRecognition: ASR already listening, stopping first...")
            audioManager.stopListening()
        }
        // 停止可能还在播放的 TTS
        if (audioManager.isSpeaking()) {
            Log.d(TAG, "startAsrRecognition: TTS still speaking, stopping first...")
            audioManager.stopSpeaking()
        }

        // 生成新的会话ID
        val sessionId = audioManager.startNewSession()
        transitionTo(ConversationState.LISTENING)
        Log.i(TAG, "State: → LISTENING, starting ASR, sessionId=$sessionId")

        if (isInContinuousConversation) {
            resetContinuousConversationTimeout()
        }

        // 等待100ms确保旧资源完全释放
        scope.launch {
            delay(100)
            if (currentState != ConversationState.LISTENING) {
                Log.d(TAG, "startAsrRecognition: state changed during delay, aborting")
                return@launch
            }
            if (sessionId != audioManager.getCurrentSessionId()) {
                Log.w(TAG, "startAsrRecognition: sessionId=$sessionId expired, aborting")
                return@launch
            }
            audioManager.startListening(sessionId, object : AsrManager.AsrResultCallback {
                override fun onRecordingStarted(sessionId: Long) {
                    if (sessionId != audioManager.getCurrentSessionId()) {
                        Log.w(TAG, "ASR onRecordingStarted: stale session $sessionId, dropped")
                        return
                    }
                }
                override fun onSpeechStarted(sessionId: Long) {}
                override fun onSpeechStopped(sessionId: Long) {}
                override fun onPartialResult(text: String, sessionId: Long) {}
                override fun onAudioVolume(rms: Int, sessionId: Long) {}

                override fun onResult(text: String, sessionId: Long) {
                    mainHandler.post {
                        if (sessionId != audioManager.getCurrentSessionId()) {
                            Log.w(TAG, "ASR onResult: stale session $sessionId, dropped. text=$text")
                            return@post
                        }
                        onAsrResult(text, asrOnly)
                    }
                }

                override fun onError(errorMsg: String, sessionId: Long) {
                    mainHandler.post {
                        if (sessionId != audioManager.getCurrentSessionId()) {
                            Log.w(TAG, "ASR onError: stale session $sessionId, dropped")
                            return@post
                        }
                        onAsrError(errorMsg)
                    }
                }
            })
        }
    }

    /**
     * ASR 识别结果处理
     * 对应原 MainActivity.processAsrResult(text, asrOnly)
     *
     * @param text ASR 识别结果
     * @param asrOnly 是否为 ASR 单独测试模式
     */
    fun onAsrResult(text: String, asrOnly: Boolean = false) {
        // 切换期间输入屏蔽
        if (PersonaSwitchManager.isSwitching) {
            Log.i(TAG, "ASR result dropped: persona switch in progress")
            return
        }

        if (asrOnly) {
            val tmpSessionId = audioManager.generateTempSessionId()
            audioManager.startSpeaking("识别结果：$text", tmpSessionId)
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
                llmManager.exitConversation()
                audioManager.interruptConversation(InterruptReason.USER_SPEECH)
                expressionManager.requestExpression(ExpressionType.HAPPY, duration = 1200, force = true)
                val byeSessionId = audioManager.generateTempSessionId()
                audioManager.startSpeaking("好的，随时可以叫我，拜拜", byeSessionId)
                return
            }

            // 关机关键词检测
            if (isPowerOffWord(raw)) {
                expressionManager.powerOff()
                exitContinuousConversation()
                llmManager.exitConversation()
                audioManager.stopSpeaking()
                return
            }

            // ========== P1 本地意图快速响应（跳过 LLM，秒级响应） ==========
            val intentResult = LocalIntentResolver.resolve(text)
            if (intentResult.matched) {
                Log.i(TAG, "Local intent matched: ${intentResult.intent}, text=\"$text\"")
                when (intentResult.intent) {
                    LocalIntentResolver.IntentType.STOP_SPEAKING -> {
                        audioManager.interruptConversation(InterruptReason.USER_SPEECH)
                        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
                        if (isInContinuousConversation) {
                            scope.launch {
                                delay(300)
                                startAsrRecognition()
                            }
                        }
                        return
                    }
                    LocalIntentResolver.IntentType.BATTERY_STATUS -> {
                        val batteryPercent = getCurrentBatteryPercent()
                        val response = LocalIntentResolver.fillBatteryResponse(batteryPercent)
                        val tmpSessionId = audioManager.generateTempSessionId()
                        audioManager.startSpeaking(response, tmpSessionId)
                        return
                    }
                    else -> {
                        val tmpSessionId = audioManager.generateTempSessionId()
                        audioManager.startSpeaking(intentResult.responseText, tmpSessionId)
                        return
                    }
                }
            }

            sendTextToLlm(text)
        } else {
            // 未识别到内容
            if (isInContinuousConversation) {
                expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 800, force = true)
                scope.launch {
                    delay(800)
                    startAsrRecognition()
                }
            } else {
                expressionManager.requestExpression(ExpressionType.IDLE, force = true)
            }
        }
    }

    /**
     * Barge-in 打断处理
     * 对应原 MainActivity 中 onBargeIn 回调
     *
     * @param text Barge-in 识别到的文本（可能为空，表示用户刚开口）
     */
    fun onBargeIn(text: String) {
        Log.d(TAG, "Barge-in received, text=[$text]")
        if (callback?.isFaceRegistering() == true) {
            Log.d(TAG, "Barge-in suppressed during face registration")
            return
        }
        // 1. 取消正在进行的 LLM 请求
        llmManager.cancelRequest()
        // 2. 取消旧会话
        audioManager.interruptConversation(InterruptReason.USER_SPEECH)
        // 3. 进入连续对话模式
        if (!isInContinuousConversation) {
            isInContinuousConversation = true
        }
        resetContinuousConversationTimeout()

        // 4. 处理识别文本
        if (text.isNotBlank()) {
            audioManager.startNewSession()
            onAsrResult(text, asrOnly = false)
        } else {
            startAsrRecognition()
        }
    }

    /**
     * TTS 播放完成处理
     * 对应原 MainActivity 中 ttsFinishListener
     */
    fun onSpeakFinish() {
        if (callback?.isFaceRegistering() == true) {
            Log.d(TAG, "TTS finish during face registration, suppressed")
            return
        }

        ModuleStateManager.onTtsFinishSpeaking()

        scope.launch {
            delay(500)
            if (isInContinuousConversation) {
                startAsrRecognition()
            } else {
                startWakeUpListening()
            }
        }
    }

    /**
     * 中断当前对话（全链路取消）
     *
     * @param reason 中断原因
     */
    fun interrupt(reason: InterruptReason) {
        Log.i(TAG, "interrupt: reason=$reason, currentState=$currentState")
        audioManager.interruptConversation(reason)
        transitionTo(ConversationState.COOLDOWN)
        exitContinuousConversation()
    }

    /**
     * 对话中切换人格
     * 对应原 MainActivity.switchPersona(target)
     *
     * @param target 目标人格
     */
    fun switchPersona(target: LlmManager.Persona) {
        if (!PersonaSwitchManager.tryStartSwitch("switchPersona")) {
            Log.w(TAG, "switchPersona: switch in progress, drop request")
            return
        }

        try {
            val config = PersonaRegistry.getConfig(target)

            // 已经是当前人格 → 不切换，回一句话
            if (llmManager.getPersona() == target) {
                PersonaSwitchManager.finishSwitch("switchPersona")
                val tmpSessionId = audioManager.generateTempSessionId()
                audioManager.startSpeaking(config.alreadyHereQuote, tmpSessionId)
                return
            }

            Log.i(TAG, "Switching persona in conversation: ${llmManager.getPersona()} → $target")

            // 切断旧流
            audioManager.stopSpeaking()
            audioManager.stopListening()
            llmManager.cancelRequest()

            // 数据层前置切换
            val voiceOption = voicePreferenceStore.resolveVoiceForPersona(target)
            audioManager.setTtsVoice(voiceOption.voiceId)
            Log.d(TAG, "TTS voice set to: ${voiceOption.voiceId} (${voiceOption.displayName})")
            llmManager.setPersona(target)

            // 渲染层切换（动画）
            expressionManager.switchStyle(config.expressionStyle, animated = true)

            // 等待动画完成后释放锁
            scope.launch {
                try {
                    delay(800)
                    PersonaSwitchManager.finishSwitch("switchPersona")
                    Log.d(TAG, "Persona switch animation complete, lock released")
                } catch (e: Exception) {
                    Log.e(TAG, "Switch animation delay failed", e)
                    PersonaSwitchManager.forceRelease("switchPersona animation error")
                }
            }

            // 播放登场台词
            val intro = config.introQuote
            transitionTo(ConversationState.SPEAKING)
            audioManager.interruptConversation(InterruptReason.HARDWARE_COMMAND)

            val introSessionId = audioManager.generateTempSessionId()
            audioManager.startSpeaking(intro, introSessionId, object : TtsManager.TtsStateCallback {
                override fun onStart(sessionId: Long) {}
                override fun onDone(sessionId: Long) {
                    mainHandler.post {
                        Log.d(TAG, "Persona switch intro finished, resuming ASR")
                        if (!isInContinuousConversation) {
                            isInContinuousConversation = true
                        }
                        resetContinuousConversationTimeout()
                        startAsrRecognition()
                    }
                }
                override fun onError(errorCode: Int, sessionId: Long) {
                    mainHandler.post {
                        Log.w(TAG, "Persona switch intro error: $errorCode")
                        if (!isInContinuousConversation) {
                            isInContinuousConversation = true
                        }
                        startAsrRecognition()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "switchPersona failed", e)
            PersonaSwitchManager.forceRelease("switchPersona error")
        }
    }

    /**
     * 软重置（语音相关部分）
     * 对应原 MainActivity.performSoftReset() 中的语音逻辑
     * 视觉和 Demo 相关由 MainActivity 处理
     */
    fun performSoftReset() {
        Log.i(TAG, "Performing soft reset (voice part)...")
        scope.launch {
            audioManager.stopListening()
            audioManager.stopKwsListening()
            audioManager.stopSpeaking()
            llmManager.cancelRequest()
            llmManager.exitConversation()
            isInContinuousConversation = false
            audioManager.interruptConversation(InterruptReason.HARDWARE_COMMAND)
            transitionTo(ConversationState.COOLDOWN)
            continuousConversationTimeoutJob?.cancel()
            delay(200)
            try { startWakeUpListening() } catch (e: Exception) {
                Log.e(TAG, "Restart wake up failed", e)
            }
            Log.i(TAG, "Soft reset (voice part) complete")
        }
    }

    /**
     * Activity onResume 时调用（语音相关部分）
     */
    fun onResume() {
        if (audioManager.isSpeaking() || audioManager.isListening() || audioManager.isKwsListening()) {
            return
        }
        if (isInContinuousConversation) {
            startAsrRecognition()
        } else {
            startWakeUpListening()
        }
    }

    /**
     * Activity onPause 时调用（语音相关部分）
     */
    fun onPause() {
        transitionTo(ConversationState.COOLDOWN)
        isInContinuousConversation = false
        audioManager.interruptConversation(InterruptReason.SYSTEM_ERROR)
        continuousConversationTimeoutJob?.cancel()
        continuousConversationTimeoutJob = null
        try {
            audioManager.stopListening()
            audioManager.stopKwsListening()
            audioManager.stopSpeaking()
        } catch (e: Exception) {
            Log.w(TAG, "onPause stop audio failed", e)
        }
    }

    /**
     * Activity onDestroy 时调用（语音相关部分）
     */
    fun onDestroy() {
        audioManager.stopSpeaking()
        audioManager.stopListening()
        audioManager.stopKwsListening()
        llmManager.cancelRequest()
        continuousConversationTimeoutJob?.cancel()
        continuousConversationTimeoutJob = null
        callback = null
        Log.i(TAG, "ConversationCoordinator destroyed")
    }

    // ==================== 内部方法 ====================

    /**
     * 状态转换（集中管理，可追溯）
     */
    private fun transitionTo(newState: ConversationState) {
        if (currentState != newState) {
            Log.i(TAG, "State transition: $currentState → $newState")
            currentState = newState
            callback?.onStateChanged(newState)
        }
    }

    /**
     * 唤醒应答序列（快速唤醒和常规唤醒共用）
     */
    private fun startWakeUpSequence(persona: LlmManager.Persona) {
        // 临时关闭 barge-in（唤醒应答是短提示音，不需要打断）
        val wasBargeInEnabled = audioManager.bargeInEnabled
        audioManager.bargeInEnabled = false

        val greeting = buildGreeting(persona)
        expressionManager.requestExpression(ExpressionType.RESPONSE, duration = 800, force = true)
        val wakeSessionId = audioManager.generateTempSessionId()
        audioManager.startSpeaking(greeting, wakeSessionId)

        resetContinuousConversationTimeout()

        // 兜底：动态计算时间后如果还没进入 LISTENING，强制启动 ASR
        val fallbackMs = estimateWakeUpFallbackMs(greeting)
        Log.i(TAG, "Wake-up fallback: greeting=${greeting.length}chars, estimated=${fallbackMs}ms")
        scope.launch {
            delay(fallbackMs)
            audioManager.bargeInEnabled = wasBargeInEnabled
            if (currentState == ConversationState.AWAKENING) {
                Log.w(TAG, "Fallback: TTS finish callback missed, force starting ASR after ${fallbackMs}ms")
                transitionTo(ConversationState.LISTENING)
                callback?.onNeedCheckPermissionAndStartAsr()
            }
        }
    }

    /**
     * 根据人格生成问候语
     */
    private fun buildGreeting(persona: LlmManager.Persona): String {
        val name = callback?.getLastRecognizedName()
        return PersonaRegistry.getConfig(persona).buildGreeting(name)
    }

    /**
     * ASR 错误处理
     */
    private fun onAsrError(errorMsg: String) {
        Log.w(TAG, "ASR error: $errorMsg")
        if (isInContinuousConversation) {
            expressionManager.requestExpression(ExpressionType.CONFUSED, duration = 1000, force = true)
            scope.launch {
                delay(1000)
                startAsrRecognition()
            }
        } else {
            expressionManager.requestExpression(ExpressionType.CONFUSED)
            val errSessionId = audioManager.generateTempSessionId()
            audioManager.startSpeaking("抱歉，我没有听清，请再说一遍", errSessionId)
        }
    }

    /**
     * 发送文本给 LLM（流式流水线：LLM SSE → 逐句 TTS）
     */
    private fun sendTextToLlm(text: String) {
        if (text.isBlank()) return

        val sessionId = audioManager.getCurrentSessionId()
        if (sessionId == 0L) {
            Log.w(TAG, "sendTextToLlm: no active conversation, aborting")
            return
        }

        transitionTo(ConversationState.PROCESSING)
        val trimmedText = text.trim()

        // 指令检测：清空记忆
        if (trimmedText.contains("退出对话") || trimmedText.contains("清除记忆") ||
            trimmedText.contains("忘了我") || trimmedText.contains("重置对话")) {
            llmManager.exitConversation()
            exitContinuousConversation()
            audioManager.interruptConversation(InterruptReason.USER_SPEECH)
            val clearSessionId = audioManager.generateTempSessionId()
            audioManager.startSpeaking("好的，我已经清空对话记忆啦，我们重新开始吧~", clearSessionId)
            expressionManager.requestExpression(ExpressionType.HAPPY)
            return
        }

        // 简单长期记忆提取 + 人脸注册触发
        val namePattern1 = Regex("我叫([\\u4e00-\\u9fa5a-zA-Z]{2,4})")
        val namePattern2 = Regex("我是([\\u4e00-\\u9fa5a-zA-Z]{2,4})")
        val nameMatch = namePattern1.find(trimmedText) ?: namePattern2.find(trimmedText)
        if (nameMatch != null) {
            val userName = nameMatch.groupValues[1]
            val invalidNames = setOf("一个", "你的", "机器", "机器人", "AI", "ai", "你朋友", "学生", "好人")
            if (userName !in invalidNames) {
                llmManager.addLongTermMemory("用户的名字是$userName")
                Log.i(TAG, "Name command detected: $userName, starting face registration")
                callback?.onStartFaceRegistration(userName)
                return
            }
        }

        // 开始请求 LLM 时重置超时
        if (isInContinuousConversation) {
            resetContinuousConversationTimeout()
        }

        ModuleStateManager.onLlmStartThinking()
        audioManager.startStreaming(sessionId)

        // 情绪流式解析器
        var isSingingMode = false
        val emotionStreamParser = EmotionParser.StreamParser(
            onEmotionParsed = { emotion ->
                val expr = EmotionParser.emotionToExpression(emotion)
                if (expr != null) {
                    Log.i(TAG, "Emotion-driven expression: $emotion -> $expr")
                    expressionManager.requestExpression(
                        type = expr, intensity = 100, duration = null,
                        force = true, source = ExpressionSource.LLM_EMOTION
                    )
                }
                if (emotion == "singing") {
                    isSingingMode = true
                    audioManager.applySingingVoice()
                    Log.i(TAG, "Singing mode activated")
                }
            },
            onTextReady = { textChunk ->
                audioManager.feedStreamChunk(textChunk)
            }
        )

        llmManager.sendQueryStream(sessionId, text, object : LlmManager.StreamCallback {
            override fun onDelta(delta: String, sessionId: Long) {
                mainHandler.post {
                    if (sessionId != audioManager.getCurrentSessionId()) {
                        Log.w(TAG, "LLM onDelta: stale session $sessionId, dropped")
                        return@post
                    }
                    emotionStreamParser.feed(delta)
                }
            }

            override fun onComplete(fullText: String, sessionId: Long) {
                mainHandler.post {
                    if (sessionId != audioManager.getCurrentSessionId()) {
                        Log.w(TAG, "LLM onComplete: stale session $sessionId, dropped")
                        return@post
                    }
                    ModuleStateManager.onLlmFinish()
                    if (isInContinuousConversation) {
                        resetContinuousConversationTimeout()
                    }
                    emotionStreamParser.flush()
                    emotionStreamParser.reset()
                    audioManager.finishStreaming()
                    if (isSingingMode) {
                        isSingingMode = false
                        audioManager.restoreDefaultVoice(expressionManager.getCurrentStyle())
                        Log.i(TAG, "Singing mode ended, voice restored")
                    }
                }
            }

            override fun onError(errorMsg: String, sessionId: Long) {
                mainHandler.post {
                    if (sessionId != audioManager.getCurrentSessionId()) {
                        Log.w(TAG, "LLM onError: stale session $sessionId, dropped")
                        return@post
                    }
                    ModuleStateManager.onLlmFinish()
                    if (isInContinuousConversation) {
                        resetContinuousConversationTimeout()
                    }
                    emotionStreamParser.reset()
                    if (isSingingMode) {
                        isSingingMode = false
                        audioManager.restoreDefaultVoice(expressionManager.getCurrentStyle())
                    }
                    audioManager.stopSpeaking()
                    expressionManager.requestExpression(ExpressionType.SAD)
                    val errSessionId = audioManager.generateTempSessionId()
                    audioManager.startSpeaking("抱歉，我现在无法回答你的问题，请稍后再试", errSessionId)
                }
            }
        })
    }

    /**
     * 重置连续对话空闲超时
     */
    private fun resetContinuousConversationTimeout() {
        continuousConversationTimeoutJob?.cancel()
        continuousConversationTimeoutJob = scope.launch {
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

    /**
     * 获取当前设备电量百分比
     */
    private fun getCurrentBatteryPercent(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent in 0..100) percent else -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery percent", e)
            -1
        }
    }

    /**
     * 结束词宽松匹配
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

    /**
     * 根据文本长度预估唤醒兜底时间
     */
    private fun estimateWakeUpFallbackMs(text: String): Long {
        val charCount = text.length.coerceAtLeast(1)
        val playbackMs = (charCount / TTS_CHARS_PER_SECOND * 1000).toLong()
        val estimated = (playbackMs * WAKE_UP_FALLBACK_MARGIN).toLong() + TTS_SYNTHESIS_DELAY_MS
        return estimated.coerceIn(WAKE_UP_FALLBACK_MIN_MS, WAKE_UP_FALLBACK_MAX_MS)
    }

    /**
     * 获取当前状态（用于调试）
     */
    fun getCurrentState(): ConversationState = currentState

    /**
     * 是否处于连续对话模式
     */
    fun isContinuousConversation(): Boolean = isInContinuousConversation

    /**
     * 手动进入连续对话模式（如手动触发 ASR 时）
     */
    fun enterContinuousConversation() {
        isInContinuousConversation = true
        resetContinuousConversationTimeout()
    }

    /**
     * 刷新连续对话空闲超时（如人脸注册完成后调用，避免注册期间超时退出）
     */
    fun refreshContinuousConversationTimeout() {
        if (isInContinuousConversation) {
            resetContinuousConversationTimeout()
        }
    }
}
