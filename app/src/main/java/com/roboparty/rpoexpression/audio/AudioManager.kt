/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.log10
import kotlin.random.Random
import kotlinx.coroutines.isActive
import com.roboparty.rpoexpression.expression.ExpressionStyle
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

    // 音量检测
    private var mediaRecorder: MediaRecorder? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var volumeDetectJob: Job? = null

    // 当前音量dB
    private val _audioVolume = MutableStateFlow(0f)
    val audioVolume: StateFlow<Float> = _audioVolume.asStateFlow()

    // 声源方向：-1左，0中，1右
    private val _soundDirection = MutableStateFlow(0f)
    val soundDirection: StateFlow<Float> = _soundDirection.asStateFlow()

    // ========== 打断（Barge-in）功能 ==========
    var bargeInEnabled = true          // 是否启用语音打断：TTS播报时用户说话自动停止播报
    var bargeInGuardMs = 600L          // TTS开头保护时间，防回声误触（毫秒）
    var ttsStartTime = 0L              // 当前TTS播报开始时间戳

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
     * 初始化音频模块（ASR + TTS + 音量检测）
     */
    fun init() {
        try {
            asrManager.init()
            ttsManager.init()
            startVolumeDetect()
            Log.d(TAG, "AudioManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "AudioManager init failed, skip audio module", e)
        }
    }

    /**
     * 启动音量检测
     */
    private fun startVolumeDetect() {
        try {
            //用MediaRecorder后台静默录音，不保存文件，只读取麦克风最大振幅
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile("/dev/null")
                prepare()
                start()
            }
        } catch (e: Exception) {
            // 捕获所有异常（权限不足、不支持等），避免闪退
            Log.e(TAG, "Start volume detect failed, skip audio perception", e)
            mediaRecorder = null
            return
        }

        if (mediaRecorder == null) return

        volumeDetectJob = scope.launch {
            var lastSoundTime = 0L
            while (isActive) {
                mediaRecorder?.let { recorder ->
                    val amplitude = recorder.maxAmplitude
                    //分贝计算，把麦克风原始振幅转为人耳感知分贝值
                    val db = if (amplitude > 0)  20 * log10(amplitude / 32768.0f) + 90 else 0f
                    _audioVolume.value = db.coerceIn(0f, 100f)

                    // 有声音的时候，随机转向声源方向（单麦模拟）
                    if (db > 30f) {
                        if (System.currentTimeMillis() - lastSoundTime > 1000) {
                            lastSoundTime = System.currentTimeMillis()
                            _soundDirection.value = (Random.nextFloat() - 0.5f) * 2f // -1~1
                            delay(800)
                            _soundDirection.value = 0f
                        }
                    } else {
                        _soundDirection.value = 0f
                    }
                }
                delay(100)
            }
        }
    }

    /**
     * 重新启动音量检测（权限授予后调用）
     */
    fun restartVolumeDetect() {
        if (volumeDetectJob?.isActive == true) return
        startVolumeDetect()
    }

    /**
     * 停止音量检测
     */
    private fun stopVolumeDetect() {
        volumeDetectJob?.cancel()
        volumeDetectJob = null
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Stop volume detect failed", e)
        }
        mediaRecorder = null
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
        // 打断模式关闭时，播报前先停止ASR；开启时保留ASR运行以支持打断
        if (!bargeInEnabled && asrManager.isListening()) {
            asrManager.stopListening()
        }

        // 记录TTS开始时间，用于开头防回声保护
        ttsStartTime = System.currentTimeMillis()

        // 包装回调：TTS结束时同时触发全局 speakFinishListener
        val wrappedCallback = object : TtsManager.TtsStateCallback {
            override fun onStart() {
                callback?.onStart()
                // 打断模式：TTS开始后启动后台静默ASR监听，用于检测用户打断
                if (bargeInEnabled && !asrManager.isListening()) {
                    startBargeInListen()
                }
            }
            override fun onDone() {
                // TTS正常结束，停止打断监听
                stopBargeInListen()
                callback?.onDone()
                // 触发全局播放完成回调
                speakFinishListener?.onSpeakFinsh()
            }
            override fun onError(errorCode: Int) {
                stopBargeInListen()
                callback?.onError(errorCode)
                // 出错也触发结束回调，避免卡住
                speakFinishListener?.onSpeakFinsh()
            }
        }

        ttsManager.speak(text, wrappedCallback)
    }

    /**
     * 启动打断专用的静默ASR监听
     * 不触发表情变化，只在后台识别用户语音
     */
    private fun startBargeInListen() {
        try {
            asrManager.startListening(object : AsrManager.AsrResultCallback {
                override fun onResult(text: String) {
                    val elapsed = System.currentTimeMillis() - ttsStartTime
                    // TTS开头保护期内的结果忽略，防回声误触发
                    if (elapsed < bargeInGuardMs) return
                    // 有效识别结果：触发打断
                    if (text.isNotBlank() && text.length >= 2) {
                        // 停止TTS播报
                        ttsManager.stop()
                        stopBargeInListen()
                        // 通知上层发生了打断
                        bargeInListener?.onBargeIn(text)
                    }
                }
                override fun onError(errorMsg: String) {
                    // 打断监听出错静默处理，不影响TTS播放
                }
            }, triggerExpression = false) // 静默模式，不触发识别中表情
        } catch (e: Exception) {
            Log.e(TAG, "Start barge-in listen failed", e)
        }
    }

    /**
     * 停止打断监听
     */
    private fun stopBargeInListen() {
        try {
            if (asrManager.isListening()) {
                asrManager.stopListening()
            }
        } catch (e: Exception) {
            // 忽略停止异常
        }
    }

    /**
     * 停止TTS播报
     */
    fun stopSpeaking() {
        stopBargeInListen()
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
        stopVolumeDetect()
        asrManager.release()
        ttsManager.release()
        Log.d(TAG, "AudioManager released")
    }

}
