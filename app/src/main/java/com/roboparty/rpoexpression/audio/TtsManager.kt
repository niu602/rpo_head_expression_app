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
                if (utteranceId == UTTERANCE_ID) {
                    Log.d(TAG, "TTS start speaking")
                    // 自动联动表情：切换到说话状态
                    ModuleStateManager.onTtsStartSpeaking()
                    stateCallback?.onStart()
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    Log.d(TAG, "TTS finish speaking")
                    // 自动联动表情：恢复之前表情
                    ModuleStateManager.onTtsFinishSpeaking()
                    stateCallback?.onDone()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == UTTERANCE_ID) {
                    Log.e(TAG, "TTS error, code: $errorCode")
                    // 自动联动表情：错误表情
                    ModuleStateManager.onTtsError()
                    stateCallback?.onError(errorCode)
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

    /**
     * 执行播报
     */
    private fun doSpeak(text: String) {
        textToSpeech?.stop()
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    /**
     * 停止播报
     */
    fun stop() {
        textToSpeech?.stop()
        Log.d(TAG, "TTS stopped")
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
