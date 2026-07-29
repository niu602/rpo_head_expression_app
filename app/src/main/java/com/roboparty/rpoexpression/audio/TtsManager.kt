/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.integration.ModuleStateManager
import java.util.Locale

/**
 * 系统原生TTS管理器
 * 封装Android系统TextToSpeech能力，自动联动表情状态
 */
class TtsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID = "rpo_tts"

        // 男款音色：更低沉稳重
        val VOICE_MALE = VoiceProfile(pitch = 0.65f, speechRate = 0.90f)
        // 女款音色：清亮灵动
        val VOICE_FEMALE = VoiceProfile(pitch = 1.15f, speechRate = 1.05f)

        @Volatile
        private var instance: TtsManager? = null

        fun getInstance(context: Context): TtsManager {
            return instance ?: synchronized(this) {
                instance ?: TtsManager(context.applicationContext).also { instance = it }
            }
        }
    }

        private var textToSpeech: TextToSpeech? = null
    private var isInitSuccess = false

    // 当前音色配置（默认女款）
    private var currentVoicePitch = VOICE_FEMALE.pitch
    private var currentVoiceRate = VOICE_FEMALE.speechRate

    /**
     * 音色配置
     * 通过音调(pitch)和语速(speechRate)组合出不同性别听感
     */
    data class VoiceProfile(
        val pitch: Float,       // 音调：<1 低沉，>1 高亢
        val speechRate: Float   // 语速：<1 慢，>1 快
    )

    /**
     * TTS状态回调
     */
    interface TtsStateCallback {
        fun onStart() {}
        fun onDone() {}
        fun onError(errorCode: Int) {}
    }

    private var stateCallback: TtsStateCallback? = null

    /**
     * 设置状态回调（供 AudioManager 流式模式外部设置）
     */
    fun setStateCallback(callback: TtsStateCallback?) {
        stateCallback = callback
    }

    // 流式播报：标记当前 chunk id 是否是本轮第一个（用于 onUtterance 回调判断）
    @Volatile private var firstChunkId: String? = null
    @Volatile private var lastChunkId: String? = null
    @Volatile private var isStreamingMode = false

    /**
     * 初始化TTS引擎
     * 优先使用默认引擎，失败时自动重试，再失败则尝试其他已安装的引擎
     * 必须在主线程调用，否则部分机型初始化失败
     */
    fun init(callback: ((Boolean) -> Unit)? = null) {
        // 切到主线程初始化（MIUI等ROM要求TTS服务绑定必须在主线程）
        Handler(Looper.getMainLooper()).post {
            initWithRetry(0, callback)
        }
    }

    /**
     * 带重试的初始化
     */
    private fun initWithRetry(retryCount: Int, callback: ((Boolean) -> Unit)?) {
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // 初始化成功，打印引擎信息
                    logEngineInfo()
                    val result = setupChineseLanguage()
                    if (result) {
                        isInitSuccess = true
                        setupUtteranceListener()
                        Log.d(TAG, "TTS init success with default engine")
                        callback?.invoke(true)
                    } else {
                        Log.w(TAG, "Default engine doesn't support Chinese, try other engines")
                        tryOtherEngines(callback)
                    }
                } else {
                    Log.e(TAG, "TTS init failed with default engine, status=$status, retry=$retryCount")
                    // 失败后重试一次（延迟500ms，可能是TTS服务还没起来）
                    if (retryCount < 1) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            initWithRetry(retryCount + 1, callback)
                        }, 500)
                    } else {
                        // 重试也失败，尝试其他引擎
                        tryOtherEngines(callback)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "TTS init SecurityException: ${e.message}")
            tryOtherEngines(callback)
        } catch (e: Exception) {
            Log.e(TAG, "TTS init exception: ${e.message}", e)
            tryOtherEngines(callback)
        }
    }

    /**
     * 打印当前引擎信息
     */
    private fun logEngineInfo() {
        try {
            val defaultEngine = textToSpeech?.defaultEngine
            val engines = textToSpeech?.engines
            Log.d(TAG, "===== TTS Engines =====")
            Log.d(TAG, "Default: $defaultEngine")
            engines?.forEach { engine ->
                Log.d(TAG, "  - ${engine.name} (${engine.label})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query engine info", e)
        }
    }

    /**
     * 设置中文语言
     * @return true表示设置成功，false表示不支持中文
     */
    private fun setupChineseLanguage(): Boolean {
        val result = textToSpeech?.setLanguage(Locale.CHINA)
        return when (result) {
            TextToSpeech.LANG_MISSING_DATA -> {
                Log.e(TAG, "Chinese language data missing, need download voice data")
                false
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.e(TAG, "Chinese language not supported by this engine")
                false
            }
            else -> {
                textToSpeech?.setSpeechRate(currentVoiceRate)
                textToSpeech?.setPitch(currentVoicePitch)
                true
            }
        }
    }

    /**
     * 遍历尝试其他已安装的TTS引擎
     */
    private fun tryOtherEngines(callback: ((Boolean) -> Unit)?) {
        try {
            val engines = textToSpeech?.engines
            if (engines.isNullOrEmpty()) {
                Log.e(TAG, "No other TTS engines available")
                isInitSuccess = false
                callback?.invoke(false)
                return
            }

            // 关掉当前失败的引擎
            textToSpeech?.shutdown()

            // 优先尝试小米/百度/讯飞等国产引擎（通常支持中文）
            val priorityEngines = listOf(
                "com.xiaomi.mibrain.speech",     // 小米小爱引擎
                "com.baidu.duersdk.opensdk",     // 百度语音
                "com.iflytek.speechcloud",       // 讯飞语音
                "com.google.android.tts",        // Google TTS
                "com.samsung.SMT"                // 三星语音
            )

            val availableEngineNames = engines.map { it.name }
            val engineToTry = priorityEngines.filter { it in availableEngineNames } +
                    availableEngineNames.filter { it !in priorityEngines }

            tryNextEngine(engineToTry, 0, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Try other engines failed", e)
            isInitSuccess = false
            callback?.invoke(false)
        }
    }

    /**
     * 递归尝试下一个引擎
     */
    private fun tryNextEngine(engineList: List<String>, index: Int, callback: ((Boolean) -> Unit)?) {
        if (index >= engineList.size) {
            Log.e(TAG, "All TTS engines tried, none supports Chinese")
            isInitSuccess = false
            callback?.invoke(false)
            return
        }

        val engineName = engineList[index]
        Log.d(TAG, "Trying TTS engine: $engineName")

        try {
            textToSpeech = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS && setupChineseLanguage()) {
                    isInitSuccess = true
                    setupUtteranceListener()
                    Log.i(TAG, "Success with engine: $engineName")
                    callback?.invoke(true)
                } else {
                    Log.w(TAG, "Engine $engineName failed, try next")
                    textToSpeech?.shutdown()
                    tryNextEngine(engineList, index + 1, callback)
                }
            }, engineName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init engine: $engineName", e)
            tryNextEngine(engineList, index + 1, callback)
        }
    }

    /**
     * TTS是否可用
     */
    fun isAvailable(): Boolean = isInitSuccess

    /**
     * 根据渲染风格切换音色
     * 男款 → 低沉稳重，女款 → 清亮灵动
     * 切换即时生效，下一次 speak 时使用新音色
     *
     * @param style 目标风格
     */
    fun applyVoiceStyle(style: ExpressionStyle) {
        val profile = when (style) {
            ExpressionStyle.MALE -> VOICE_MALE
            ExpressionStyle.FEMALE -> VOICE_FEMALE
        }

        currentVoicePitch = profile.pitch
        currentVoiceRate = profile.speechRate

        // 如果 TTS 已初始化，立即应用参数
        if (isInitSuccess) {
            textToSpeech?.setPitch(profile.pitch)
            textToSpeech?.setSpeechRate(profile.speechRate)
            Log.d(TAG, "Voice style applied: $style, pitch=${profile.pitch}, rate=${profile.speechRate}")
        } else {
            Log.d(TAG, "Voice style cached: $style (will apply after TTS init)")
        }
    }

    /**
     * 获取当前音色配置
     */
    fun getCurrentVoiceProfile(): VoiceProfile = VoiceProfile(currentVoicePitch, currentVoiceRate)

    /**
     * 设置播放状态监听，自动联动表情
     */
    private fun setupUtteranceListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // 整句模式：UTTERANCE_ID；流式模式：首个 chunk id
                if (utteranceId == UTTERANCE_ID || utteranceId == firstChunkId) {
                    Log.d(TAG, "TTS start speaking (utteranceId=$utteranceId)")
                    ModuleStateManager.onTtsStartSpeaking()
                    stateCallback?.onStart()
                }
            }

            override fun onDone(utteranceId: String?) {
                // 整句模式：UTTERANCE_ID；流式模式：最后一个 chunk id
                if (utteranceId == UTTERANCE_ID || utteranceId == lastChunkId) {
                    Log.d(TAG, "TTS finish speaking (utteranceId=$utteranceId)")
                    ModuleStateManager.onTtsFinishSpeaking()
                    stateCallback?.onDone()
                    // 流式模式下最后一个chunk播完，清理标记
                    if (utteranceId != UTTERANCE_ID) {
                        isStreamingMode = false
                        firstChunkId = null
                        lastChunkId = null
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == UTTERANCE_ID || utteranceId == lastChunkId) {
                    Log.e(TAG, "TTS error, code: $errorCode (utteranceId=$utteranceId)")
                    ModuleStateManager.onTtsError()
                    stateCallback?.onError(errorCode)
                    if (utteranceId != UTTERANCE_ID) {
                        isStreamingMode = false
                        firstChunkId = null
                        lastChunkId = null
                    }
                }
            }
        })
    }

    /**
     * 开始播报文本
     * @param text 要播报的文本
     * @param callback 状态回调
     */
    fun speak(text: String, callback: TtsStateCallback? = null) {
        this.stateCallback = callback

        if (!isInitSuccess) {
            Log.e(TAG, "TTS not available, try re-init")
            init { success ->
                if (success) {
                    doSpeak(text)
                } else {
                    // TTS彻底不可用：降级处理，手动走一遍状态回调
                    // 保证上层表情链路和连续对话不被卡死
                    Log.e(TAG, "TTS init failed, fallback to silent mode")
                    ModuleStateManager.onTtsStartSpeaking()
                    // 模拟播报一小段时间后结束
                    val duration = (text.length * 150L).coerceIn(500L, 3000L)
                    Handler(Looper.getMainLooper()).postDelayed({
                        ModuleStateManager.onTtsError()
                        callback?.onError(TextToSpeech.ERROR)
                        stateCallback?.onError(TextToSpeech.ERROR)
                    }, duration)
                }
            }
            return
        }

        doSpeak(text)
    }

    private var chunkIdCounter = 0

    /**
     * 执行播报（整句模式）
     */
    private fun doSpeak(text: String) {
        textToSpeech?.stop()
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    /**
     * 流式播报：首次调用会立即开始播放，后续调用排队追加。
     *
     * 与 speak() 的区别：
     * - speak() 用 QUEUE_FLUSH：每次清空队列重新播
     * - speakChunk() 用 QUEUE_ADD：第一句用FLUSH清掉旧内容，后续ADD排队
     *
     * 用于 LLM 流式输出：收到第一句话立即播放，后面的句子边收边排队。
     *
     * @param text         一个句子或一段文本
     * @param isFirst      是否是本轮第一句（true=清队列立即播，false=追加到队列尾）
     */
    fun speakChunk(text: String, isFirst: Boolean) {
        if (!isInitSuccess) {
            Log.w(TAG, "TTS not available, skip chunk: $text")
            return
        }
        if (text.isBlank()) return

        val utteranceId = "rpo_chunk_${chunkIdCounter++}"
        isStreamingMode = true
        lastChunkId = utteranceId // 每次更新，最终指向最后一个入队的 chunk

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        if (isFirst) {
            firstChunkId = utteranceId // 记录本轮首个 chunk
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Log.d(TAG, "TTS chunk (first): $text")
        } else {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            Log.d(TAG, "TTS chunk (queued): $text")
        }
    }

    /**
     * 标记本轮流式播报的输入已结束（LLM 全部输出完毕后调用）
     * 
     * 注意：不清理 lastChunkId 和 isStreamingMode，
     * 因为最后一个 chunk 可能还在 TTS 队列中等待播放。
     * 等 onDone 回调触发后再在 UtteranceProgressListener 中清理。
     */
    fun endStream() {
        // 仅标记输入结束，不清理播放状态
    }

    /**
     * 停止播报并清空所有排队的句子
     *
     * 只在确实正在播报时才手动触发 onDone，避免在非播报状态下
     * （如 startSpeaking 内部预停、doSpeak 内部 stop 等）误触发回调，
     * 导致上层状态机错误推进。
     */
    fun stop() {
        val wasSpeaking = isSpeaking()
        textToSpeech?.stop()
        chunkIdCounter = 0
        firstChunkId = null
        lastChunkId = null
        isStreamingMode = false
        if (wasSpeaking) {
            Log.d(TAG, "TTS stopped manually (was speaking), trigger onDone callback")
            ModuleStateManager.onTtsFinishSpeaking()
            stateCallback?.onDone()
        } else {
            Log.d(TAG, "TTS stop() called but not speaking, skip onDone")
        }
    }

    /**
     * 是否正在播报
     */
    fun isSpeaking(): Boolean {
        return textToSpeech?.isSpeaking ?: false
    }

    /**
     * 释放TTS资源
     */
    fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitSuccess = false
        stateCallback = null
        Log.d(TAG, "TTS released")
    }
}
