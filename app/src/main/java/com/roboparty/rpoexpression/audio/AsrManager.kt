/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.roboparty.rpoexpression.integration.ModuleStateManager
import com.roboparty.rpoexpression.util.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ASR语音识别管理器
 * 基于通义千问qwen3-asr-flash-realtime实时语音识别，WebSocket长连接
 */
//单例模式
class AsrManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AsrManager"

        @Volatile
        private var instance: AsrManager? = null

        fun getInstance(context: Context): AsrManager {
            return instance ?: synchronized(this) {
                instance ?: AsrManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * ASR识别结果回调
     */
    interface AsrResultCallback {
        /**
         * 识别完成，返回最终结果
         */
        fun onResult(text: String) {}

        /**
         * 识别过程中返回临时结果（可选）
         */
        fun onPartialResult(text: String) {}

        /**
         * 识别出错
         */
        fun onError(errorMsg: String) {}
    }

    private val gson = Gson()
    private lateinit var okHttpClient: OkHttpClient
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioExecutor = Executors.newSingleThreadExecutor()
    private var isRecording = false
    private var isWsConnected = false
    private var isSessionConfigured = false // 会话是否配置完成，配置完成后才发送音频
    private var isInitialized = false
    private var shouldTriggerExpression = true // 当前会话是否应该触发表情（语音唤醒模式为false）
    private var resultCallback: AsrResultCallback? = null
    // 识别超时处理：15秒无结果自动超时
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (isRecording) {
            Log.w(TAG, "Recognition timeout after 15s")
            resultCallback?.onError("识别超时，请再说一次")
            if (shouldTriggerExpression) {
                ModuleStateManager.onAsrError()
            }
            stopListening()
        }
    }

    /**
     * 初始化ASR模块
     */
    fun init() {
        if (isInitialized) {
            return
        }
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        isInitialized = true
        Log.d(TAG, "AsrManager initialized")
    }

    /**
     * 开始语音识别
     * @param callback 识别结果回调
     * @param triggerExpression 是否触发识别中表情（语音唤醒模式传false，避免常驻显示识别中）
     */
    fun startListening(callback: AsrResultCallback, triggerExpression: Boolean = true) {
        if (!isInitialized) {
            callback.onError("ASR未初始化")
            return
        }
        if (isRecording) {
            Log.w(TAG, "Already listening")
            return
        }
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.onError("未授予录音权限")
            return
        }

        resultCallback = callback
        isSessionConfigured = false
        shouldTriggerExpression = triggerExpression
        if (triggerExpression) {
            ModuleStateManager.onAsrStartListening()
        }
        Log.d(TAG, "Start ASR listening, triggerExpression=$triggerExpression, connecting WebSocket...")

        // 建立WebSocket连接
        val request = Request.Builder()
            .url(Constants.QWEN_ASR_WS_URL)
            .addHeader("Authorization", "Bearer ${Constants.QWEN_ASR_API_KEY}")
            .addHeader("OpenAI-Beta", "realtime=v1") // 必须的请求头
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isWsConnected = true
                // 连接成功后发送session.update配置
                sendSessionUpdate(webSocket)
            }

            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                handleServerMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed, code=$code, reason=$reason")
                isWsConnected = false
                isSessionConfigured = false
                stopAudioRecording()
                if (code != 1000) {
                    // 异常关闭
                    resultCallback?.onError("连接断开: $reason")
                    if (shouldTriggerExpression) {
                        ModuleStateManager.onAsrError()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                isWsConnected = false
                isSessionConfigured = false
                stopAudioRecording()
                resultCallback?.onError("连接失败: ${t.message}")
                if (shouldTriggerExpression) {
                    ModuleStateManager.onAsrError()
                }
            }
        })
    }

    /**
     * 发送session.update配置，按照官方文档格式
     */
    private fun sendSessionUpdate(webSocket: WebSocket) {
        val sessionUpdate = JsonObject().apply {
            addProperty("event_id", UUID.randomUUID().toString())
            addProperty("type", "session.update")
            add("session", JsonObject().apply {
                addProperty("input_audio_format", "pcm") // 官方参数有效值为pcm，对应16位小端PCM
                addProperty("sample_rate", Constants.AUDIO_SAMPLE_RATE)
                add("input_audio_transcription", JsonObject().apply {
                    addProperty("language", "zh") // 中文识别
                })
                add("turn_detection", JsonObject().apply {
                    addProperty("type", "server_vad")
                    addProperty("threshold", 0.15) // 提高灵敏度，更容易检测到语音结束
                    addProperty("silence_duration_ms", 500) // 500ms静音判定为说话结束，响应更快
                })
            })
        }
        val json = gson.toJson(sessionUpdate)
        Log.d(TAG, "Send session.update: $json")
        webSocket.send(json)
    }

    /**
     * 处理服务端消息
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun handleServerMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return
            when (type) {
                "session.created" -> {
                    Log.d(TAG, "Session created")
                }
                "session.updated" -> {
                    Log.d(TAG, "Session configured successfully, start audio recording")
                    isSessionConfigured = true
                    // 配置成功，开始录音
                    startAudioRecording()
                }
                "error" -> {
                    val errorMsg = json.get("message")?.asString ?: "未知错误"
                    Log.e(TAG, "Server error: $errorMsg")
                    resultCallback?.onError("服务端错误: $errorMsg")
                    if (shouldTriggerExpression) {
                        ModuleStateManager.onAsrError()
                    }
                    stopListening()
                }
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech started")
                    if (shouldTriggerExpression) {
                        ModuleStateManager.onAsrProcessing()
                    }
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped")
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    // 识别完成，返回最终结果：transcript直接是结果字符串
                    val text = json.get("transcript")?.asString?.trim() ?: ""
                    Log.d(TAG, "Recognition completed: $text")
                    if (shouldTriggerExpression) {
                        ModuleStateManager.onAsrFinish()
                    }
                    if (text.isNotEmpty()) {
                        resultCallback?.onResult(text)
                    }
                    // 识别完成后停止录音，等待下一次开始
                    stopListening()
                }
                "conversation.item.input_audio_transcription.text" -> {
                    // 临时识别结果：text是已确认部分，stash是临时草稿，拼接起来
                    val confirmed = json.get("text")?.asString ?: ""
                    val stash = json.get("stash")?.asString ?: ""
                    val partialText = (confirmed + stash).trim()
                    if (partialText.isNotEmpty()) {
                        resultCallback?.onPartialResult(partialText)
                    }
                }
                "conversation.item.input_audio_transcription.failed" -> {
                    // 识别失败
                    val errorMsg = json.getAsJsonObject("error")?.get("message")?.asString ?: "识别失败"
                    Log.e(TAG, "Recognition failed: $errorMsg")
                    resultCallback?.onError(errorMsg)
                    if (shouldTriggerExpression) {
                        ModuleStateManager.onAsrError()
                    }
                    stopListening()
                }
                "session.finished" -> {
                    Log.d(TAG, "Session finished")
                    stopListening()
                }
                else -> {
                    Log.d(TAG, "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse message failed", e)
        }
    }

    /**
     * 开始录音并发送音频数据
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        if (isRecording) {
            return
        }
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                Constants.AUDIO_SAMPLE_RATE,
                Constants.AUDIO_CHANNEL_CONFIG,
                Constants.AUDIO_FORMAT
            )
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
                resultCallback?.onError("录音参数不支持")
                if (shouldTriggerExpression) {
                    ModuleStateManager.onAsrError()
                }
                return
            }
            val bufferSize = minBufferSize * Constants.AUDIO_BUFFER_SIZE_MULTIPLIER

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                Constants.AUDIO_SAMPLE_RATE,
                Constants.AUDIO_CHANNEL_CONFIG,
                Constants.AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                resultCallback?.onError("录音初始化失败")
                if (shouldTriggerExpression) {
                    ModuleStateManager.onAsrError()
                }
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "Audio recording started, bufferSize=$bufferSize")
            // 启动15秒识别超时
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, 15000)

            // 后台线程读取音频数据并发送
            audioExecutor.execute {
                val buffer = ShortArray(bufferSize / 2) // 16bit = 2bytes per sample
                while (isRecording && audioRecord != null) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0 && isWsConnected && isSessionConfigured) {
                        // 转成字节数组，Base64编码发送
                        val byteBuffer = ByteBuffer.allocate(readCount * 2)
                        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until readCount) {
                            byteBuffer.putShort(buffer[i])
                        }
                        val audioBytes = byteBuffer.array()
                        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

                        val appendEvent = JsonObject().apply {
                            addProperty("event_id", UUID.randomUUID().toString())
                            addProperty("type", "input_audio_buffer.append")
                            addProperty("audio", base64Audio)
                        }
                        webSocket?.send(gson.toJson(appendEvent))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start recording failed", e)
            resultCallback?.onError("录音启动失败: ${e.message}")
            if (shouldTriggerExpression) {
                ModuleStateManager.onAsrError()
            }
            stopListening()
        }
    }

    /**
     * 停止录音
     */
    private fun stopAudioRecording() {
        if (!isRecording) {
            return
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording failed", e)
        }
        audioRecord = null
        Log.d(TAG, "Audio recording stopped")
    }

    /**
     * 停止识别
     */
    fun stopListening() {
        if (!isRecording && !isWsConnected) {
            return
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        Log.d(TAG, "Stop ASR listening")
        stopAudioRecording()
        if (isWsConnected) {
            // 发送结束会话
            val finishEvent = JsonObject().apply {
                addProperty("event_id", UUID.randomUUID().toString())
                addProperty("type", "session.finish")
            }
            webSocket?.send(gson.toJson(finishEvent))
            webSocket?.close(1000, "User stop")
        }
        webSocket = null
        isWsConnected = false
        isSessionConfigured = false
        resultCallback = null
        if (shouldTriggerExpression) {
            ModuleStateManager.onAsrFinish()
        }
    }

    /**
     * 是否正在识别中
     */
    fun isListening(): Boolean {
        return isRecording || isWsConnected
    }

    /**
     * 释放资源
     */
    fun release() {
        stopListening()
        if (::okHttpClient.isInitialized) {
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
        }
        audioExecutor.shutdownNow()
        isInitialized = false
        Log.d(TAG, "AsrManager released")
    }
}
