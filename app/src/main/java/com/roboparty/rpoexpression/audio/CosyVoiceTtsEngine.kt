/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.roboparty.rpoexpression.integration.ModuleStateManager
import com.roboparty.rpoexpression.util.Constants
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CosyVoice 云端情感 TTS 引擎（HTTP API 优化版 - 队列播放）
 *
 * 核心特性：
 * 1. 队列式播放：speakChunk 追加到队列，不会打断当前播放
 * 2. 分段合成：长文本按句子分割，避免字数限制截断
 * 3. 预加载：播放当前句时后台预合成下一句
 * 4. MP3 格式：文件更小下载更快
 * 5. 错误容错：单句失败自动跳过
 *
 * 两种模式：
 * - speak(text)：整句播报，清空队列重新开始
 * - speakChunk(text)：流式追加，加入队列按顺序播放
 */
class CosyVoiceTtsEngine(@Suppress("UNUSED_PARAMETER") context: Context) : TtsEngine {

    companion object {
        private const val TAG = "CosyVoiceTts"

        // HTTP 请求超时
        private const val HTTP_CONNECT_TIMEOUT_S = 10L
        private const val HTTP_READ_TIMEOUT_S = 20L
        private const val HTTP_WRITE_TIMEOUT_S = 10L

        // 单句最大字数
        private const val MAX_CHARS_PER_SENTENCE = 300

        // 状态
        private const val STATE_IDLE = 0
        private const val STATE_SYNTHESIZING = 1
        private const val STATE_PLAYING = 2

        // LRU 缓存配置
        private const val MAX_CACHE_SIZE = 60
        private const val EVICT_COUNT = 5

        // 播放队列容量（P1：有界队列，防止打断后旧内容残留）
        // 容量=2：当前正在播1句 + 队列最多积压1句，共2句
        private const val PLAY_QUEUE_CAPACITY = 2

        // ========== 句间倾听窗（P3：句间打断机制）==========
        // 每句播完后停顿的最小/最大时长（随机取值，模拟自然呼吸感）
        private const val INTER_SENTENCE_PAUSE_MIN_MS = 800L
        private const val INTER_SENTENCE_PAUSE_MAX_MS = 1200L
        // 回声尾巴：播放结束后短暂屏蔽麦克风，防止扬声器余音被误判为用户说话
        private const val ECHO_TAIL_MS = 200L
    }

    // ==================== 数据类 ====================

    /**
     * 播放队列中的句子，绑定会话ID
     *
     * 设计目的：
     * - 每个 TTS 请求（speak/speakChunk）携带 sessionId
     * - 播放时校验 sessionId == currentSessionId，不匹配则跳过（会话已被取消/切换）
     * - 根治旧架构中"旧会话的队列句子在新会话中继续播放"的竞态问题
     */
    private data class QueuedSentence(
        val text: String,
        val sessionId: Long
    )

    private data class TtsRequest(
        val model: String,
        val input: Input,
        val parameters: Parameters
    ) {
        data class Input(val text: String)
        data class Parameters(
            val voice: String,
            @SerializedName("text_type") val textType: String,
            val format: String
        )
    }

    private data class TtsResponse(
        val output: Output?,
        val usage: Usage?,
        @SerializedName("request_id") val requestId: String?,
        val code: String?,
        val message: String?
    ) {
        data class Output(
            val audio: Audio?,
            @SerializedName("finish_reason") val finishReason: String?
        ) {
            data class Audio(
                val id: String?,
                val url: String?,
                @SerializedName("expires_at") val expiresAt: Long?
            )
        }

        data class Usage(val characters: Int?)
    }

    // ==================== 成员变量 ====================

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val preloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var okHttpClient: OkHttpClient
    private var mediaPlayer: MediaPlayer? = null

    // 状态
    @Volatile private var engineState = STATE_IDLE
    @Volatile private var isInitialized = false

    // 回调
    private var stateCallback: TtsManager.TtsStateCallback? = null

    // 播放队列（有界，容量=2），每个句子绑定会话ID
    // 溢出策略：DROP_OLDEST（队列满时丢弃最旧的，保留最新的）
    // 设计目的：防止 LLM 流式输出过快导致队列积压过多句子，
    //           打断后旧内容残留播放的问题
    private val playQueue = LinkedBlockingQueue<QueuedSentence>(PLAY_QUEUE_CAPACITY)
    private val isPlaying = AtomicBoolean(false)
    private val hasStarted = AtomicBoolean(false)  // 标记是否已经开始过播放（用于 onStart 回调）

    /**
     * 当前活跃会话ID
     * - speak(sessionId) / speakChunk(isFirst=true, sessionId) 时设置
     * - stop() 时重置为 0
     * - playNextSentence 时校验队列中句子的 sessionId == currentSessionId，不匹配则跳过
     */
    @Volatile private var currentSessionId: Long = 0L

    /**
     * 当前正在播放句子的会话ID（用于回调透传）
     */
    @Volatile private var playingSessionId: Long = 0L

    // ========== 句间倾听窗（P3）==========
    private var interSentenceListener: TtsEngine.OnInterSentenceListener? = null
    @Volatile private var interSentenceInterrupted = false
    private var interSentenceRunnable: Runnable? = null

    // ==================== LRU 缓存结构 ====================

    /**
     * 缓存条目
     * - url: 音频URL
     * - expiresAt: URL过期时间戳（毫秒），0表示永不过期
     * - protected: 是否受保护（通过 preload() 预缓存的句子，LRU 淘汰时跳过）
     * - lastAccessTime: 最近访问时间（纳秒），用于 LRU 排序
     */
    private data class CacheEntry(
        val url: String,
        val expiresAt: Long,
        var protected: Boolean,
        var lastAccessTime: Long = System.nanoTime()
    )

    /**
     * LRU 音频缓存：key = "voiceId:text" -> CacheEntry
     * 核心规则：
     * - 播完不清、停止不清、出错也不清（永久保留，除非 LRU 淘汰）
     * - 超过 MAX_CACHE_SIZE 时触发 LRU 淘汰，优先淘汰过期条目，再淘汰最久未使用的非 protected 条目
     * - preload() 预缓存的条目标记为 protected，不被 LRU 淘汰
     */
    private val audioCache = ConcurrentHashMap<String, CacheEntry>()

    /** LRU 访问顺序队列：记录最近访问的 key，队首=最久未使用，队尾=最近使用 */
    private val lruQueue = LinkedBlockingDeque<String>()

    /** LRU 操作锁：保护 lruQueue 的复合操作（remove+offer、遍历淘汰等）的原子性 */
    private val lruLock = Any()

    // 当前使用的音色ID，默认从 Constants 读取
    @Volatile
    private var currentVoiceId: String = Constants.CLOUD_TTS_VOICE_ID

    /**
     * 获取带音色前缀的缓存key
     * 确保不同音色的缓存互不干扰
     *
     * @param text 文本内容
     * @param voiceId 可选的音色ID（用于 preload 快照），传 null 时使用当前音色
     */
    private fun getCacheKey(text: String, voiceId: String? = null): String = "${voiceId ?: currentVoiceId}:${text}"

    /**
     * LRU 淘汰：缓存超限时清理
     * 清理优先级：
     * 1. 已过期的条目（不管是不是 protected，过期了都没用了）
     * 2. 最久未使用的非 protected 条目
     *
     * 注意：整个淘汰过程在 lruLock 保护下执行，保证原子性
     */
    private fun evictIfNeeded() {
        if (audioCache.size <= MAX_CACHE_SIZE) return

        synchronized(lruLock) {
            // 双重检查：加锁后可能已经被其他线程淘汰过了
            if (audioCache.size <= MAX_CACHE_SIZE) return

            val now = System.currentTimeMillis()
            val toEvict = mutableListOf<String>()

            // ===== 第一阶段：清理已过期的条目 =====
            val expiredIterator = lruQueue.iterator()
            while (expiredIterator.hasNext()) {
                val key = expiredIterator.next()
                val entry = audioCache[key]
                if (entry != null && entry.expiresAt > 0 && entry.expiresAt < now) {
                    toEvict.add(key)
                    expiredIterator.remove()
                    Log.d(TAG, "LRU evicted (expired): ${key.take(30)}...")
                }
            }

            // ===== 第二阶段：如果还超限，LRU 淘汰最久未使用的非 protected 条目 =====
            if (audioCache.size - toEvict.size > MAX_CACHE_SIZE) {
                val lruIterator = lruQueue.iterator()
                while (lruIterator.hasNext() && toEvict.size < EVICT_COUNT) {
                    val key = lruIterator.next()
                    val entry = audioCache[key]
                    if (entry != null && !entry.protected) {
                        toEvict.add(key)
                        lruIterator.remove()
                        Log.d(TAG, "LRU evicted (lru): ${key.take(30)}...")
                    }
                }
            }

            // 执行删除
            if (toEvict.isNotEmpty()) {
                toEvict.forEach { key ->
                    audioCache.remove(key)
                }
                Log.i(TAG, "LRU eviction: removed ${toEvict.size} entries, cache size now ${audioCache.size}/${MAX_CACHE_SIZE}")
            }
        }
    }

    /**
     * 更新条目的访问时间，并将其移到 LRU 队列尾部（标记为"最近使用"）
     * 在 lruLock 保护下执行，保证 remove+offer 的原子性
     */
    private fun touchAccess(key: String) {
        synchronized(lruLock) {
            lruQueue.remove(key)
            lruQueue.offer(key)
            audioCache[key]?.lastAccessTime = System.nanoTime()
        }
    }

    // ==================== 初始化 ====================

    override fun init(callback: ((Boolean) -> Unit)?) {
        if (isInitialized) {
            callback?.invoke(true)
            return
        }
        executor.execute {
            try {
                okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                    .readTimeout(HTTP_READ_TIMEOUT_S, TimeUnit.SECONDS)
                    .writeTimeout(HTTP_WRITE_TIMEOUT_S, TimeUnit.SECONDS)
                    .build()

                initMediaPlayer()

                Log.d(TAG, "CosyVoice TTS (HTTP Queue) initialized")
                isInitialized = true
                mainHandler.post { callback?.invoke(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                mainHandler.post { callback?.invoke(false) }
            }
        }
    }

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnCompletionListener {
                Log.d(TAG, "MediaPlayer playback completed")
                onSentenceComplete()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                onPlaybackError()
                true
            }
        }
    }

    // ==================== 整句播报（清空队列重新开始） ====================

    override fun speak(text: String, sessionId: Long, callback: TtsManager.TtsStateCallback?) {
        if (!isInitialized) {
            Log.e(TAG, "Engine not initialized")
            callback?.onError(-1, sessionId)
            return
        }
        stateCallback = callback
        currentSessionId = sessionId
        Log.d(TAG, "speak: sessionId=$sessionId, text length=${text.length}")

        executor.execute {
            // 整句播报：清空队列，重新开始
            stopInternal()
            hasStarted.set(false)
            enqueueSentences(text, sessionId)
            startPlaybackIfNeeded()
        }
    }

    // ==================== 流式播报（追加到队列） ====================

    override fun setStateCallback(callback: TtsManager.TtsStateCallback?) {
        stateCallback = callback
    }

    override fun speakChunk(text: String, isFirst: Boolean, sessionId: Long) {
        if (text.isBlank()) {
            Log.w(TAG, "speakChunk: text is blank, skipping")
            return
        }
        Log.i(TAG, "speakChunk: sessionId=$sessionId, isFirst=$isFirst, text=[$text]")

        executor.execute {
            if (isFirst) {
                // 首句：清空旧队列，设置新会话ID（与 SystemTtsEngine QUEUE_FLUSH 行为一致）
                currentSessionId = sessionId
                stopInternal()
                hasStarted.set(false)
            }
            // 流式模式：追加到队列，不打断当前播放
            enqueueSentences(text, sessionId)
            startPlaybackIfNeeded()
        }
    }

    override fun endStream() {
        // 标记流结束（当前实现不需要特殊处理，队列播完自然结束）
        Log.d(TAG, "endStream: stream ended, ${playQueue.size} sentences in queue")
    }

    // ==================== 预缓存（公开API） ====================

    /**
     * 切换TTS音色
     * 切换后新合成的音频使用新音色，已缓存的音频按音色隔离不会串
     *
     * @param voiceId 音色ID，如 longdaiyu_v3、longanyang 等
     */
    fun setVoice(voiceId: String) {
        if (voiceId.isBlank()) {
            Log.w(TAG, "setVoice: empty voiceId, ignored")
            return
        }
        if (currentVoiceId == voiceId) {
            Log.d(TAG, "Voice already set to: $voiceId")
            return
        }
        currentVoiceId = voiceId
        Log.i(TAG, "TTS voice changed to: $voiceId")
        // 注意：不清空缓存，因为缓存key带音色前缀，不同音色互不干扰
        // 切换后新的 preload 和 speak 会用新音色合成新的缓存条目
    }

    /**
     * 预合成并缓存文本（不播放）
     * 用于提前合成问候语等常用文本，播放时秒开
     * 使用音色快照：在调用时锁定当前音色，避免并发切换导致串音
     *
     * @param text 要预合成的文本
     */
    fun preload(text: String) {
        if (!isInitialized || text.isBlank()) {
            Log.w(TAG, "preload: skipped (initialized=$isInitialized, text blank=${text.isBlank()})")
            return
        }
        // 音色快照：在调用时立即捕获当前音色，后台任务使用此快照
        val voiceSnapshot = currentVoiceId
        Log.d(TAG, "preload: scheduling preload for \"${text.take(30)}...\" (voice=$voiceSnapshot)")
        preloadExecutor.execute {
            try {
                val sentences = splitSentences(text)
                sentences.forEach { sentence ->
                    val cacheKey = getCacheKey(sentence, voiceSnapshot)
                    if (!audioCache.containsKey(cacheKey)) {
                        // 预缓存的句子标记为 protected，LRU 淘汰时保留
                        synthesizeSentence(sentence, protected = true, voiceId = voiceSnapshot)
                    }
                }
                Log.d(TAG, "preload: completed for \"${text.take(30)}...\" (voice=$voiceSnapshot)")
            } catch (e: Exception) {
                Log.w(TAG, "preload failed", e)
            }
        }
    }

    /**
     * 检查文本是否已预加载完成（当前音色下）
     */
    fun isPreloaded(text: String): Boolean {
        if (text.isBlank()) return false
        val sentences = splitSentences(text)
        return sentences.all { audioCache.containsKey(getCacheKey(it)) }
    }

    // ==================== 队列管理 ====================

    /**
     * 把文本分割成句子，加入播放队列
     *
     * 溢出策略：DROP_OLDEST —— 队列满时丢弃最旧的句子，保留最新的。
     * 这样即使 LLM 流式输出很快，队列最多积压 PLAY_QUEUE_CAPACITY 句，
     * 打断后不会有大量旧内容残留播放。
     *
     * @param text 要分割的文本
     * @param sessionId 会话ID，绑定到每个队列句子
     */
    private fun enqueueSentences(text: String, sessionId: Long) {
        val sentences = splitSentences(text)
        Log.d(TAG, "Enqueue ${sentences.size} sentences, sessionId=$sessionId, queue size was ${playQueue.size}")
        sentences.forEach { sentence ->
            val item = QueuedSentence(sentence, sessionId)
            // DROP_OLDEST：队列满时丢弃最旧的，直到能放入新句子
            while (!playQueue.offer(item)) {
                val dropped = playQueue.poll()
                if (dropped != null) {
                    Log.w(TAG, "Queue full (capacity=$PLAY_QUEUE_CAPACITY), dropped oldest: " +
                            "sessionId=${dropped.sessionId}, text=${dropped.text.take(20)}...")
                }
            }
            // 预加载：队列里的句子都提前合成
            preloadSentence(sentence)
        }
        Log.d(TAG, "Queue size now: ${playQueue.size}")
    }

    /**
     * 如果当前没在播放，启动播放循环
     */
    private fun startPlaybackIfNeeded() {
        if (isPlaying.get()) {
            Log.d(TAG, "Already playing, skip")
            return
        }
        if (playQueue.isEmpty()) {
            Log.d(TAG, "Queue is empty, nothing to play")
            return
        }

        Log.d(TAG, "Starting playback loop")
        isPlaying.set(true)
        playNextSentence()
    }

    /**
     * 播放队列中的下一句
     *
     * 会话校验：跳过所有 sessionId != currentSessionId 的过期句子，
     * 直到找到匹配的句子或队列为空。
     */
    private fun playNextSentence() {
        // 跳过所有过期会话的句子
        var sentence: QueuedSentence?
        while (true) {
            sentence = playQueue.poll()
            if (sentence == null) {
                Log.d(TAG, "Queue is empty, playback finished")
                isPlaying.set(false)
                onAllComplete()
                return
            }
            if (sentence.sessionId == currentSessionId) {
                break // 找到当前会话的句子
            }
            Log.w(TAG, "Skip expired sentence: sessionId=${sentence.sessionId}, current=$currentSessionId, text=${sentence.text.take(20)}...")
            // 继续 poll 下一个
        }

        val validSentence = sentence!!
        playingSessionId = validSentence.sessionId
        Log.d(TAG, "Playing next sentence: ${validSentence.text.take(30)}..., sessionId=${validSentence.sessionId}")
        engineState = STATE_SYNTHESIZING

        executor.execute {
            // 通过 synthesizeSentence 获取 URL（自带缓存命中 + 过期检查 + 重新合成）
            val audioUrl = synthesizeSentence(validSentence.text)

            if (audioUrl == null) {
                Log.e(TAG, "Sentence synthesis failed, skipping: ${validSentence.text.take(30)}")
                // 合成失败，跳过这句，继续下一句
                playNextSentence()
                return@execute
            }

            // 播放
            playAudio(audioUrl)
        }
    }

    // ==================== 文本分割 ====================

    /**
     * 分割文本为句子列表
     */
    private fun splitSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val regex = Regex("[^。！？；\\n]+[。！？；\\n]?")
        regex.findAll(text).forEach { matchResult ->
            val sentence = matchResult.value.trim()
            if (sentence.isNotEmpty()) {
                if (sentence.length > MAX_CHARS_PER_SENTENCE) {
                    sentences.addAll(splitLongSentence(sentence))
                } else {
                    sentences.add(sentence)
                }
            }
        }
        // 处理最后没有标点的句子
        val lastChar = text.lastOrNull()
        if (lastChar != null && lastChar !in "。！？；\n") {
            val lastSentence = text.substringAfterLast('。').substringAfterLast('！')
                .substringAfterLast('？').substringAfterLast('；').substringAfterLast('\n').trim()
            if (lastSentence.isNotEmpty() && sentences.lastOrNull() != lastSentence) {
                if (lastSentence.length > MAX_CHARS_PER_SENTENCE) {
                    sentences.addAll(splitLongSentence(lastSentence))
                } else {
                    sentences.add(lastSentence)
                }
            }
        }
        return sentences
    }

    /**
     * 按字数分割长句子
     */
    private fun splitLongSentence(sentence: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = sentence

        while (remaining.length > MAX_CHARS_PER_SENTENCE) {
            var splitIndex = MAX_CHARS_PER_SENTENCE
            for (i in MAX_CHARS_PER_SENTENCE downTo MAX_CHARS_PER_SENTENCE / 2) {
                if (i < remaining.length && remaining[i] in "，、, ") {
                    splitIndex = i + 1
                    break
                }
            }
            result.add(remaining.substring(0, splitIndex).trim())
            remaining = remaining.substring(splitIndex).trim()
        }
        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }
        return result
    }

    // ==================== 合成逻辑 ====================

    /**
     * 合成单句，返回音频URL
     *
     * @param text 文本内容
     * @param protected 是否受保护（true=预缓存，LRU不淘汰；false=动态合成，可淘汰）
     * @param voiceId 音色ID（null则用当前音色）
     * @return 音频URL，失败返回null
     */
    private fun synthesizeSentence(text: String, protected: Boolean = false, voiceId: String? = null): String? {
        val actualVoiceId = voiceId ?: currentVoiceId
        val cacheKey = getCacheKey(text, voiceId)

        // 先查缓存
        val cached = audioCache[cacheKey]
        if (cached != null) {
            // 检查是否过期
            val now = System.currentTimeMillis()
            if (cached.expiresAt > 0 && cached.expiresAt < now) {
                Log.d(TAG, "Cache expired: ${text.take(20)}... (expiresAt=${cached.expiresAt}, now=$now)")
                // 过期了，从缓存和LRU队列中移除，重新合成
                synchronized(lruLock) {
                    audioCache.remove(cacheKey)
                    lruQueue.remove(cacheKey)
                }
            } else {
                Log.d(TAG, "Sentence hit cache: ${text.take(20)}... (voice=$actualVoiceId, protected=${cached.protected})")
                touchAccess(cacheKey)
                return cached.url
            }
        }

        Log.d(TAG, "Synthesizing: ${text.take(30)}... (voice=$actualVoiceId, protected=$protected)")
        return try {
            val requestBody = TtsRequest(
                model = Constants.COSYVOICE_V35_MODEL,
                input = TtsRequest.Input(text = text),
                parameters = TtsRequest.Parameters(
                    voice = actualVoiceId,
                    textType = "PlainText",
                    format = "mp3"
                )
            )
            val jsonBody = gson.toJson(requestBody)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(Constants.CLOUD_TTS_HTTP_URL)
                .addHeader("Authorization", "Bearer ${Constants.CLOUD_TTS_APP_KEY}")
                .post(jsonBody.toRequestBody(mediaType))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP failed: code=${response.code}")
                    return null
                }

                val ttsResponse = gson.fromJson(responseBody, TtsResponse::class.java)
                val audioUrl = ttsResponse.output?.audio?.url
                val expiresAt = ttsResponse.output?.audio?.expiresAt ?: 0L

                if (audioUrl.isNullOrEmpty()) {
                    Log.e(TAG, "No audio URL in response")
                    return null
                }

                // 存入缓存（加锁保证原子性）
                synchronized(lruLock) {
                    evictIfNeeded()
                    audioCache[cacheKey] = CacheEntry(
                        url = audioUrl,
                        expiresAt = expiresAt,
                        protected = protected
                    )
                    lruQueue.offer(cacheKey)
                }
                Log.d(TAG, "Synthesis success: ${text.take(20)}... (voice=$actualVoiceId, expiresAt=$expiresAt)")
                audioUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            null
        }
    }

    /**
     * 后台预加载句子（用于播放队列中的下一句，提前合成减少等待）
     * 注意：这些是对话中动态产生的句子，不标记为 protected，可被 LRU 淘汰
     */
    private fun preloadSentence(text: String, voiceId: String? = null) {
        val cacheKey = getCacheKey(text, voiceId)
        if (audioCache.containsKey(cacheKey)) return
        preloadExecutor.execute {
            try {
                synthesizeSentence(text, protected = false, voiceId = voiceId)
            } catch (e: Exception) {
                Log.w(TAG, "Preload sentence failed", e)
            }
        }
    }

    // ==================== 播放逻辑 ====================

    /**
     * 播放音频
     */
    private fun playAudio(audioUrl: String) {
        try {
            engineState = STATE_PLAYING

            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(audioUrl)
            mediaPlayer?.prepareAsync()

            mediaPlayer?.setOnPreparedListener {
                Log.d(TAG, "MediaPlayer prepared, starting playback, sessionId=$playingSessionId")
                it.start()

                // 第一次开始播放时触发 onStart
                if (!hasStarted.getAndSet(true)) {
                    val sid = playingSessionId
                    mainHandler.post {
                        ModuleStateManager.onTtsStartSpeaking()
                        stateCallback?.onStart(sid)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Play audio failed", e)
            // 播放失败，继续下一句
            playNextSentence()
        }
    }

    /**
     * 单句播放完成
     *
     * P3 句间倾听窗：每句播完后不立即播下一句，而是停顿 800~1200ms。
     * 停顿期间 engineState=IDLE，isSpeaking()=false，上层 VAD 可以检测用户语音。
     * - 检测到用户语音 → interruptInterSentence() → 清空剩余队列 → onDone
     * - 无语音 → 延迟结束 → 继续播下一句
     */
    private fun onSentenceComplete() {
        Log.d(TAG, "Sentence complete, queue size=${playQueue.size}")
        // 句间暂停：标记为非播放状态，让 shouldListen() 返回 true
        engineState = STATE_IDLE

        if (playQueue.isEmpty()) {
            // 队列空了，全部播完
            isPlaying.set(false)
            onAllComplete()
            return
        }

        // 还有下一句：进入句间倾听窗
        val pauseMs = (INTER_SENTENCE_PAUSE_MIN_MS..INTER_SENTENCE_PAUSE_MAX_MS).random()
        interSentenceInterrupted = false
        val remaining = playQueue.size
        Log.d(TAG, "Inter-sentence listening window: ${pauseMs}ms, remaining=$remaining")
        interSentenceListener?.onInterSentenceStart(remaining)

        // 取消之前的延迟任务（防止重入）
        interSentenceRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            interSentenceRunnable = null
            // 检查是否已经被停止（stop() 已调用）
            if (!isPlaying.get() || currentSessionId == 0L) {
                Log.d(TAG, "Inter-sentence: playback already stopped, skip")
                interSentenceListener?.onInterSentenceEnd(interrupted = false)
                return@Runnable
            }
            if (interSentenceInterrupted) {
                // 被用户打断：清空剩余队列，结束播放
                Log.i(TAG, "Inter-sentence: interrupted by user, clearing remaining queue")
                interSentenceListener?.onInterSentenceEnd(interrupted = true)
                stopInternal(clearQueue = true)
                isPlaying.set(false)
                onAllComplete()
            } else {
                // 正常继续：播下一句
                Log.d(TAG, "Inter-sentence: no user speech, continue to next sentence")
                interSentenceListener?.onInterSentenceEnd(interrupted = false)
                playNextSentence()
            }
        }
        interSentenceRunnable = runnable
        mainHandler.postDelayed(runnable, pauseMs)
    }

    /**
     * 全部播放完成
     * 注意：不清空缓存！缓存由 LRU 机制自动管理
     */
    private fun onAllComplete() {
        val sid = playingSessionId
        Log.d(TAG, "All sentences complete, sessionId=$sid")
        engineState = STATE_IDLE
        hasStarted.set(false)
        playingSessionId = 0L
        mainHandler.post {
            ModuleStateManager.onTtsFinishSpeaking()
            stateCallback?.onDone(sid)
        }
    }

    // ==================== 错误处理 ====================

    private fun onPlaybackError() {
        Log.e(TAG, "Playback error")
        // 跳过当前句，继续下一句
        if (playQueue.isEmpty()) {
            isPlaying.set(false)
            onAllComplete()
        } else {
            playNextSentence()
        }
    }

    private fun onSynthesisError(msg: String) {
        val sid = playingSessionId
        Log.e(TAG, "Synthesis error: $msg, sessionId=$sid")
        engineState = STATE_IDLE
        isPlaying.set(false)
        hasStarted.set(false)
        playingSessionId = 0L
        playQueue.clear()
        // 注意：不清空缓存！出错不影响已有缓存
        mainHandler.post {
            ModuleStateManager.onTtsError()
            stateCallback?.onError(-1, sid)
        }
    }

    // ==================== 内部停止 ====================

    /**
     * 内部停止播放
     *
     * @param clearQueue 是否清空播放队列
     *                   - true：打断/新播报开始，清空所有待播句子
     *                   - false：仅停止当前播放，保留队列（预留场景）
     */
    private fun stopInternal(clearQueue: Boolean = true) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.reset()
        } catch (e: Exception) {
            Log.w(TAG, "stopInternal exception", e)
        }
        // 取消句间倾听窗延迟任务
        interSentenceRunnable?.let { mainHandler.removeCallbacks(it) }
        interSentenceRunnable = null
        interSentenceInterrupted = false
        engineState = STATE_IDLE
        isPlaying.set(false)
        if (clearQueue) {
            playQueue.clear()
            Log.d(TAG, "stopInternal: queue cleared")
        } else {
            Log.d(TAG, "stopInternal: queue preserved (${playQueue.size} sentences)")
        }
        // 注意：不清空缓存！停止不影响缓存，下次播放还能用
    }

    // ==================== TtsEngine 接口实现 ====================

    override fun stop(clearQueue: Boolean) {
        val wasSpeaking = engineState != STATE_IDLE || isPlaying.get()
        val stoppedSessionId = currentSessionId
        currentSessionId = 0L // 立即失效，后续队列句子全部跳过
        Log.d(TAG, "stop: wasSpeaking=$wasSpeaking, clearQueue=$clearQueue, stoppedSessionId=$stoppedSessionId")
        executor.execute {
            stopInternal(clearQueue)
            if (wasSpeaking) {
                mainHandler.post {
                    Log.d(TAG, "TTS stopped manually, sessionId=$stoppedSessionId")
                    ModuleStateManager.onTtsFinishSpeaking()
                    stateCallback?.onDone(stoppedSessionId)
                }
            }
        }
    }

    override fun isSpeaking(): Boolean {
        return engineState != STATE_IDLE ||
               isPlaying.get() ||
               mediaPlayer?.isPlaying == true
    }

    override fun isAvailable(): Boolean = isInitialized

    // ========== 句间倾听窗（P3）接口实现 ==========

    override fun setOnInterSentenceListener(listener: TtsEngine.OnInterSentenceListener?) {
        interSentenceListener = listener
    }

    override fun interruptInterSentence() {
        if (interSentenceRunnable != null) {
            Log.i(TAG, "interruptInterSentence: user spoke during listening window")
            interSentenceInterrupted = true
        }
    }

    override fun release() {
        executor.execute {
            stopInternal()
            mediaPlayer?.release()
            mediaPlayer = null
            executor.shutdown()
            preloadExecutor.shutdown()
            Log.d(TAG, "CosyVoiceTtsEngine (HTTP Queue) released")
        }
    }
}
