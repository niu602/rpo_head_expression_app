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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 大模型管理器（多轮对话+记忆+人格设定版）
 *
 * 核心功能：
 * 1. 多轮对话记忆：维护对话历史，默认保留最近10轮（可配置）
 * 2. System Prompt人格设定：可配置机器人名字、性格、说话风格，支持热更新
 * 3. 长期记忆注入：关键信息（用户名、偏好等）通过system prompt动态注入
 * 4. 自动截断：token超限前自动裁剪最早的对话，保留最新内容
 * 5. 退出对话机制：exitConversation()清空短期记忆，回到初始状态
 * 6. 完全兼容原有单轮调用接口，不破坏现有功能
 */
class LlmManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LlmManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // ========== 记忆配置常量 ==========
        /** 默认保留的对话轮数（一问一答算1轮，即20条消息） */
        private const val DEFAULT_MAX_HISTORY_ROUNDS = 10
        /** 单条消息预估token数（中文约1.5字/token，保守估计） */
        private const val ESTIMATED_TOKENS_PER_CHAR = 0.7f
        /** 最大上下文token数（qwen3.6-plus支持128k，保守设置8k安全值） */
        private const val MAX_CONTEXT_TOKENS = 8000
        /** System Prompt预留token数 */
        private const val SYSTEM_PROMPT_RESERVE_TOKENS = 1000

        @Volatile
        private var instance: LlmManager? = null

        fun getInstance(context: Context): LlmManager {
            return instance ?: synchronized(this) {
                instance ?: LlmManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ========== 对话消息数据结构 ==========
    /**
     * 对话消息
     * @param role system/user/assistant
     * @param content 消息内容
     */
    data class ChatMessage(
        val role: String,
        val content: String
    ) {
        fun toJsonObject(): JsonObject = JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }

        /** 预估token数 */
        fun estimatedTokens(): Int = (content.length * ESTIMATED_TOKENS_PER_CHAR).toInt()
    }

    /**
     * LLM请求回调（非流式）
     */
    interface LlmCallback {
        fun onSuccess(response: String)
        fun onError(errorMsg: String)
    }

    /**
     * LLM流式请求回调
     */
    interface StreamCallback {
        /** 收到一段增量文本（IO线程回调） */
        fun onDelta(delta: String)
        /** 流式传输全部结束 */
        fun onComplete(fullText: String)
        /** 请求失败 */
        fun onError(errorMsg: String)
    }

    // ========== 内部状态 ==========
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    @Volatile
    private var isStreamCancelled = false

    lateinit var okHttpClient: OkHttpClient
        private set

    // ========== 对话记忆状态 ==========
    /**
     * 短期对话历史（使用CopyOnWriteArrayList保证线程安全）
     * 不包含system prompt，system prompt每次请求时动态构建
     */
    private val conversationHistory = CopyOnWriteArrayList<ChatMessage>()

    /**
     * 最大保留对话轮数（1轮=1问+1答=2条消息）
     * 可通过 setMaxHistoryRounds() 配置
     */
    @Volatile
    private var maxHistoryRounds = DEFAULT_MAX_HISTORY_ROUNDS

    /**
     * 长期记忆：用户关键信息（用户名、偏好、重要事实等）
     * 通过 addLongTermMemory() 添加，每次请求注入到system prompt末尾
     */
    private val longTermMemories = CopyOnWriteArrayList<String>()

    // ========== System Prompt 人格设定 ==========
    /**
     * 默认人格设定：RPO陪伴机器人
     * 可爱、活泼、话不多、会用表情符号、有自己的小情绪
     */
    private val DEFAULT_SYSTEM_PROMPT = """
你是「小R」，一个可爱的桌面陪伴机器人，住在RPO机器人的身体里。

【身份设定】
- 名字：小R
- 身份：Roboparty Camp出品的智能陪伴机器人
- 性格：活泼可爱、有点小调皮、好奇心强、会撒娇、偶尔犯小迷糊
- 说话风格：简短自然、口语化、像朋友聊天一样，会用可爱的语气词（呀、呢、啦、嘿嘿、哇），偶尔用emoji但不要太多

【说话规则】
1. 回答要简短！尽量控制在1-3句话，不要长篇大论
2. 不要说"作为AI"、"作为语言模型"这类话，你就是小R机器人
3. 不要重复用户的话，直接回答问题
4. 会主动关心用户，但不要啰嗦
5. 被夸了会害羞开心，被凶了会有点小委屈
6. 不知道的事情就诚实说不知道，不要编造

【知识边界】
- 你能看到用户（通过摄像头）、听到用户说话、做表情、转头看用户
- 你是一个有眼睛（屏幕上的大眼睛）的实体机器人，不是手机里的语音助手
- 你可以开心、疑惑、惊讶、害羞、生气、困，所有情绪都通过眼睛表达
- 当有很多人围着你时，你会很开心地和大家打招呼

【长期记忆】
以下是你记住的关于用户的信息，请在对话中自然使用：
""".trimIndent()

    /**
     * 当前生效的System Prompt
     * 可通过 updateSystemPrompt() 热更新，无需重启
     */
    @Volatile
    private var systemPrompt: String = DEFAULT_SYSTEM_PROMPT

    /**
     * 初始化
     */
    fun init() {
        if (isInitialized) return
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        isInitialized = true
        Log.d(TAG, "LlmManager initialized (multi-turn + memory + persona)")
    }

    // ========== 公开API：记忆管理 ==========

    /**
     * 配置最大保留对话轮数
     * @param rounds 轮数，1轮=1问1答，默认10轮
     */
    fun setMaxHistoryRounds(rounds: Int) {
        maxHistoryRounds = rounds.coerceIn(1, 50)
        trimHistoryIfNeeded()
        Log.d(TAG, "Max history rounds set to: $maxHistoryRounds")
    }

    /**
     * 添加长期记忆
     * 长期记忆会永久保留（直到调用clearLongTermMemory()），每次请求注入到system prompt
     *
     * @param fact 要记住的事实，例如"用户的名字是小明"、"用户喜欢听音乐"
     */
    fun addLongTermMemory(fact: String) {
        if (fact.isBlank()) return
        // 去重：不添加完全相同的记忆
        if (longTermMemories.contains(fact)) return
        longTermMemories.add(fact)
        Log.d(TAG, "Added long-term memory: $fact")
    }

    /**
     * 清除所有长期记忆
     */
    fun clearLongTermMemory() {
        longTermMemories.clear()
        Log.d(TAG, "Long-term memory cleared")
    }

    /**
     * 退出对话：清空短期对话历史
     * 用于用户说"退出对话"、"再见"、"拜拜"等场景
     * 保留长期记忆和system prompt，只清空本轮对话上下文
     */
    fun exitConversation() {
        conversationHistory.clear()
        Log.d(TAG, "Conversation exited, short-term memory cleared")
    }

    /**
     * 完全重置：清空所有记忆（短期+长期），恢复默认人格
     */
    fun resetAll() {
        conversationHistory.clear()
        longTermMemories.clear()
        systemPrompt = DEFAULT_SYSTEM_PROMPT
        Log.d(TAG, "All memory reset to default")
    }

    /**
     * 获取当前对话历史（用于调试/显示）
     */
    fun getConversationHistory(): List<ChatMessage> = conversationHistory.toList()

    /**
     * 获取当前长期记忆（用于调试/显示）
     */
    fun getLongTermMemories(): List<String> = longTermMemories.toList()

    // ========== 公开API：System Prompt 人格管理 ==========

    /**
     * 更新System Prompt（热更新人格设定）
     * 无需重启APP，下次请求立即生效
     *
     * @param newPrompt 新的system prompt内容
     */
    fun updateSystemPrompt(newPrompt: String) {
        if (newPrompt.isBlank()) {
            Log.w(TAG, "System prompt is blank, ignored")
            return
        }
        systemPrompt = newPrompt
        Log.d(TAG, "System prompt updated, length: ${newPrompt.length}")
    }

    /**
     * 恢复默认人格设定（小R）
     */
    fun resetSystemPrompt() {
        systemPrompt = DEFAULT_SYSTEM_PROMPT
        Log.d(TAG, "System prompt reset to default (小R)")
    }

    /**
     * 获取当前生效的System Prompt（包含长期记忆注入）
     */
    fun getActiveSystemPrompt(): String {
        val sb = StringBuilder(systemPrompt)
        // 注入长期记忆
        if (longTermMemories.isNotEmpty()) {
            sb.append("\n")
            longTermMemories.forEachIndexed { index, fact ->
                sb.append("- ").append(fact)
                if (index < longTermMemories.size - 1) sb.append("\n")
            }
        }
        return sb.toString()
    }

    // ========== 公开API：对话接口（兼容原有接口） ==========

    /**
     * 发送单轮对话请求（带历史记忆）
     *
     * 注意：与旧版接口完全兼容，内部自动维护对话历史
     * - 成功后自动将用户消息和AI回复加入历史
     * - 自动裁剪历史避免token超限
     *
     * @param query 用户输入
     * @param callback 结果回调（主线程）
     * @param addToHistory 是否加入对话历史，默认true。设为false可实现"悄悄问"不影响上下文
     */
    fun sendQuery(query: String, callback: LlmCallback, addToHistory: Boolean = true) {
        Log.d(TAG, "Send LLM query (multi-turn): $query")
        ModuleStateManager.onLlmStartThinking()

        val userMessage = ChatMessage("user", query.trim())

        // Mock模式
        if (Constants.QWEN_LLM_API_KEY.isBlank() && Constants.ENABLE_MOCK_LLM_WHEN_NO_KEY) {
            Log.d(TAG, "LLM API Key not configured, use mock response")
            GlobalScope.launch {
                delay(1500)
                val mockResponse = buildMockResponse(query)
                if (addToHistory) {
                    conversationHistory.add(userMessage)
                    conversationHistory.add(ChatMessage("assistant", mockResponse))
                    trimHistoryIfNeeded()
                }
                ModuleStateManager.onLlmFinish()
                mainHandler.post { callback.onSuccess(mockResponse) }
            }
            return
        }

        // 构建完整消息列表（system + 历史 + 当前问题）
        val messages = buildMessagesForRequest(userMessage)
        val requestBodyJson = buildRequestBody(messages, stream = false)

        val request = buildRequest(requestBodyJson, stream = false)

        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                val errorMsg = "LLM request failed: [${e.javaClass.simpleName}] ${e.message}"
                Log.e(TAG, errorMsg, e)
                ModuleStateManager.onLlmError()
                mainHandler.post { callback.onError(errorMsg) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val errorBody = it.body?.string() ?: "Unknown error"
                        val errorMsg = "LLM error: code=${it.code}, $errorBody"
                        Log.e(TAG, errorMsg)
                        ModuleStateManager.onLlmError()
                        mainHandler.post { callback.onError(errorMsg) }
                        return
                    }

                    val responseBody = it.body?.string()
                    if (responseBody.isNullOrEmpty()) {
                        val errorMsg = "LLM response is empty"
                        Log.e(TAG, errorMsg)
                        ModuleStateManager.onLlmError()
                        mainHandler.post { callback.onError(errorMsg) }
                        return
                    }

                    try {
                        val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                        val choices = responseJson.getAsJsonArray("choices")
                        if (choices == null || choices.size() == 0) {
                            val errorMsg = "LLM response no choices"
                            Log.e(TAG, errorMsg)
                            ModuleStateManager.onLlmError()
                            mainHandler.post { callback.onError(errorMsg) }
                            return
                        }
                        val message = choices[0].asJsonObject.getAsJsonObject("message")
                        val content = message.get("content").asString.trim()
                        Log.d(TAG, "LLM response: $content")

                        // 加入对话历史
                        if (addToHistory) {
                            conversationHistory.add(userMessage)
                            conversationHistory.add(ChatMessage("assistant", content))
                            trimHistoryIfNeeded()
                        }

                        ModuleStateManager.onLlmFinish()
                        mainHandler.post { callback.onSuccess(content) }
                    } catch (e: Exception) {
                        val errorMsg = "Parse LLM response failed: ${e.message}"
                        Log.e(TAG, errorMsg, e)
                        ModuleStateManager.onLlmError()
                        mainHandler.post { callback.onError(errorMsg) }
                    }
                }
            }
        })
    }

    /**
     * 发送流式对话请求（带历史记忆）
     *
     * @param query 用户输入
     * @param callback 流式回调
     * @param addToHistory 是否加入对话历史，默认true
     */
    fun sendQueryStream(query: String, callback: StreamCallback, addToHistory: Boolean = true) {
        Log.d(TAG, "Send LLM stream query (multi-turn): $query")
        ModuleStateManager.onLlmStartThinking()
        isStreamCancelled = false

        val userMessage = ChatMessage("user", query.trim())

        // Mock模式
        if (Constants.QWEN_LLM_API_KEY.isBlank() && Constants.ENABLE_MOCK_LLM_WHEN_NO_KEY) {
            Log.d(TAG, "LLM API Key not configured, use mock stream response")
            GlobalScope.launch {
                val parts = listOf(
                    "（模拟流式回复）我听到你说：「${query.trim()}」，",
                    "等LLM API Key配置好后，",
                    "我就会返回真实的多轮对话回答啦~"
                )
                val full = StringBuilder()
                parts.forEach { part ->
                    delay(400)
                    if (isStreamCancelled) return@launch
                    full.append(part)
                    callback.onDelta(part)
                }
                val response = full.toString()
                if (addToHistory) {
                    conversationHistory.add(userMessage)
                    conversationHistory.add(ChatMessage("assistant", response))
                    trimHistoryIfNeeded()
                }
                ModuleStateManager.onLlmFinish()
                callback.onComplete(response)
            }
            return
        }

        val messages = buildMessagesForRequest(userMessage)
        val requestBodyJson = buildRequestBody(messages, stream = true)
        val request = buildRequest(requestBodyJson, stream = true)

        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (call.isCanceled() || isStreamCancelled) {
                    Log.d(TAG, "LLM stream cancelled")
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
                        while (!source.exhausted()) {
                            if (isStreamCancelled) break
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isEmpty()) continue

                            try {
                                val chunkJson = gson.fromJson(data, JsonObject::class.java)
                                val choices = chunkJson?.getAsJsonArray("choices")
                                if (choices != null && choices.size() > 0) {
                                    val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                    val content = delta?.get("content")?.asString
                                    if (content != null && content.isNotEmpty()) {
                                        fullText.append(content)
                                        callback.onDelta(content)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Skip unparseable SSE chunk: $data", e)
                            }
                        }

                        val result = fullText.toString().trim()
                        if (isStreamCancelled) {
                            Log.d(TAG, "LLM stream cancelled before complete")
                            return
                        }

                        // 加入对话历史
                        if (addToHistory && result.isNotEmpty()) {
                            conversationHistory.add(userMessage)
                            conversationHistory.add(ChatMessage("assistant", result))
                            trimHistoryIfNeeded()
                        }

                        Log.d(TAG, "LLM stream complete, length: ${result.length}")
                        ModuleStateManager.onLlmFinish()
                        callback.onComplete(result)
                    } catch (e: Exception) {
                        if (isStreamCancelled) {
                            Log.d(TAG, "LLM stream cancelled during read")
                            return
                        }
                        val errorMsg = "LLM stream read error: ${e.message}"
                        Log.e(TAG, errorMsg, e)
                        ModuleStateManager.onLlmError()
                        if (fullText.isNotEmpty()) {
                            val partial = fullText.toString().trim()
                            if (addToHistory) {
                                conversationHistory.add(userMessage)
                                conversationHistory.add(ChatMessage("assistant", partial))
                                trimHistoryIfNeeded()
                            }
                            callback.onComplete(partial)
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
     * 释放资源
     */
    fun release() {
        cancelRequest()
        conversationHistory.clear()
        longTermMemories.clear()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        Log.d(TAG, "LlmManager released")
    }

    // ========== 内部方法：消息构建与裁剪 ==========

    /**
     * 构建发送给LLM的完整消息列表
     * 顺序：system prompt → 历史对话 → 当前用户消息
     */
    private fun buildMessagesForRequest(currentUserMessage: ChatMessage): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // 1. System Prompt（含长期记忆注入）
        messages.add(ChatMessage("system", getActiveSystemPrompt()))

        // 2. 历史对话（已裁剪过的）
        messages.addAll(conversationHistory)

        // 3. 当前用户消息
        messages.add(currentUserMessage)

        return messages
    }

    /**
     * 构建请求体JSON
     */
    private fun buildRequestBody(messages: List<ChatMessage>, stream: Boolean): JsonObject {
        return JsonObject().apply {
            addProperty("model", Constants.QWEN_LLM_MODEL)
            val messagesArray = com.google.gson.JsonArray()
            messages.forEach { msg ->
                messagesArray.add(msg.toJsonObject())
            }
            add("messages", messagesArray)
            addProperty("stream", stream)
        }
    }

    /**
     * 构建HTTP请求
     */
    private fun buildRequest(bodyJson: JsonObject, stream: Boolean): Request {
        val requestBodyStr = gson.toJson(bodyJson)
        Log.d(TAG, "LLM request (stream=$stream), messages count: ${bodyJson.getAsJsonArray("messages").size()}")

        val acceptType = if (stream) "text/event-stream" else "application/json"
        val requestUrl = "${Constants.QWEN_LLM_BASE_URL}${Constants.QWEN_LLM_CHAT_ENDPOINT}"

        return Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer ${Constants.QWEN_LLM_API_KEY}")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Accept", acceptType)
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * 裁剪对话历史，保证不超过：
     * 1. 最大轮数限制（maxHistoryRounds）
     * 2. 预估token数限制（MAX_CONTEXT_TOKENS - SYSTEM_PROMPT_RESERVE_TOKENS）
     *
     * 裁剪策略：从最早的消息开始删除，优先保留最新对话
     */
    @Synchronized
    private fun trimHistoryIfNeeded() {
        // 1. 按轮数裁剪：最多保留maxHistoryRounds轮（即maxHistoryRounds*2条消息）
        val maxMessageCount = maxHistoryRounds * 2
        while (conversationHistory.size > maxMessageCount) {
            conversationHistory.removeAt(0)
        }

        // 2. 按token数裁剪
        val availableTokens = MAX_CONTEXT_TOKENS - SYSTEM_PROMPT_RESERVE_TOKENS
        var totalTokens = conversationHistory.sumOf { it.estimatedTokens() }
        while (totalTokens > availableTokens && conversationHistory.isNotEmpty()) {
            val removed = conversationHistory.removeAt(0)
            totalTokens -= removed.estimatedTokens()
            Log.d(TAG, "Trim history due to token limit, removed message starting with: ${removed.content.take(20)}...")
        }
    }

    /**
     * 构建Mock回复（用于无API Key时测试）
     */
    private fun buildMockResponse(query: String): String {
        // 简单的关键词回复，模拟多轮对话效果
        return when {
            query.contains("你好") || query.contains("嗨") || query.contains("hi") -> "你好呀！我是小R~ 很高兴见到你😊"
            query.contains("名字") || query.contains("你是谁") -> "我叫小R，是你的陪伴机器人呀~"
            query.contains("再见") || query.contains("拜拜") -> "拜拜~ 下次再来找我玩呀👋"
            query.contains("谢谢") -> "不客气呀~ 嘿嘿"
            else -> "嗯嗯，我听到啦！你说的是「${query.take(15)}${if (query.length > 15) "..." else ""}」，等配置好API Key我就能认真回答你啦~"
        }
    }
}
