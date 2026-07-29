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
import kotlinx.coroutines.*

/**
 * 音频管理器
 * 统一管理ASR语音识别和TTS语音播报，对外提供统一接口
 */
class AudioManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AudioManager"

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

    // ========== 打断（Barge-in）功能 ==========
    var bargeInEnabled = true          // 是否启用语音打断：TTS播报时用户说话自动停止播报
    var bargeInGuardMs = 2500L         // TTS开头保护时间，防回声误触（毫秒），从麦克风实际开始录音算起
    var ttsStartTime = 0L              // 当前TTS播报实际开始时间戳（在onRecordingStarted回调中设置）

    // 语音持续确认：检测到speech_started后，再确认语音持续了这么久才真正触发打断
    // 避免短促的回声/爆音/点击声被误判为用户说话
    private val bargeInConfirmMs = 200L
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
     * 初始化音频模块（ASR + TTS 并行启动）
     *
     * ASR和TTS互不依赖，使用协程async同时初始化，
     * 耗时从 t_asr + t_tts 降低到 max(t_asr, t_tts)。
     * - ASR：OkHttpClient构建，跑在IO线程
     * - TTS：TextToSpeech绑定，本身已post到主线程
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
            asrDeferred.await()
            ttsDeferred.await()
            Log.d(TAG, "AudioManager initialized (ASR + TTS parallel)")
        }
    }

    /**
     * 开始ASR语音识别
     * @param callback 识别结果回调
     * @param triggerExpression 是否触发识别中表情（唤醒监听传false，正式识别传true）
     */
    fun startListening(
        callback: AsrManager.AsrResultCallback,
        triggerExpression: Boolean = true
    ) {
        // 如果正在播报，先停止
        if (ttsManager.isSpeaking()) {
            ttsManager.stop()
        }
        asrManager.startListening(callback, triggerExpression)
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
     * 开始TTS播报文本
     * @param text 要播报的文本
     * @param callback 播报状态回调
     */
    fun startSpeaking(text: String, callback: TtsManager.TtsStateCallback? = null) {
        // 重置 suppress 标记，新的一轮TTS正常走 speakFinish
        suppressSpeakFinish = false
        // TTS播报前先停止所有ASR会话，确保扬声器播放时只有一个音频消费者
        // 避免TTS声音被麦克风录入导致误触发
        if (asrManager.isListening()) {
            asrManager.stopListening()
        }

        // 包装回调：TTS结束时同时触发全局 speakFinishListener
        val wrappedCallback = object : TtsManager.TtsStateCallback {
            override fun onStart() {
                Log.d(TAG, "TTS actual start, barge-in guard will begin when mic starts")
                callback?.onStart()
                // 打断模式：TTS开始后启动后台静默ASR监听，用于检测用户打断
                if (bargeInEnabled && !asrManager.isListening()) {
                    startBargeInListen()
                }
            }
            override fun onDone() {
                stopBargeInListen()
                callback?.onDone()
                // 打断/取消导致的stop不走speakFinish，避免双重启动ASR
                if (!suppressSpeakFinish) {
                    speakFinishListener?.onSpeakFinsh()
                } else {
                    Log.d(TAG, "speakFinish suppressed (barge-in/cancel)")
                }
            }
            override fun onError(errorCode: Int) {
                stopBargeInListen()
                callback?.onError(errorCode)
                if (!suppressSpeakFinish) {
                    speakFinishListener?.onSpeakFinsh()
                }
            }
        }

        ttsManager.speak(text, wrappedCallback)
    }

    // ========== 流式播报（LLM边生成边播）==========

    private var streamBuffer = StringBuilder()
    private var streamFirstSent = false
    // 断句分隔符：句号、感叹号、问号、换行、分号、中文逗号也可做软断句
    private val sentenceDelimiters = charArrayOf('。', '！', '？', '\n', '；', '!', '?', '.')
    // 最小缓冲长度：不足一个句子时先攒着，但超过这个长度强制发（防止标点缺失时卡太久）
    private val minFlushLen = 40

    /**
     * 开始流式播报会话
     *
     * 在 LLM 流式请求开始前调用，重置内部状态。
     * 之后每次收到 LLM delta 调 [feedStreamChunk]。
     */
    fun startStreaming(callback: TtsManager.TtsStateCallback? = null) {
        // 重置 suppress 标记，新的一轮流式播报正常走 speakFinish
        suppressSpeakFinish = false
        // TTS播报前先停止所有ASR会话
        if (asrManager.isListening()) {
            asrManager.stopListening()
        }
        streamBuffer.setLength(0)
        streamFirstSent = false

        // 包装回调
        val wrappedCallback = object : TtsManager.TtsStateCallback {
            override fun onStart() {
                Log.d(TAG, "TTS stream start, barge-in guard will begin when mic starts")
                callback?.onStart()
                if (bargeInEnabled && !asrManager.isListening()) {
                    startBargeInListen()
                }
            }
            override fun onDone() {
                stopBargeInListen()
                callback?.onDone()
                if (!suppressSpeakFinish) {
                    speakFinishListener?.onSpeakFinsh()
                } else {
                    Log.d(TAG, "speakFinish suppressed (barge-in/cancel)")
                }
            }
            override fun onError(errorCode: Int) {
                stopBargeInListen()
                callback?.onError(errorCode)
                if (!suppressSpeakFinish) {
                    speakFinishListener?.onSpeakFinsh()
                }
            }
        }

        // 告诉 TTSManager 用流式模式的回调
        ttsManager.setStateCallback(wrappedCallback)
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
        while (streamBuffer.isNotEmpty()) {
            // 找最早的句子结束符
            val cutIdx = streamBuffer.indexOfAny(sentenceDelimiters)
            if (cutIdx >= 0) {
                // 包含分隔符在内作为一个完整句子
                val sentence = streamBuffer.substring(0, cutIdx + 1).trim()
                streamBuffer.delete(0, cutIdx + 1)
                if (sentence.isNotEmpty()) {
                    ttsManager.speakChunk(sentence, isFirst = !streamFirstSent)
                    streamFirstSent = true
                }
            } else if (force) {
                // 强制 flush：没有句子结束符也把剩余全送出去
                val remaining = streamBuffer.toString().trim()
                streamBuffer.setLength(0)
                if (remaining.isNotEmpty()) {
                    ttsManager.speakChunk(remaining, isFirst = !streamFirstSent)
                    streamFirstSent = true
                }
                break
            } else if (streamBuffer.length >= minFlushLen) {
                // 没有标点但已经太长了，强制 flush 防止延迟
                val remaining = streamBuffer.toString().trim()
                streamBuffer.setLength(0)
                if (remaining.isNotEmpty()) {
                    ttsManager.speakChunk(remaining, isFirst = !streamFirstSent)
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
     * 1. 保护期（bargeInGuardMs）：麦克风开始录音后的前 N 毫秒内完全忽略语音，防TTS开头强回声
     * 2. VAD高阈值：barge-in会话使用 0.65 的高VAD阈值，降低对回声的灵敏度
     * 3. 语音持续确认（bargeInConfirmMs）：检测到speech_started后再确认200ms，
     *    语音仍在持续才真正打断，避免短促爆音/点击声/瞬时回声误触发
     */
    private fun startBargeInListen() {
        try {
            // 重置状态
            isSpeechOngoing = false
            isBargeInTriggering = false
            // 只移除 barge-in 确认任务，不影响 mainHandler 上的其他回调
            bargeInConfirmRunnable?.let { mainHandler.removeCallbacks(it) }
            bargeInConfirmRunnable = null

            asrManager.startListening(object : AsrManager.AsrResultCallback {
                /**
                 * 麦克风开始录音 → 记录准确的保护期起点
                 * WebSocket连接+session配置可能耗时数百毫秒，
                 * 从TTS onStart算起会导致保护期提前过期
                 */
                override fun onRecordingStarted() {
                    ttsStartTime = asrManager.recordingStartTime
                    Log.d(TAG, "Barge-in mic started, guard period = ${bargeInGuardMs}ms, " +
                            "VAD threshold = 0.65 (barge-in mode)")
                }

                /**
                 * 检测到语音开始 → 进入"持续确认"状态
                 * 不立即打断，等确认语音持续足够长时间后再打断，
                 * 避免短促的回声/爆音误触发
                 */
                override fun onSpeechStarted() {
                    // 边界保护：如果录音还没正式开始（ttsStartTime仍为0），
                    // 说明时序异常，保护期尚未生效，直接忽略
                    if (ttsStartTime == 0L) {
                        Log.w(TAG, "Barge-in: speech_started but ttsStartTime=0, ignored (boundary guard)")
                        return
                    }

                    val elapsed = System.currentTimeMillis() - ttsStartTime
                    // 第一重保障：保护期内完全忽略，防TTS开头强回声
                    if (elapsed < bargeInGuardMs) {
                        Log.d(TAG, "Barge-in: speech_started within guard period " +
                                "(${elapsed}ms < ${bargeInGuardMs}ms), ignored")
                        return
                    }

                    Log.d(TAG, "Barge-in: speech detected at ${elapsed}ms, entering confirm phase (${bargeInConfirmMs}ms)")
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
                override fun onSpeechStopped() {
                    Log.d(TAG, "Barge-in: speech stopped")
                    isSpeechOngoing = false
                    bargeInConfirmRunnable?.let {
                        mainHandler.removeCallbacks(it)
                        Log.d(TAG, "Barge-in: confirm runnable removed due to speech_stop")
                    }
                    bargeInConfirmRunnable = null
                }

                override fun onResult(text: String) {
                    // 边界保护：录音未开始时忽略
                    if (ttsStartTime == 0L) return

                    // 完整识别结果作为备用：如果onSpeechStarted没触发到（极端情况），这里兜底
                    val elapsed = System.currentTimeMillis() - ttsStartTime
                    if (elapsed < bargeInGuardMs) return

                    if (text.isNotBlank() && text.length >= 2) {
                        Log.d(TAG, "Barge-in fallback: got full result (${elapsed}ms): $text")
                        triggerBargeIn(text)
                    }
                }

                override fun onError(errorMsg: String) {
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
        ttsManager.stop()
    }

    /**
     * 是否正在播报中
     */
    fun isSpeaking(): Boolean {
        return ttsManager.isSpeaking()
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
     * 释放音频资源
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        asrManager.release()
        ttsManager.release()
        Log.d(TAG, "AudioManager released")
    }

}
