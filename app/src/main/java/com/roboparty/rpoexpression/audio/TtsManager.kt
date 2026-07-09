/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
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
     */
    fun init(callback: ((Boolean) -> Unit)? = null) {
        textToSpeech = TextToSpeech(context) { status ->
            isInitSuccess = if (status == TextToSpeech.SUCCESS) {
                // 设置语言为中文
                val result = textToSpeech?.setLanguage(Locale.CHINA)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Chinese language not supported")
                    false
                } else {
                    // 设置语速
                    textToSpeech?.setSpeechRate(1.0f)
                    // 设置音调
                    textToSpeech?.setPitch(1.0f)
                    // 设置播放进度监听
                    setupUtteranceListener()
                    Log.d(TAG, "TTS init success")
                    true
                }
            } else {
                Log.e(TAG, "TTS init failed")
                false
            }
            callback?.invoke(isInitSuccess)
        }
    }

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
            Log.e(TAG, "TTS not initialized, init first")
            init { success ->
                if (success) {
                    doSpeak(text)
                } else {
                    callback?.onError(TextToSpeech.ERROR)
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
