/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.llm.LlmManager
import kotlinx.coroutines.*

/**
 * 音频管理器
 * 统一管理ASR语音识别和TTS语音播报，对外提供统一接口
 */
class AudioManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AudioManager"
        private const val ECHO_TAIL_MS = 200L
        @Volatile
        private var instance: AudioManager? = null

        fun getInstance(context: Context): AudioManager {
            return instance ?: synchronized(this) {
                instance ?: AudioManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val asrManager: AsrManager by lazy { AsrManager.getInstance(context) }
    private val ttsManager: TtsManager by lazy { TtsManager.getInstance(context) }
    private val kwsManager: KwsManager by lazy { KwsManager.getInstance(context) }
    private val llmManager: LlmManager by lazy { LlmManager.getInstance(context) }

    // ========== 统一会话管理（P0：conversationJob + sessionId）==========
    /** 当前会话的 Job，作为所有会话相关协程的父 Job，取消它可一次性终止整轮对话 */
    @Volatile private var conversationJob: Job? = null
    /** 当前活跃的会话ID，ASR→LLM→TTS 全链路复用 */
    @Volatile private var currentSessionId: Long = 0L
    /** 当前会话的完整状态对象 */
    @Volatile private var currentSession: ConversationSession? = null
    /** 会话ID生成器（从1递增，0表示无效） */
    private val sessionIdGenerator = java.util.concurrent.atomic.AtomicLong(0L)

    // ========== 打断（Barge-in）功能 ==========
    var bargeInEnabled = true          // 是否启用语音打断：TTS播报时用户说话自动停止播报

    // ========== P3 句间倾听窗：播放状态门控 ==========
    // 最后一次 TTS 播放结束时间戳（句间暂停时设置，用于计算回声尾巴）
    // 0 表示正在播放中（shouldListen 直接返回 false）
    @Volatile private var lastTtsStopTime = 0L
    // 回声尾巴：播放结束后短暂屏蔽麦克风，防止扬声器余音被误判为用户说话


    /**
     * 当前是否可以监听用户语音（门控）
     *
     * 规则：
     * - TTS 正在播放 → false（完全不监听，防自激）
     * 播放结束后 ECHO_TAIL_MS 内 → false（回声尾巴，防余音误触）
     * 句间倾听窗且超过回声尾巴 → true（可以检测用户打断）
     *
     * @return true 表示当前可以监听用户语音
     */
    fun shouldListen(): Boolean {
        if (isSpeaking()) return false
        val stopTime = lastTtsStopTime
        if (stopTime == 0L) return false  // 正在播放中
        return System.currentTimeMillis() - stopTime > ECHO_TAIL_MS
    }

    // ========== 自适应回声保护期 ==========
    // 保护期不是固定的，根据麦克风实时音量动态伸缩：
    // - 回声强（音量大）→ 保护期自动延长
    // - 回声弱（音量小）→ 保护期很快结束，用户可以立刻打断
    var bargeInGuardMinMs = 300L       // 最小保护期（无论回声多弱，至少等这么久，防开头爆音）
    var bargeInGuardMaxMs = 2000L      // 最大保护期（无论回声多强，最多等这么久，兜底）
    @Volatile private var bargeInGuardEndTime = 0L  // 当前保护期结束时间戳（动态调整）
    var bargeInEchoThreshold = 1000    // 回声音量阈值（RMS值），高于此值认为回声强，需要延长保护期
    private val bargeInGuardExtendMs = 100L // 每次检测到强回声时延长的保护期时长
    @Volatile var ttsStartTime = 0L              // 当前TTS播报实际开始时间戳（在onRecordingStarted回调中设置）

    // 语音持续确认：检测到speech_started后，再确认语音持续了这么久才真正触发打断
    // 避免短促的回声/爆音/点击声被误判为用户说话
    private val bargeInConfirmMs = 150L
    private var bargeInConfirmRunnable: Runnable? = null
    @Volatile private var isSpeechOngoing = false  // 当前VAD是否检测到语音正在进行
    @Volatile private var isBargeInTriggering = false  // 防止triggerBargeIn重入
    @Volatile private var suppressSpeakFinish = false  // 打断/取消时抑制speakFinish回调，避免双重启动ASR

    // 打断回调接口
    interface OnBargeInListener {
        fun onBargeIn(text: String)
    }
    private var bargeInListener: OnBargeInListener? = null

    fun setOnBargeInListener(listener: OnBargeInListener?) {
        bargeInListener = listener
    }

    //TTS播放完成回调接口
    interface OnSpeakFinishListener{
        fun onSpeakFinsh()
    }
    private var speakFinishListener: OnSpeakFinishListener? = null

    fun setOnSpeakFinishListener(listener: OnSpeakFinishListener?) {
        speakFinishListener = listener
    }

    /**
     * 初始化音频模块（ASR + TTS + KWS 并行启动）
     *
     * ASR/TTS/KWS互不依赖，使用协程async同时初始化，
     * 耗时从串行初始化降低到 max(t_asr, t_tts, t_kws)。
     */
    fun init() {
        scope.launch {
            val asrDeferred = async(Dispatchers.IO) {
                try {
                    asrManager.init()
                } catch (e: Exception) {
                    Log.e(TAG, "ASR init failed", e)
                }
            }
            val ttsDeferred = async(Dispatchers.Main) {
                try {
                    ttsManager.init()
                } catch (e: Exception) {
                    Log.e(TAG, "TTS init failed", e)
                }
            }
            val kwsDeferred = async(Dispatchers.IO) {
                try {
                    kwsManager.init()
                } catch (e: Exception) {
                    Log.e(TAG, "KWS init failed", e)
                }
            }
            asrDeferred.await()
            ttsDeferred.await()
            kwsDeferred.await()
            // P3：设置句间倾听窗回调，在每句播完后的停顿期间记录播放结束时间
            ttsManager.setOnInterSentenceListener(object : TtsEngine.OnInterSentenceListener {
                override fun onInterSentenceStart(remainingCount: Int) {
                    // 句间暂停开始：记录播放结束时间，启动回声尾巴计时
                    lastTtsStopTime = System.currentTimeMillis()
                    Log.d(TAG, "Inter-sentence window start: remaining=$remainingCount, " +
                            "echo tail=${ECHO_TAIL_MS}ms, listenable after ${ECHO_TAIL_MS}ms")
                }
                override fun onInterSentenceEnd(interrupted: Boolean) {
                    Log.d(TAG, "Inter-sentence window end: interrupted=$interrupted")
                }
            })
            Log.d(TAG, "AudioManager initialized (ASR + TTS + KWS parallel)")
        }
    }

    // ========== 统一会话管理（P0：conversationJob + sessionId）==========

    /**
     * 启动新一轮对话会话
     *
     * 1. 取消旧的 conversationJob（旧会话的所有协程被协作式取消）
     * 2. 创建新的 Job 作为本轮会话的父 Job
     * 3. 生成递增的 sessionId
     * 4. 创建新的 ConversationSession 状态对象
     *
     * 调用时机：startAsrRecognition（新一轮对话开始）、Barge-in 触发后重建会话
     *
     * @return 新的会话ID，全链路（ASR→LLM→TTS）携带此ID
     */
    fun startNewSession(): Long {
        // 取消旧会话的 Job，所有子协程收到 CancellationException
        conversationJob?.cancel("New session started", null)
        // 创建新的 Job，作为本轮会话所有协程的父作用域
        conversationJob = Job()
        // 生成递增的会话ID（从1开始，0表示无效/无会话）
        val newId = sessionIdGenerator.incrementAndGet()
        currentSessionId = newId
        currentSession = ConversationSession(
            id = newId,
            startedAt = System.currentTimeMillis(),
            state = ConversationState.IDLE
        )
        Log.i(TAG, "New conversation session started: id=$newId, job=$conversationJob")
        return newId
    }

    /**
     * 中断当前对话会话
     *
     * 一次性取消整轮对话的所有模块：
     * 1. 取消 conversationJob（所有会话相关协程终止）
     * 2. 停止 ASR 识别
     * 3. 取消 LLM 请求
     * 4. 停止 TTS 播报（内部清空队列）
     * 5. 标记会话状态为 INTERRUPTED
     *
     * 调用时机：Barge-in（用户打断）、人格切换、唤醒/休眠、软重置、关机
     *
     * @param reason 中断原因，用于日志和状态追踪
     */
    fun interruptConversation(reason: InterruptReason) {
        val oldId = currentSessionId
        if (oldId == 0L && conversationJob == null) {
            Log.d(TAG, "interruptConversation: no active session, reason=$reason")
            return
        }

        Log.i(TAG, "Conversation interrupted: id=$oldId, reason=$reason")

        // 1. 取消会话 Job（所有子协程协作式取消）
        conversationJob?.cancel("Interrupted: $reason", null)
        conversationJob = null

        // 2. 依次取消各模块
        try { asrManager.stopListening() } catch (e: Exception) { Log.w(TAG, "Stop ASR on interrupt failed", e) }
        try { llmManager.cancelRequest() } catch (e: Exception) { Log.w(TAG, "Cancel LLM on interrupt failed", e) }
        try { ttsManager.stop() } catch (e: Exception) { Log.w(TAG, "Stop TTS on interrupt failed", e) }

        // 3. 重置会话状态
        currentSession?.let {
            it.state = ConversationState.INTERRUPTED
        }
        currentSession = null
        currentSessionId = 0L
    }

    /**
     * 获取当前活跃的会话ID
     * 用于回调校验：回调携带的 sessionId 与此值不匹配则丢弃（旧会话回调）
     *
     * @return 当前会话ID，0表示无活跃会话
     */
    fun getCurrentSessionId(): Long = currentSessionId

    /**
     * 获取当前会话的 Job，供需要绑定到会话生命周期的协程使用
     *
     * 用法：`scope.launch(audioManager.getConversationJob() + Dispatchers.IO) { ... }`
     * 当会话被中断时，此协程会自动收到 CancellationException
     *
     * @return 当前会话 Job，无活跃会话时返回 null
     */
    fun getConversationJob(): Job? = conversationJob

    /**
     * 生成一个独立的临时会话ID（不属于对话流程）
     * 用于问候语、错误提示、试听音等独立 TTS 播报
     *
     * @return 临时会话ID（正整数，不设为 currentSessionId）
     */
    fun generateTempSessionId(): Long = sessionIdGenerator.incrementAndGet()

    // ========== KWS 关键词唤醒 ==========

    /**
     * 开始KWS关键词唤醒监听（DORMANT状态）
     *
     * 互斥规则：
     * 1. 先停止ASR（如果正在运行）
     * 2. 等200ms确保AudioRecord完全释放
     * 3. 启动KWS独立录音通道
     *
     * @param callback KWS检测回调
     */
    fun startKwsListening(callback: KwsManager.KwsCallback) {
        scope.launch {
            try {
                // 停止ASR，释放录音通道
                if (asrManager.isListening()) {
                    asrManager.stopListening()
                }
                // 200ms安全间隔，确保AudioRecord完全释放
                // （部分机型AudioRecord释放有延迟，不等待会导致KWS初始化失败）
                delay(200)
                @Suppress("MissingPermission")
                kwsManager.start(callback)
                Log.d(TAG, "KWS listening started")
            } catch (e: Exception) {
                Log.e(TAG, "Start KWS listening failed", e)
                mainHandler.post { callback.onError("KWS启动失败: ${e.message}") }
            }
        }
    }

    /**
     * 停止KWS关键词唤醒监听
     *
     * 停止后等200ms再启动ASR，确保录音通道完全释放
     */
    fun stopKwsListening() {
        kwsManager.stop()
        Log.d(TAG, "KWS listening stopped")
    }

    /**
     * KWS是否正在监听
     */
    fun isKwsListening(): Boolean = kwsManager.isRunning()

    /**
     * 开始ASR语音识别
     * @param sessionId 会话唯一ID，透传给 AsrManager 用于回调校验
     * @param callback 识别结果回调
     * @param triggerExpression 是否触发识别中表情（唤醒监听传false，正式识别传true）
     */
    fun startListening(
        sessionId: Long,
        callback: AsrManager.AsrResultCallback,
        triggerExpression: Boolean = true
    ) {
        // 如果正在播报，先停止
        if (ttsManager.isSpeaking()) {
            ttsManager.stop()
        }
        // 启动ASR前先停止KWS，释放录音通道
        if (kwsManager.isRunning()) {
            kwsManager.stop()
        }
        asrManager.startListening(sessionId, callback, triggerExpression)
    }

    /**
     * 停止ASR语音识别
     */
    fun stopListening() {
        asrManager.stopListening()
    }

    /**
     * 是否正在识别中
     */
    fun isListening(): Boolean {
        return asrManager.isListening()
    }

    /**
     * 预合成并缓存TTS文本（不播放）
     * 用于提前合成问候语等常用文本，播放时秒开
     */
    fun preloadTts(text: String) {
        ttsManager.preload(text)
    }

    /**
     * 检查TTS文本是否已预加载完成
     */
    fun isTtsPreloaded(text: String): Boolean {
        return ttsManager.isPreloaded(text)
    }

    /**
     * 开始TTS播报文本
     * @param text 要播报的文本
     * @param sessionId 会话唯一ID，透传给 TtsManager
     * @param callback 播报状态回调
     */
    fun startSpeaking(text: String, sessionId: Long, callback: TtsManager.TtsStateCallback? = null) {
        // 重置 suppress 标记，新的一轮TTS正常走 speakFinish
        suppressSpeakFinish = false
        // TTS播报前先停止所有ASR会话，确保扬声器播放时只有一个音频消费者
        // 避免TTS声音被麦克风录入导致误触发
        if (asrManager.isListening()) {
            asrManager.stopListening()
        }

        // 包装回调：TTS开始时mute KWS防止回声，结束时unmute
        val wrappedCallback = object : TtsManager.TtsStateCallback {
            override fun onStart(sessionId: Long) {
                Log.d(TAG, "TTS actual start, barge-in guard will begin when mic starts")
                lastTtsStopTime = 0L  // 正在播放，门控关闭
                kwsManager.setMuted(true)  // TTS开始，KWS静音防回声
                callback?.onStart(sessionId)
                // 打断模式：TTS开始后启动后台静默ASR监听，用于检测用户打断
                if (bargeInEnabled && !asrManager.isListening()) {
                    startBargeInListen()
                }
            }
            override fun onDone(sessionId: Long) {
                lastTtsStopTime = System.currentTimeMillis()  // 播放结束，开始计时回声尾巴
                kwsManager.setMuted(false)  // TTS结束，KWS解除静音
                stopBargeInListen()
                callback?.onDone(sessionId)
                // 打断/取消导致的stop不走speakFinish，避免双重启动ASR
                if (!suppressSpeakFinish) {
                    speakFinishListener?.onSpeakFinsh()
                } else {
                    Log.d(TAG, "speakFinish suppressed (barge-in/cancel)")
                }
            }
            override fun onError(errorCode: Int, sessionId: Long) {
                lastTtsStopTime = System.currentTimeMillis()
                kwsManager.setMuted(false)  // TTS出错也解除静音
                stopBargeInListen()
                callback?.onError(errorCode, sessionId)
                if (!suppressSpeakFinish) {
                    speakFinishListener?.onSpeakFinsh()
                }
            }
        }

        ttsManager.speak(text, sessionId, wrappedCallback)
    }

    // ========== 流式播报（LLM边生成边播）==========

    private var streamBuffer = StringBuilder()
    private var streamFirstSent = false
    /** 当前流式播报的会话ID，feedStreamChunk 时透传给 TTS */
    @Volatile private var currentStreamSessionId: Long = 0L
    // 断句分隔符：句号、感叹号、问号、换行、分号、中文逗号也可做软断句
    private val sentenceDelimiters = charArrayOf('。', '！', '？', '\n', '；', '!', '?', '.')
    // 最小缓冲长度：不足一个句子时先攒着，但超过这个长度强制发（防止标点缺失时卡太久）
    private val minFlushLen = 40

    /**
     * 开始流式播报会话
     *
     * 在 LLM 流式请求开始前调用，重置内部状态。
     * 之后每次收到 LLM delta 调 [feedStreamChunk]。
     *
     * @param sessionId 会话唯一ID，所有后续 speakChunk 均携带此ID
     * @param callback 播报状态回调
     */
    fun startStreaming(sessionId: Long, callback: TtsManager.TtsStateCallback? = null) {
        Log.i(TAG, "[DIAG] startStreaming called, sessionId=$sessionId, bargeInEnabled=$bargeInEnabled, asrListening=${asrManager.isListening()}")
        currentStreamSessionId = sessionId
        // 重置 suppress 标记，新的一轮流式播报正常走 speakFinish
        suppressSpeakFinish = false
        // TTS播报前先停止所有ASR会话
        if (asrManager.isListening()) {
            Log.d(TAG, "startStreaming: stopping ASR first")
            asrManager.stopListening()
        }
        streamBuffer.setLength(0)
        streamFirstSent = false
        Log.d(TAG, "[DIAG] startStreaming: state reset, buffer cleared")

        // 包装回调
        val wrappedCallback = object : TtsManager.TtsStateCallback {
            override fun onStart(sessionId: Long) {
                Log.i(TAG, "[DIAG] Stream TTS onStart callback triggered, sessionId=$sessionId")
                lastTtsStopTime = 0L
                kwsManager.setMuted(true)  // TTS开始，KWS静音防回声
                callback?.onStart(sessionId)
                if (bargeInEnabled && !asrManager.isListening()) {
                    startBargeInListen()
                }
            }
            override fun onDone(sessionId: Long) {
                Log.i(TAG, "[DIAG] Stream TTS onDone callback triggered, sessionId=$sessionId, suppressSpeakFinish=$suppressSpeakFinish")
                lastTtsStopTime = System.currentTimeMillis()
                kwsManager.setMuted(false)  // TTS结束，KWS解除静音
                stopBargeInListen()
                callback?.onDone(sessionId)
                if (!suppressSpeakFinish) {
                    Log.d(TAG, "[DIAG] Calling speakFinishListener after stream onDone")
                    speakFinishListener?.onSpeakFinsh()
                } else {
                    Log.d(TAG, "speakFinish suppressed (barge-in/cancel)")
                }
            }
            override fun onError(errorCode: Int, sessionId: Long) {
                Log.e(TAG, "[DIAG] Stream TTS onError callback triggered, code=$errorCode, sessionId=$sessionId")
                lastTtsStopTime = System.currentTimeMillis()
                kwsManager.setMuted(false)  // TTS出错也解除静音
                stopBargeInListen()
                callback?.onError(errorCode, sessionId)
                if (!suppressSpeakFinish) {
                    speakFinishListener?.onSpeakFinsh()
                }
            }
        }

        // 告诉 TTSManager 用流式模式的回调
        ttsManager.setStateCallback(wrappedCallback)
        Log.d(TAG, "[DIAG] startStreaming: wrappedCallback set to ttsManager")
        // 注意：不在这里立即 speak，等第一个 chunk 到来才 speakChunk
    }

    /**
     * 喂入一段 LLM 增量文本
     *
     * 内部做句子切分：收到完整句子就立即送 TTS 播放（首句立即开始，后续排队）。
     * 未到句号的碎片先攒在 buffer 里，达到阈值也强制 flush。
     * 必须在主线程调用（TextToSpeech.speak 需要主线程）。
     */
    fun feedStreamChunk(delta: String) {
        if (delta.isEmpty()) return
        streamBuffer.append(delta)

        // 尝试按句子切分并 flush
        flushCompleteSentences(force = false)
    }

    /**
     * 流式播报结束：把 buffer 里剩余的碎片全部播出去
     */
    fun finishStreaming() {
        // 强制 flush 剩余内容
        flushCompleteSentences(force = true)
        ttsManager.endStream()

        // 边界情况：LLM 返回空内容，一个 chunk 都没发出去
        // 此时 TTS 队列为空，UtteranceProgressListener 不会回调 onDone
        // 需要手动触发 speakFinish，否则对话流程会卡死
        if (!streamFirstSent) {
            Log.w(TAG, "Stream TTS: no chunk was ever sent, manually trigger finish")
            stopBargeInListen()
            if (!suppressSpeakFinish) {
                speakFinishListener?.onSpeakFinsh()
            }
        }

        Log.d(TAG, "Stream TTS finished, buffer cleared")
    }

    /**
     * 从 buffer 中提取完整句子送 TTS
     */
    private fun flushCompleteSentences(force: Boolean) {
        val sid = currentStreamSessionId
        while (streamBuffer.isNotEmpty()) {
            // 找最早的句子结束符
            val cutIdx = streamBuffer.indexOfAny(sentenceDelimiters)
            if (cutIdx >= 0) {
                // 包含分隔符在内作为一个完整句子
                val sentence = streamBuffer.substring(0, cutIdx + 1).trim()
                streamBuffer.delete(0, cutIdx + 1)
                if (sentence.isNotEmpty()) {
                    ttsManager.speakChunk(sentence, isFirst = !streamFirstSent, sessionId = sid)
                    streamFirstSent = true
                }
            } else if (force) {
                // 强制 flush：没有句子结束符也把剩余全送出去
                val remaining = streamBuffer.toString().trim()
                streamBuffer.setLength(0)
                if (remaining.isNotEmpty()) {
                    ttsManager.speakChunk(remaining, isFirst = !streamFirstSent, sessionId = sid)
                    streamFirstSent = true
                }
                break
            } else if (streamBuffer.length >= minFlushLen) {
                // 没有标点但已经太长了，强制 flush 防止延迟
                val remaining = streamBuffer.toString().trim()
                streamBuffer.setLength(0)
                if (remaining.isNotEmpty()) {
                    ttsManager.speakChunk(remaining, isFirst = !streamFirstSent, sessionId = sid)
                    streamFirstSent = true
                }
                break
            } else {
                // 不够长也没有句子结束，继续等
                break
            }
        }
    }

    /**
     * 启动打断专用的静默ASR监听
     * 不触发表情变化，只在后台识别用户语音
     *
     * 防误触三重保障：
     * 1. 自适应保护期：根据麦克风实时音量动态调整保护期长度
     *    - 回声强（音量大）→ 保护期自动延长
     *    - 回声弱（音量小）→ 保护期很快结束
     *    - 范围：bargeInGuardMinMs ~ bargeInGuardMaxMs
     * 2. VAD高阈值：barge-in会话使用 0.65 的高VAD阈值，降低对回声的灵敏度
     * 3. 语音持续确认（bargeInConfirmMs）：检测到speech_started后再确认150ms，
     *    语音仍在持续才真正打断，避免短促爆音/点击声/瞬时回声误触发
     */
    private fun startBargeInListen() {
        try {
            // 重置状态
            isSpeechOngoing = false
            isBargeInTriggering = false
            bargeInGuardEndTime = 0L
            // 只移除 barge-in 确认任务，不影响 mainHandler 上的其他回调
            bargeInConfirmRunnable?.let { mainHandler.removeCallbacks(it) }
            bargeInConfirmRunnable = null

            // barge-in 专用会话ID（负数，与正常对话 sessionId 区分）
            val bargeInSessionId = -1L

            asrManager.startListening(bargeInSessionId, object : AsrManager.AsrResultCallback {
                /**
                 * 麦克风开始录音 → 初始化自适应保护期
                 * 保护期从麦克风开始录音算起，初始值 = 最小保护期
                 * 之后根据回声强度动态延长
                 */
                override fun onRecordingStarted(sessionId: Long) {
                    ttsStartTime = asrManager.recordingStartTime
                    // 初始化自适应保护期：从最小保护期开始
                    bargeInGuardEndTime = ttsStartTime + bargeInGuardMinMs
                    Log.d(TAG, "Barge-in mic started, adaptive guard: " +
                            "min=${bargeInGuardMinMs}ms, max=${bargeInGuardMaxMs}ms, " +
                            "threshold=$bargeInEchoThreshold, " +
                            "VAD threshold = 0.65 (barge-in mode)")
                }

                /**
                 * 实时音量回调 → 动态调整保护期
                 * 回声强（音量超过阈值）→ 延长保护期
                 * 回声弱 → 不延长，保护期到了就结束
                 */
                override fun onAudioVolume(rms: Int, sessionId: Long) {
                    // 保护期已经结束了就不用调整了
                    val now = System.currentTimeMillis()
                    if (now >= bargeInGuardEndTime) return
                    if (bargeInGuardEndTime == 0L) return  // 还没初始化

                    if (rms > bargeInEchoThreshold) {
                        // 回声强，延长保护期
                        val maxEndTime = ttsStartTime + bargeInGuardMaxMs
                        val newEndTime = (bargeInGuardEndTime + bargeInGuardExtendMs).coerceAtMost(maxEndTime)
                        if (newEndTime > bargeInGuardEndTime) {
                            bargeInGuardEndTime = newEndTime
                            Log.v(TAG, "Barge-in guard extended: rms=$rms > $bargeInEchoThreshold, " +
                                    "guard now ${bargeInGuardEndTime - ttsStartTime}ms " +
                                    "(max ${bargeInGuardMaxMs}ms)")
                        }
                    }
                }

                /**
                 * 检测到语音开始 → 进入"持续确认"状态
                 * 不立即打断，等确认语音持续足够长时间后再打断，
                 * 避免短促的回声/爆音误触发
                 */
                override fun onSpeechStarted(sessionId: Long) {
                    // P3 句间倾听窗：第一道门控——播放期间/回声尾巴期间完全忽略，不做能量判断
                    if (!shouldListen()) {
                        Log.d(TAG, "Barge-in: speech_started but shouldListen=false (speaking/echo tail), ignored")
                        return
                    }
                    // 边界保护：如果录音还没正式开始（ttsStartTime仍为0），
                    // 说明时序异常，保护期尚未生效，直接忽略
                    if (ttsStartTime == 0L) {
                        Log.w(TAG, "Barge-in: speech_started but ttsStartTime=0, ignored (boundary guard)")
                        return
                    }

                    val now = System.currentTimeMillis()
                    val elapsed = now - ttsStartTime
                    val remaining = bargeInGuardEndTime - now

                    // 第一重保障：自适应保护期内完全忽略，防TTS回声
                    if (now < bargeInGuardEndTime) {
                        Log.d(TAG, "Barge-in: speech_started within adaptive guard period " +
                                "(elapsed=${elapsed}ms, remaining=${remaining}ms), ignored")
                        return
                    }

                    Log.d(TAG, "Barge-in: speech detected at ${elapsed}ms (guard ended ${-remaining}ms ago), " +
                            "entering confirm phase (${bargeInConfirmMs}ms)")
                    isSpeechOngoing = true

                    // 第三重保障：语音持续确认
                    // 延迟 bargeInConfirmMs 后再检查，如果语音仍在持续才真正打断
                    // 使用主线程Handler，避免协程异常问题
                    bargeInConfirmRunnable?.let { mainHandler.removeCallbacks(it) }
                    val confirmRunnable = Runnable {
                        if (isSpeechOngoing && !isBargeInTriggering) {
                            Log.d(TAG, "Barge-in: confirmed speech ongoing after ${bargeInConfirmMs}ms, triggering barge-in")
                            triggerBargeIn("")
                        } else {
                            Log.d(TAG, "Barge-in: speech stopped within confirm window, likely echo/noise, ignored")
                        }
                        bargeInConfirmRunnable = null
                    }
                    bargeInConfirmRunnable = confirmRunnable
                    mainHandler.postDelayed(confirmRunnable, bargeInConfirmMs)
                }

                /**
                 * 检测到语音结束 → 取消确认任务
                 * 如果确认任务还没执行，说明语音持续时间太短，是回声/噪声的可能性大
                 */
                override fun onSpeechStopped(sessionId: Long) {
                    Log.d(TAG, "Barge-in: speech stopped")
                    isSpeechOngoing = false
                    bargeInConfirmRunnable?.let {
                        mainHandler.removeCallbacks(it)
                        Log.d(TAG, "Barge-in: confirm runnable removed due to speech_stop")
                    }
                    bargeInConfirmRunnable = null
                }

                override fun onResult(text: String, sessionId: Long) {
                    // P3 句间倾听窗：第一道门控
                    if (!shouldListen()) return
                    // 边界保护：录音未开始时忽略
                    if (ttsStartTime == 0L) return

                    // 完整识别结果作为备用：如果onSpeechStarted没触发到（极端情况），这里兜底
                    val now = System.currentTimeMillis()
                    if (now < bargeInGuardEndTime) return

                    if (text.isNotBlank() && text.length >= 2) {
                        val elapsed = now - ttsStartTime
                        Log.d(TAG, "Barge-in fallback: got full result (${elapsed}ms): $text")
                        triggerBargeIn(text)
                    }
                }

                override fun onPartialResult(text: String, sessionId: Long) {
                    // barge-in 模式忽略部分结果
                }

                override fun onError(errorMsg: String, sessionId: Long) {
                    // 打断监听出错静默处理，不影响TTS播放
                    Log.w(TAG, "Barge-in listen error: $errorMsg")
                    isSpeechOngoing = false
                    bargeInConfirmRunnable?.let { mainHandler.removeCallbacks(it) }
                    bargeInConfirmRunnable = null
                }
            }, triggerExpression = false, bargeIn = true) // 打断监听模式：静默 + 标记为barge-in（使用高VAD阈值）
        } catch (e: Exception) {
            Log.e(TAG, "Start barge-in listen failed", e)
        }
    }

    /**
     * 执行打断操作
     * 封装统一的打断逻辑，确保 onSpeechStarted 确认路径和 onResult 兜底路径行为一致
     *
     * 关键顺序：
     * 1. 设 suppress 标记，让后续 ttsManager.stop() 触发的 onDone 不走 speakFinishListener
     * 2. 停止 barge-in 监听
     * 3. 停止 TTS
     * 4. 通知上层（上层负责启动新 ASR）
     */
    private fun triggerBargeIn(text: String) {
        // 防止重入
        if (isBargeInTriggering) {
            Log.w(TAG, "Barge-in already triggering, skip duplicate call")
            return
        }
        isBargeInTriggering = true
        suppressSpeakFinish = true  // 抑制 stop() 引发的 speakFinishListener

        Log.d(TAG, "Barge-in triggered! Stopping barge-in listen first, then TTS")

        // 第一步：先停止barge-in监听，清除所有回调
        stopBargeInListenInternal()

        // 第二步：停止TTS播报（切到主线程，TextToSpeech.speak/stop 要求主线程）
        mainHandler.post {
            ttsManager.stop()

            // 第三步：通知上层发生了打断
            try {
                bargeInListener?.onBargeIn(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error in bargeInListener callback", e)
            } finally {
                isBargeInTriggering = false
                suppressSpeakFinish = false
            }
        }
    }

    /**
     * 停止打断监听（对外安全版本）
     * 仅停止标记为 barge-in 的会话，不影响正在进行的正常识别
     */
    private fun stopBargeInListen() {
        stopBargeInListenInternal()
    }

    /**
     * 内部停止barge-in监听的实现
     * 清理所有状态，取消延迟任务
     */
    private fun stopBargeInListenInternal() {
        try {
            // 取消语音持续确认任务
            bargeInConfirmRunnable?.let { mainHandler.removeCallbacks(it) }
            bargeInConfirmRunnable = null
            isSpeechOngoing = false
            // 重置保护期时间戳
            ttsStartTime = 0L
            bargeInGuardEndTime = 0L
            // 停止ASR会话
            asrManager.stopBargeInSession()
        } catch (e: Exception) {
            // 忽略停止异常
            Log.w(TAG, "Stop barge-in listen exception: ${e.message}")
        }
    }

    /**
     * 停止TTS播报（静默，不触发 speakFinishListener）
     *
     * 用于打断/错误恢复等场景：只停TTS，不自动启动下一轮ASR。
     */
    fun stopSpeaking() {
        stopBargeInListen()
        suppressSpeakFinish = true
        kwsManager.setMuted(false)  // 停止TTS时解除KWS静音
        ttsManager.stop()
    }

    /**
     * 是否正在播报中
     */
    fun isSpeaking(): Boolean {
        return ttsManager.isSpeaking()
    }

    /**
     * 切换云端TTS音色
     * 仅对云端TTS（CosyVoice）生效，系统TTS不受影响
     *
     * @param voiceId 音色ID
     */
    fun setTtsVoice(voiceId: String) {
        try {
            ttsManager.setVoice(voiceId)
            Log.d(TAG, "TTS voice set to: $voiceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set TTS voice", e)
        }
    }

    /**
     * 获取当前 TTS 音色 ID
     */
    fun getCurrentVoiceId(): String {
        return try {
            ttsManager.getCurrentVoiceId()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current voice", e)
            ""
        }
    }

    /**
     * 根据渲染风格切换 TTS 音色
     * 男款 → 低沉稳重，女款 → 清亮灵动
     * 与表情风格联动，保持视觉听觉统一
     */
    fun applyVoiceStyle(style: ExpressionStyle) {
        try {
            ttsManager.applyVoiceStyle(style)
            Log.d(TAG, "Voice style synced to: $style")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply voice style", e)
        }
    }

    /**
     * 切换到歌声模式音色（高音调 + 慢语速）
     * 后续接入云端情感TTS后，此方法将调用云端歌声合成接口
     */
    fun applySingingVoice() {
        try {
            ttsManager.applySingingVoice()
            Log.d(TAG, "Singing voice activated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply singing voice", e)
        }
    }

    /**
     * 恢复默认音色（唱歌结束后调用）
     * @param style 当前渲染风格，默认女款
     */
    fun restoreDefaultVoice(style: ExpressionStyle = ExpressionStyle.FEMALE) {
        try {
            ttsManager.restoreDefaultVoice(style)
            Log.d(TAG, "Voice restored to: $style")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore voice", e)
        }
    }

    /**
     * 释放音频资源
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        asrManager.release()
        ttsManager.release()
        kwsManager.release()
        Log.d(TAG, "AudioManager released")
    }

}
