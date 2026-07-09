/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.util.Log

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

    private val asrManager: AsrManager by lazy { AsrManager.getInstance(context) }
    private val ttsManager: TtsManager by lazy { TtsManager.getInstance(context) }

    /**
     * 初始化音频模块（ASR + TTS）
     */
    fun init() {
        asrManager.init()
        ttsManager.init()
        Log.d(TAG, "AudioManager initialized")
    }

    /**
     * 开始ASR语音识别
     * @param callback 识别结果回调
     */
    fun startListening(callback: AsrManager.AsrResultCallback) {
        // 如果正在播报，先停止
        if (ttsManager.isSpeaking()) {
            ttsManager.stop()
        }
        asrManager.startListening(callback)
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
        // 如果正在识别，先停止
        if (asrManager.isListening()) {
            asrManager.stopListening()
        }
        ttsManager.speak(text, callback)
    }

    /**
     * 停止TTS播报
     */
    fun stopSpeaking() {
        ttsManager.stop()
    }

    /**
     * 是否正在播报中
     */
    fun isSpeaking(): Boolean {
        return ttsManager.isSpeaking()
    }

    /**
     * 释放音频资源
     */
    fun release() {
        asrManager.release()
        ttsManager.release()
        Log.d(TAG, "AudioManager released")
    }
}
