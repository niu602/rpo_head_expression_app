/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.util.Constants
import com.roboparty.rpoexpression.util.PersonaRegistry
import com.roboparty.rpoexpression.util.VoiceLibrary

/**
 * TTS 管理器（统一入口）
 *
 * 根据 Constants.CLOUD_TTS_ENABLE 选择引擎：
 * - true：使用 CosyVoice 云端情感 TTS（主引擎）
 * - false：使用系统 TTS（降级引擎）
 *
 * 对外接口完全不变，AudioManager / MainActivity 无需任何改动。
 * 云端引擎连接失败时自动降级到系统 TTS。
 */
class TtsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"

        // 男款音色：更低沉稳重
        val VOICE_MALE = VoiceProfile(
            pitch = 0.65f, speechRate = 0.90f,
            cloudVoiceId = "male-deep" // 预留云端音色 ID
        )
        // 女款音色：清亮灵动
        val VOICE_FEMALE = VoiceProfile(
            pitch = 1.15f, speechRate = 1.05f,
            cloudVoiceId = "female-vivid" // 预留云端音色 ID
        )
        // 歌声音色
        val VOICE_SINGING = VoiceProfile(
            pitch = 1.4f, speechRate = 0.75f,
            cloudVoiceId = "singing-high"
        )

        @Volatile
        private var instance: TtsManager? = null

        fun getInstance(context: Context): TtsManager {
            return instance ?: synchronized(this) {
                instance ?: TtsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 音色配置
     * @param pitch 音高 (系统 TTS 使用)
     * @param speechRate 语速 (系统 TTS 使用)
     * @param cloudVoiceId 云端音色 ID (CosyVoice 使用)
     */
    data class VoiceProfile(
        val pitch: Float,
        val speechRate: Float,
        val cloudVoiceId: String = ""
    )

    /**
     * TTS状态回调
     */
    interface TtsStateCallback {
        fun onStart() {}
        fun onDone() {}
        fun onError(errorCode: Int) {}
    }

    // 当前引擎（云端或系统）
    private var engine: TtsEngine? = null
    // 系统 TTS 引擎（降级备用，始终初始化）
    private lateinit var systemEngine: SystemTtsEngine
    // 当前是否使用云端引擎
    private var useCloudTts = Constants.CLOUD_TTS_ENABLE

    /**
     * 当前音色ID（用于幂等检查）
     * 避免重复设置相同音色导致不必要的操作
     */
    @Volatile
    private var currentVoiceId: String = ""

    /**
     * 初始化 TTS 引擎
     * 云端启用时同时初始化系统 TTS 作为降级方案
     */
    fun init(callback: ((Boolean) -> Unit)? = null) {
        // 始终初始化系统 TTS 作为降级
        systemEngine = SystemTtsEngine(context)
        systemEngine.init { systemOk ->
            if (useCloudTts) {
                // 云端模式：初始化 CosyVoice 引擎
                initCloudEngine(callback, systemOk)
            } else {
                // 纯系统模式
                engine = systemEngine
                callback?.invoke(systemOk)
            }
        }
    }

    /**
     * 初始化云端 TTS 引擎
     */
    private fun initCloudEngine(callback: ((Boolean) -> Unit)?, systemOk: Boolean) {
        val cloudEngine = CosyVoiceTtsEngine(context)
        cloudEngine.init { cloudOk ->
            if (cloudOk) {
                engine = cloudEngine
                Log.i(TAG, "Cloud TTS (CosyVoice) initialized as primary engine")
                // 云端初始化成功但系统TTS也备着
                callback?.invoke(true)
            } else {
                Log.w(TAG, "Cloud TTS init failed, fallback to system TTS")
                engine = systemEngine
                useCloudTts = false
                callback?.invoke(systemOk)
            }
        }
    }

    /**
     * 根据渲染风格切换音色
     * 云端TTS：复刻音色固定，此处仅影响系统TTS降级时的参数
     */
    fun applyVoiceStyle(style: ExpressionStyle) {
        (engine as? SystemTtsEngine)?.applyVoiceStyle(style)
        if (::systemEngine.isInitialized) {
            systemEngine.applyVoiceStyle(style)
        }
    }

    /**
     * 切换到歌声模式音色
     */
    fun applySingingVoice() {
        (engine as? SystemTtsEngine)?.applySingingVoice()
        if (::systemEngine.isInitialized) {
            systemEngine.applySingingVoice()
        }
    }

    /**
     * 恢复默认音色
     */
    fun restoreDefaultVoice(style: ExpressionStyle = ExpressionStyle.FEMALE) {
        (engine as? SystemTtsEngine)?.restoreDefaultVoice(style)
        if (::systemEngine.isInitialized) {
            systemEngine.restoreDefaultVoice(style)
        }
    }

    /**
     * TTS是否可用
     */
    fun isAvailable(): Boolean = engine?.isAvailable() ?: false

    /**
     * 设置状态回调（流式模式用）
     */
    fun setStateCallback(callback: TtsStateCallback?) {
        engine?.setStateCallback(callback)
    }

    /**
     * 获取当前音色配置
     */
    fun getCurrentVoiceProfile(): VoiceProfile = VOICE_FEMALE

    /**
     * 开始播报文本（整句模式）
     */
    fun speak(text: String, callback: TtsStateCallback? = null) {
        engine?.speak(text, callback) ?: run {
            Log.e(TAG, "No TTS engine available")
            callback?.onError(TextToSpeech.ERROR)
        }
    }

    /**
     * 流式播报：喂入一个文本块
     */
    fun speakChunk(text: String, isFirst: Boolean) {
        engine?.speakChunk(text, isFirst)
    }

    /**
     * 标记流式输入结束
     */
    fun endStream() {
        engine?.endStream()
    }

    /**
     * 获取当前音色 ID
     */
    fun getCurrentVoiceId(): String = currentVoiceId

    /**
     * 切换TTS音色
     * - 云端TTS（CosyVoice）：切换 voiceId
     * - 系统TTS（降级）：根据 voiceId 从 VoiceLibrary 查找对应的音调配置
     *
     * 幂等：若 voiceId 未变则直接返回，避免重复操作
     *
     * @param voiceId 音色ID，如 longanhuan_v3、longanyang 等
     */
    fun setVoice(voiceId: String) {
        // 幂等检查：相同音色直接返回
        if (currentVoiceId == voiceId) {
            Log.d(TAG, "setVoice: already $voiceId, skip")
            return
        }
        currentVoiceId = voiceId
        Log.i(TAG, "Set TTS voice: $voiceId")

        (engine as? CosyVoiceTtsEngine)?.setVoice(voiceId)
        // 系统TTS降级时的音色映射：从 VoiceLibrary 查找
        // 每个 VoiceOption 自带 systemProfile，保证任意新音色断网时都能按性别给出正确的 pitch / rate
        (engine as? SystemTtsEngine)?.let { sysEngine ->
            val profile = VoiceLibrary.getById(voiceId)?.systemProfile
                ?: VOICE_FEMALE // 找不到时默认女声
            sysEngine.applyVoiceProfile(profile)
        }
    }

    /**
     * 预合成并缓存文本（不播放）
     * 用于提前合成问候语等常用文本，播放时秒开
     * 仅云端TTS支持，系统TTS忽略
     */
    fun preload(text: String) {
        (engine as? CosyVoiceTtsEngine)?.preload(text)
    }

    /**
     * 检查文本是否已预加载完成
     */
    fun isPreloaded(text: String): Boolean {
        return (engine as? CosyVoiceTtsEngine)?.isPreloaded(text) ?: false
    }

    /**
     * 停止播报
     */
    fun stop() {
        engine?.stop()
    }

    /**
     * 是否正在播报
     */
    fun isSpeaking(): Boolean {
        return engine?.isSpeaking() ?: false
    }

    /**
     * 释放资源
     */
    fun release() {
        engine?.release()
        if (this::systemEngine.isInitialized) {
            systemEngine.release()
        }
        engine = null
        Log.d(TAG, "TtsManager released")
    }
}
