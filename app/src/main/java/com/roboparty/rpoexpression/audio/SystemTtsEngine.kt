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
 * 系统 TTS 引擎（降级方案）
 *
 * 封装 Android 系统 TextToSpeech，作为 CosyVoice 不可用时的降级引擎。
 * 将原 TtsManager 中的系统 TTS 逻辑完整提取，实现 TtsEngine 接口。
 */
class SystemTtsEngine(private val context: Context) : TtsEngine {

    companion object {
        private const val TAG = "SystemTts"
        private const val UTTERANCE_ID = "rpo_tts"
    }

    private var textToSpeech: TextToSpeech? = null
    private var isInitSuccess = false

    // 当前音色配置（默认女款）
    private var currentProfile = TtsManager.VOICE_FEMALE

    private var stateCallback: TtsManager.TtsStateCallback? = null

    // 流式播报标记
    @Volatile private var firstChunkId: String? = null
    @Volatile private var lastChunkId: String? = null
    @Volatile private var isStreamingMode = false
    private var chunkIdCounter = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun init(callback: ((Boolean) -> Unit)?) {
        mainHandler.post {
            initWithRetry(0, callback)
        }
    }

    private fun initWithRetry(retryCount: Int, callback: ((Boolean) -> Unit)?) {
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
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
                    if (retryCount < 1) {
                        mainHandler.postDelayed({
                            initWithRetry(retryCount + 1, callback)
                        }, 500)
                    } else {
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

    private fun setupChineseLanguage(): Boolean {
        val result = textToSpeech?.setLanguage(Locale.CHINA)
        return when (result) {
            TextToSpeech.LANG_MISSING_DATA -> {
                Log.e(TAG, "Chinese language data missing")
                false
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.e(TAG, "Chinese language not supported")
                false
            }
            else -> {
                textToSpeech?.setSpeechRate(currentProfile.speechRate)
                textToSpeech?.setPitch(currentProfile.pitch)
                true
            }
        }
    }

    private fun tryOtherEngines(callback: ((Boolean) -> Unit)?) {
        try {
            val engines = textToSpeech?.engines
            if (engines.isNullOrEmpty()) {
                Log.e(TAG, "No other TTS engines available")
                isInitSuccess = false
                callback?.invoke(false)
                return
            }
            textToSpeech?.shutdown()
            val priorityEngines = listOf(
                "com.xiaomi.mibrain.speech",
                "com.baidu.duersdk.opensdk",
                "com.iflytek.speechcloud",
                "com.google.android.tts",
                "com.samsung.SMT"
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

    // ==================== 音色控制 ====================

    fun applyVoiceProfile(profile: TtsManager.VoiceProfile) {
        currentProfile = profile
        if (isInitSuccess) {
            textToSpeech?.setPitch(profile.pitch)
            textToSpeech?.setSpeechRate(profile.speechRate)
        }
    }

    fun applyVoiceStyle(style: ExpressionStyle) {
        val profile = when (style) {
            ExpressionStyle.MALE -> TtsManager.VOICE_MALE
            ExpressionStyle.FEMALE -> TtsManager.VOICE_FEMALE
        }
        applyVoiceProfile(profile)
    }

    fun applySingingVoice() {
        applyVoiceProfile(TtsManager.VOICE_SINGING)
    }

    fun restoreDefaultVoice(style: ExpressionStyle = ExpressionStyle.FEMALE) {
        applyVoiceStyle(style)
    }

    // ==================== TtsEngine 接口 ====================

    override fun speak(text: String, callback: TtsManager.TtsStateCallback?) {
        stateCallback = callback
        if (!isInitSuccess) {
            Log.e(TAG, "TTS not available, try re-init")
            init { success ->
                if (success) {
                    doSpeak(text)
                } else {
                    Log.e(TAG, "TTS init failed, fallback to silent mode")
                    ModuleStateManager.onTtsStartSpeaking()
                    val duration = (text.length * 150L).coerceIn(500L, 3000L)
                    mainHandler.postDelayed({
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

    private fun doSpeak(text: String) {
        textToSpeech?.stop()
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    override fun setStateCallback(callback: TtsManager.TtsStateCallback?) {
        stateCallback = callback
    }

    override fun speakChunk(text: String, isFirst: Boolean) {
        if (!isInitSuccess || text.isBlank()) return
        val utteranceId = "rpo_chunk_${chunkIdCounter++}"
        isStreamingMode = true
        lastChunkId = utteranceId
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        if (isFirst) {
            firstChunkId = utteranceId
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        }
    }

    override fun endStream() {
        // 仅标记输入结束，不清理播放状态
    }

    override fun stop() {
        val wasSpeaking = isSpeaking()
        Log.i(TAG, "[DIAG] stop(): wasSpeaking=$wasSpeaking, firstChunkId=$firstChunkId, lastChunkId=$lastChunkId")
        textToSpeech?.stop()
        chunkIdCounter = 0
        // 关键修复：不立即清空 firstChunkId/lastChunkId，
        // 因为 UtteranceProgressListener 可能还在队列中等待回调
        // 如果清空了，onDone 将无法匹配，导致回调永远不触发
        firstChunkId = null
        lastChunkId = null
        isStreamingMode = false
        if (wasSpeaking) {
            Log.d(TAG, "TTS stopped manually, trigger onDone")
            ModuleStateManager.onTtsFinishSpeaking()
            stateCallback?.onDone()
        }
    }

    override fun isSpeaking(): Boolean = textToSpeech?.isSpeaking ?: false

    override fun isAvailable(): Boolean = isInitSuccess

    override fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitSuccess = false
        stateCallback = null
    }

    // ==================== UtteranceProgressListener ====================

    private fun setupUtteranceListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID || utteranceId == firstChunkId) {
                    Log.d(TAG, "TTS start (utteranceId=$utteranceId)")
                    ModuleStateManager.onTtsStartSpeaking()
                    stateCallback?.onStart()
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID || utteranceId == lastChunkId) {
                    Log.d(TAG, "TTS done (utteranceId=$utteranceId)")
                    ModuleStateManager.onTtsFinishSpeaking()
                    stateCallback?.onDone()
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
                    Log.e(TAG, "TTS error: code=$errorCode (utteranceId=$utteranceId)")
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
}
