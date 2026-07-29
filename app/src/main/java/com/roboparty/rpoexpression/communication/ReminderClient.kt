/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.communication

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.roboparty.rpoexpression.RpoExpressionApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音日历后端通信客户端
 *
 * 负责和SpringBoot语音日历后端通信，实现：
 * - HTTP接口：语音新增日程、获取今日日程、标记完成/贪睡
 * - WebSocket长连接：接收后端主动推送的提醒消息
 * - 30秒心跳、断连自动重连
 */
class ReminderClient private constructor() {

    companion object {
        private const val TAG = "ReminderClient"
        private const val DEFAULT_SERVER_HOST = "10.43.2.89" // 默认后端地址，后续可配置
        private const val DEFAULT_SERVER_PORT = 8888
        private const val HEARTBEAT_INTERVAL = 30000L // 30秒心跳，和现有TCP客户端一致
        private const val RECONNECT_INTERVAL = 3000L // 断连后3秒重连
        private const val ACTION_REMINDER_RECEIVED = "com.roboparty.rpoexpression.REMINDER_RECEIVED"
        private const val EXTRA_REMINDER_ID = "reminder_id"
        private const val EXTRA_REMINDER_TYPE = "reminder_type"
        private const val EXTRA_REMINDER_CONTENT = "reminder_content"

        // 提醒类型枚举
        const val TYPE_ALARM = "alarm" // 闹钟
        const val TYPE_WORK = "work" // 工作/会议
        const val TYPE_BIRTHDAY = "birthday" // 生日/纪念日
        const val TYPE_TODO = "todo" // 普通待办

        @Volatile
        private var instance: ReminderClient? = null

        fun getInstance(): ReminderClient {
            return instance ?: synchronized(this) {
                instance ?: ReminderClient().also { instance = it }
            }
        }
    }

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    // 数据类：日程信息
    data class ReminderInfo(
        val id: Long,
        val time: String,
        val content: String,
        val type: String = TYPE_TODO,
        @SerializedName("is_done") val isDone: Boolean = false
    )

    // 数据类：接口通用返回
    data class ApiResponse<T>(
        val code: Int,
        val msg: String,
        val data: T?
    )

    // 核心组件
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    // 配置
    private var serverHost: String = DEFAULT_SERVER_HOST
    private var serverPort: Int = DEFAULT_SERVER_PORT
    private var serverBaseUrl: String = "http://$serverHost:$serverPort"
    private var wsUrl: String = "ws://$serverHost:$serverPort/ws/reminder"

    // OkHttpClient配置
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS) // 连接超时10秒
            .readTimeout(30, TimeUnit.SECONDS) // 读超时30秒
            .writeTimeout(30, TimeUnit.SECONDS) // 写超时30秒
            .retryOnConnectionFailure(true) // 自动重试连接失败
            .build()
    }

    /**
     * 设置后端服务地址
     */
    fun setServerAddress(host: String, port: Int = DEFAULT_SERVER_PORT) {
        this.serverHost = host
        this.serverPort = port
        this.serverBaseUrl = "http://$host:$port"
        this.wsUrl = "ws://$host:$port/ws/reminder"
        Log.i(TAG, "Server address set to: $serverBaseUrl")
    }

    /**
     * 启动客户端，连接WebSocket
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Starting reminder client, server: $serverBaseUrl")
            // TODO 后端WebSocket配置好、网络通了之后打开下面这行
            // connectWebSocket()
            Log.w(TAG, "WebSocket暂时禁用，等后端配置好网络通了再启用，不影响HTTP接口开发")
        }
    }

    /**
     * 停止客户端，断开连接
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping reminder client")
            heartbeatJob?.cancel()
            reconnectJob?.cancel()
            webSocket?.close(1000, "Client stop")
            webSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED
            scope.coroutineContext.cancelChildren()
        }
    }

    /**
     * 语音新增日程
     * @param audioFile 录音文件
     * @return 新增成功返回ReminderInfo，失败返回null
     */
    suspend fun addReminderByVoice(audioFile: File): ReminderInfo? = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio", audioFile.name,
                    audioFile.asRequestBody("audio/mp3".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$serverBaseUrl/api/reminder/addByVoice")
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val type: Type = object : com.google.gson.reflect.TypeToken<ApiResponse<ReminderInfo>>() {}.type
            val apiResponse = gson.fromJson<ApiResponse<ReminderInfo>>(body, type)
            if (apiResponse.code == 200) {
                Log.i(TAG, "Add reminder success: ${apiResponse.data}")
                apiResponse.data
            } else {
                Log.e(TAG, "Add reminder failed: ${apiResponse.msg}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Add reminder error", e)
            null
        }
    }

    /**
     * 获取今日日程列表
     * @return 日程列表，失败返回空列表
     */
    suspend fun getTodayReminders(): List<ReminderInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverBaseUrl/api/reminder/today")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            val type: Type = object : com.google.gson.reflect.TypeToken<ApiResponse<List<ReminderInfo>>>() {}.type
            val apiResponse = gson.fromJson<ApiResponse<List<ReminderInfo>>>(body, type)
            if (apiResponse.code == 200) {
                Log.i(TAG, "Get today reminders success, count: ${apiResponse.data?.size ?: 0}")
                apiResponse.data ?: emptyList()
            } else {
                Log.e(TAG, "Get today reminders failed: ${apiResponse.msg}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get today reminders error", e)
            emptyList()
        }
    }

    /**
     * 标记提醒已完成
     */
    suspend fun markReminderDone(reminderId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverBaseUrl/api/reminder/$reminderId/done")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false
            val apiResponse = gson.fromJson(body, ApiResponse::class.java)
            if (apiResponse.code == 200) {
                Log.i(TAG, "Mark reminder $reminderId done success")
                true
            } else {
                Log.e(TAG, "Mark reminder done failed: ${apiResponse.msg}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mark reminder done error", e)
            false
        }
    }

    /**
     * 标记提醒贪睡
     */
    suspend fun markReminderSnooze(reminderId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverBaseUrl/api/reminder/$reminderId/snooze")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false
            val apiResponse = gson.fromJson(body, ApiResponse::class.java)
            if (apiResponse.code == 200) {
                Log.i(TAG, "Mark reminder $reminderId snooze success")
                true
            } else {
                Log.e(TAG, "Mark reminder snooze failed: ${apiResponse.msg}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mark reminder snooze error", e)
            false
        }
    }

    /**
     * 连接WebSocket
     */
    private fun connectWebSocket() {
        if (!isRunning.get()) return
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                Log.i(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                // 连接成功，启动心跳
                startHeartbeat()
                // 取消重连任务
                reconnectJob?.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d(TAG, "Received WebSocket message: $text")
                try {
                    // 解析提醒消息
                    val reminder = gson.fromJson(text, ReminderInfo::class.java)
                    if (reminder != null && reminder.id > 0) {
                        // 发送本地广播，通知上层处理提醒
                        val intent = Intent(ACTION_REMINDER_RECEIVED).apply {
                            putExtra(EXTRA_REMINDER_ID, reminder.id)
                            putExtra(EXTRA_REMINDER_TYPE, reminder.type)
                            putExtra(EXTRA_REMINDER_CONTENT, reminder.content)
                            setPackage(RpoExpressionApp.instance.packageName)
                        }
                        RpoExpressionApp.instance.sendBroadcast(intent)
                        Log.i(TAG, "Broadcast reminder received: ${reminder.content}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse reminder message error", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                Log.i(TAG, "WebSocket closing: code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Log.i(TAG, "WebSocket closed: code=$code, reason=$reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                heartbeatJob?.cancel()
                // 自动重连
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e(TAG, "WebSocket connection failed", t)
                _connectionState.value = ConnectionState.ERROR
                heartbeatJob?.cancel()
                // 自动重连
                scheduleReconnect()
            }
        })
    }

    /**
     * 启动心跳任务，30秒发一次心跳
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isRunning.get() && _connectionState.value == ConnectionState.CONNECTED) {
                delay(HEARTBEAT_INTERVAL)
                try {
                    webSocket?.send("ping")
                    Log.d(TAG, "Heartbeat sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Send heartbeat error", e)
                    break
                }
            }
        }
    }

    /**
     * 安排重连任务
     */
    private fun scheduleReconnect() {
        if (!isRunning.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_INTERVAL)
            Log.i(TAG, "Reconnecting WebSocket...")
            connectWebSocket()
        }
    }
}
