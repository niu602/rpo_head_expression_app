/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.roboparty.rpoexpression.integration.ModuleStateManager
import com.roboparty.rpoexpression.util.Constants
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.util.concurrent.TimeUnit

/**
 * 大模型管理器
 * 负责通义千问qwen3.6-plus API调用、单轮对话、异步回调、状态联动
 */
class LlmManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LlmManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: LlmManager? = null

        fun getInstance(context: Context): LlmManager {
            return instance ?: synchronized(this) {
                instance ?: LlmManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * LLM请求回调（非流式）
     */
    interface LlmCallback {
        /**
         * 请求成功，返回大模型回复
         */
        fun onSuccess(response: String)

        /**
         * 请求失败
         */
        fun onError(errorMsg: String)
    }

    /**
     * LLM流式请求回调
     * 边生成边回调，首字延迟从「等完整回复」降到「等第一句话」
     */
    interface StreamCallback {
        /**
         * 收到一段增量文本（在IO线程回调，调用方需自行切线程）
         */
        fun onDelta(delta: String)

        /**
         * 流式传输全部结束，fullText为拼接后的完整回复
         */
        fun onComplete(fullText: String)

        /**
         * 请求失败
         */
        fun onError(errorMsg: String)
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    // 流式请求取消标记：cancelRequest() 置 true 后，
    // 正在读取的 SSE 流抛异常也不会回调 onComplete/onError，避免上层状态混乱
    @Volatile
    private var isStreamCancelled = false

    /**
     * OkHttp网络客户端，配置超时
     */
    lateinit var okHttpClient: OkHttpClient
        private set

    /**
     * 初始化大模型模块、OkHttp客户端
     */
    fun init() {
        if (isInitialized) {
            return
        }
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS) // 连接超时调长，适配网关
            .readTimeout(60, TimeUnit.SECONDS) // 大模型生成可能需要较长时间
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        isInitialized = true
        Log.d(TAG, "LlmManager initialized")
    }

    /**
     * 发送单轮对话请求
     * @param query 用户输入文本
     * @param callback 结果回调（主线程）
     */
    fun sendQuery(query: String, callback: LlmCallback) {
        Log.d(TAG, "Send LLM query: $query")
        ModuleStateManager.onLlmStartThinking()

        // 无LLM API Key时使用模拟回复，方便测试
        if (Constants.QWEN_LLM_API_KEY.isBlank() && Constants.ENABLE_MOCK_LLM_WHEN_NO_KEY) {
            Log.d(TAG, "LLM API Key not configured, use mock response")
            GlobalScope.launch {
                // 模拟1.5秒思考延迟
                delay(1500)
                val mockResponse = "（模拟回复）我听到你说：「${query.trim()}」，等LLM API Key配置完成后我就会返回真实回答啦~"
                ModuleStateManager.onLlmFinish()
                mainHandler.post {
                    callback.onSuccess(mockResponse)
                }
            }
            return
        }

        // 构造请求体（手动构造Json，避免序列化问题，仅保留核心参数）
        val requestUrl = "${Constants.QWEN_LLM_BASE_URL}${Constants.QWEN_LLM_CHAT_ENDPOINT}"
        val requestBodyJson = JsonObject().apply {
            addProperty("model", Constants.QWEN_LLM_MODEL)
            // 手动构造messages数组，确保格式正确
            val messagesArray = com.google.gson.JsonArray()
            val userMessage = JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", query)
            }
            messagesArray.add(userMessage)
            add("messages", messagesArray)
            addProperty("stream", false) // 强制非流式返回，避免默认流式导致解析失败
        }
        val requestBodyStr = gson.toJson(requestBodyJson)
        Log.d(TAG, "LLM request url: $requestUrl")
        Log.d(TAG, "LLM request body: $requestBodyStr")
        val requestBody = requestBodyStr.toRequestBody(JSON_MEDIA_TYPE)

        // 构造请求
        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer ${Constants.QWEN_LLM_API_KEY}")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

        // 异步请求
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                val errorMsg = "LLM request failed: [${e.javaClass.simpleName}] ${e.message}"
                Log.e(TAG, errorMsg, e)
                ModuleStateManager.onLlmError()
                mainHandler.post {
                    callback.onError(errorMsg)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val errorBody = it.body?.string() ?: "Unknown error"
                        val errorMsg = "LLM request error: code=${it.code}, $errorBody"
                        Log.e(TAG, errorMsg)
                        ModuleStateManager.onLlmError()
                        mainHandler.post {
                            callback.onError(errorMsg)
                        }
                        return
                    }

                    val responseBody = it.body?.string()
                    Log.d(TAG, "LLM response code: ${it.code}")
                    Log.d(TAG, "LLM response body: $responseBody")
                    if (responseBody.isNullOrEmpty()) {
                        val errorMsg = "LLM response is empty"
                        Log.e(TAG, errorMsg)
                        ModuleStateManager.onLlmError()
                        mainHandler.post {
                            callback.onError(errorMsg)
                        }
                        return
                    }

                    try {
                        val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                        val choices = responseJson.getAsJsonArray("choices")
                        if (choices == null || choices.size() == 0) {
                            val errorMsg = "LLM response no choices"
                            Log.e(TAG, errorMsg)
                            ModuleStateManager.onLlmError()
                            mainHandler.post {
                                callback.onError(errorMsg)
                            }
                            return
                        }
                        val message = choices[0].asJsonObject.getAsJsonObject("message")
                        val content = message.get("content").asString.trim()
                        Log.d(TAG, "LLM response: $content")
                        ModuleStateManager.onLlmFinish()
                        mainHandler.post {
                            callback.onSuccess(content)
                        }
                    } catch (e: Exception) {
                        val errorMsg = "Parse LLM response failed: ${e.message}"
                        Log.e(TAG, errorMsg, e)
                        ModuleStateManager.onLlmError()
                        mainHandler.post {
                            callback.onError(errorMsg)
                        }
                    }
                }
            }
        })
    }

    /**
     * 发送流式对话请求（SSE streaming）
     * 
     * 将请求 stream 设为 true，解析 Server-Sent-Events 逐句回调。
     * 与 sendQuery 相比，首字延迟从「等全部 token 生成完」降到「等第一句话」。*
     * @param query    用户输入文本
     * @param callback 流式回调（onDelta 在 IO 线程回调）
     */
    fun sendQueryStream(query: String, callback: StreamCallback) {
        Log.d(TAG, "Send LLM stream query: $query")
        ModuleStateManager.onLlmStartThinking()
        isStreamCancelled = false

        // Mock模式
        if (Constants.QWEN_LLM_API_KEY.isBlank() && Constants.ENABLE_MOCK_LLM_WHEN_NO_KEY) {
            Log.d(TAG, "LLM API Key not configured, use mock stream response")
            GlobalScope.launch {
                val parts = listOf(
                    "（模拟流式回复）我听到你说：「${query.trim()}」，",
                    "等LLM API Key配置好后，",
                    "我就会返回真实的流式回答啦~"
                )
                val full = StringBuilder()
                parts.forEach { part ->
                    delay(400)
                    full.append(part)
                    callback.onDelta(part)
                }
                ModuleStateManager.onLlmFinish()
                callback.onComplete(full.toString())
            }
            return
        }

        val requestUrl = "${Constants.QWEN_LLM_BASE_URL}${Constants.QWEN_LLM_CHAT_ENDPOINT}"
        val requestBodyJson = JsonObject().apply {
            addProperty("model", Constants.QWEN_LLM_MODEL)
            val messagesArray = com.google.gson.JsonArray()
            messagesArray.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", query)
            })
            add("messages", messagesArray)
            addProperty("stream", true) // ← 关键：开启SSE流式
            // 可选：限制最大token数，加速首字返回
            // addProperty("max_tokens", 512)
        }
        val requestBodyStr = gson.toJson(requestBodyJson)
        Log.d(TAG, "LLM stream request: $requestBodyStr")

        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer ${Constants.QWEN_LLM_API_KEY}")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Accept", "text/event-stream")
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 异步流式请求
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // cancelRequest() 触发的取消不回调错误
                if (call.isCanceled() || isStreamCancelled) {
                    Log.d(TAG, "LLM stream cancelled, suppress onFailure")
                    return
                }
                val errorMsg = "LLM stream failed: [${e.javaClass.simpleName}] ${e.message}"
                Log.e(TAG, errorMsg, e)
                ModuleStateManager.onLlmError()
                callback.onError(errorMsg)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        val errorMsg = "LLM stream error: code=${resp.code}, $errorBody"
                        Log.e(TAG, errorMsg)
                        ModuleStateManager.onLlmError()
                        callback.onError(errorMsg)
                        return
                    }

                    val source: BufferedSource = resp.body?.source()
                        ?: run {
                            callback.onError("LLM stream body is null")
                            return
                        }

                    val fullText = StringBuilder()
                    try {
                        // 逐行读取 SSE 数据
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            // SSE 格式：每行以 "data: " 前缀
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            // [DONE] 表示流结束
                            if (data == "[DONE]") break
                            if (data.isEmpty()) continue

                            try {
                                val chunkJson = gson.fromJson(data, JsonObject::class.java)
                                val choices = chunkJson?.getAsJsonArray("choices")
                                if (choices != null && choices.size() > 0) {
                                    val delta = choices[0].asJsonObject
                                        .getAsJsonObject("delta")
                                    val content = delta?.get("content")?.asString
                                    if (content != null && content.isNotEmpty()) {
                                        fullText.append(content)
                                        callback.onDelta(content)
                                    }
                                }
                            } catch (e: Exception) {
                                // 单行解析失败不影响整体
                                Log.w(TAG, "Skip unparseable SSE chunk: $data", e)
                            }
                        }

                        val result = fullText.toString().trim()
                        Log.d(TAG, "LLM stream complete, total length: ${result.length}")
                        // cancelRequest() 后到达的正常结束也需抑制
                        if (isStreamCancelled) {
                            Log.d(TAG, "LLM stream cancelled before complete, suppress onComplete")
                            return
                        }
                        ModuleStateManager.onLlmFinish()
                        callback.onComplete(result)
                    } catch (e: Exception) {
                        // cancelRequest() 导致的读取异常不回调
                        if (isStreamCancelled) {
                            Log.d(TAG, "LLM stream cancelled during read, suppress callback")
                            return
                        }
                        val errorMsg = "LLM stream read error: ${e.message}"
                        Log.e(TAG, errorMsg, e)
                        ModuleStateManager.onLlmError()
                        // 如果已经有部分文本，仍然回调完成（部分结果优于空结果）
                        if (fullText.isNotEmpty()) {
                            callback.onComplete(fullText.toString().trim())
                        } else {
                            callback.onError(errorMsg)
                        }
                    }
                }
            }
        })
    }

    /**
     * 取消当前请求
     */
    fun cancelRequest() {
        Log.d(TAG, "Cancel LLM request")
        isStreamCancelled = true
        okHttpClient.dispatcher.cancelAll()
    }

    /**
     * 释放网络资源
     */
    fun release() {
        cancelRequest()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        Log.d(TAG, "LlmManager released")
    }
}
