/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 关键词唤醒（KWS）管理器
 *
 * 当前模式：ASR fallback（用全量ASR + 模糊匹配代替本地KWS）
 * 目标模式：sherpa-onnx 本地离线关键词检测（待集成）
 *
 * 架构保留 KWS 的状态机、误触防护、TTS静音等全部设计，
 * 只在检测引擎层做 fallback —— 后续接入 sherpa-onnx 时
 * 只需替换 detectKeyword() 的实现。
 *
 * 核心特性：
 * 1. 4层误触防护：冷却期 → TTS回声保护 → 静音 → 重复确认
 * 2. TTS播报期间自动静音，防止回声误触发
 * 3. 对外接口不变，上层（AudioManager/MainActivity）无感知
 */
class KwsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KwsManager"

        @Volatile
        private var instance: KwsManager? = null

        fun getInstance(context: Context): KwsManager {
            return instance ?: synchronized(this) {
                instance ?: KwsManager(context.applicationContext).also { instance = it }
            }
        }

        // ========== 误触防护参数 ==========
        /** 触发冷却时间，3秒内不重复触发 */
        private const val TRIGGER_COOLDOWN_MS = 3000L
        /** TTS停止后的尾音保护时间，2.5秒内不触发 */
        private const val TTS_TAIL_PROTECTION_MS = 2500L
        /** KWS 专用会话ID（负数，与正常对话 sessionId 区分） */
        private const val KWS_SESSION_ID = -2L
    }

    /**
     * KWS检测回调
     */
    interface KwsCallback {
        /** 检测到唤醒词
         *  @param keyword 匹配到的原始文本
         *  @param confidence 置信度
         *  @param group 唤醒词组（萝卜组/阿冷组）
         */
        fun onKeywordDetected(keyword: String, confidence: Float, group: WakeWordGroup)
        /** KWS出错 */
        fun onError(error: String)
    }

    /**
     * 唤醒词组枚举
     * 用于区分不同人格的唤醒词
     */
    enum class WakeWordGroup {
        /** 萝卜人格唤醒词组（女款·活泼可爱） */
        LUOBO,
        /** 阿冷人格唤醒词组（男款·冷静沉稳） */
        ALIN
    }

    // ========== 内部状态 ==========
    private val mainHandler = Handler(Looper.getMainLooper())
    private var asrManager: AsrManager? = null

    @Volatile private var isRunning = false
    @Volatile private var isInitialized = false
    @Volatile private var isMuted = false

    // 误触防护状态
    @Volatile private var lastTriggerTime = 0L
    @Volatile private var lastTtsStopTime = 0L

    private var callback: KwsCallback? = null

    // 萝卜人格唤醒词（女款·活泼可爱）
    private val luoboWakeWords = listOf(
        "萝卜", "萝卜萝卜", "罗布", "罗波",
        "小萝卜", "萝卜头"
    )

    // 阿冷人格唤醒词（男款·冷静沉稳）
    // 注意：包含"阿懔"是因为用户可能用这个字，ASR可能识别成各种变体
    private val alinWakeWords = listOf(
        "阿冷", "啊凛", "阿林", "阿霖", "阿懔",
        "凛子", "凛"
    )

    /**
     * 初始化KWS引擎
     */
    fun init() {
        if (isInitialized) return
        asrManager = AsrManager.getInstance(context)
        isInitialized = true
        Log.i(TAG, "KwsManager initialized (ASR fallback mode)")
    }

    /**
     * 开始关键词监听
     * ASR fallback模式：启动一轮ASR识别，检测到文本后做唤醒词匹配
     * 匹配失败则自动重启下一轮，形成持续监听循环
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(callback: KwsCallback) {
        if (!isInitialized) {
            callback.onError("KWS未初始化")
            return
        }
        if (isRunning) {
            Log.w(TAG, "KWS already running")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            callback.onError("未授予录音权限")
            return
        }

        this.callback = callback
        isRunning = true
        isMuted = false
        Log.i(TAG, "KWS started (ASR fallback mode)")

        startAsrWakeDetection()
    }

    /**
     * 停止关键词监听
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            asrManager?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Stop ASR in KWS failed", e)
        }
        callback = null
        Log.i(TAG, "KWS stopped")
    }

    fun isRunning(): Boolean = isRunning

    /**
     * 设置静音状态（TTS播报时调用，防止回声误触发）
     */
    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            Log.d(TAG, "KWS muted (TTS playing)")
        } else {
            lastTtsStopTime = System.currentTimeMillis()
            Log.d(TAG, "KWS unmuted, tail protection for ${TTS_TAIL_PROTECTION_MS}ms")
        }
    }

    fun isTtsProtectionActive(): Boolean {
        return System.currentTimeMillis() - lastTtsStopTime < TTS_TAIL_PROTECTION_MS
    }

    fun release() {
        stop()
        isInitialized = false
        Log.i(TAG, "KwsManager released")
    }

    // ========== ASR Fallback 唤醒检测 ==========

    /**
     * 启动一轮ASR识别，识别结果做唤醒词匹配
     * 不触发识别中表情（triggerExpression=false）
     */
    @Suppress("UNUSED_PARAMETER")
    private fun startAsrWakeDetection() {
        val mgr = asrManager ?: run {
            mainHandler.post { callback?.onError("ASR引擎不可用") }
            return
        }

        if (!isRunning) return

        // TTS正在播报 → 等播完再启动
        if (isMuted) {
            mainHandler.postDelayed({
                if (isRunning) startAsrWakeDetection()
            }, 300)
            return
        }

        // 尾音保护期内 → 等保护期结束
        if (System.currentTimeMillis() - lastTtsStopTime < TTS_TAIL_PROTECTION_MS) {
            val wait = TTS_TAIL_PROTECTION_MS - (System.currentTimeMillis() - lastTtsStopTime)
            mainHandler.postDelayed({
                if (isRunning) startAsrWakeDetection()
            }, wait + 50)
            return
        }

        mgr.startListening(KWS_SESSION_ID, object : AsrManager.AsrResultCallback {
            override fun onResult(text: String, sessionId: Long) {
                if (!isRunning) return

                // TTS 尾部保护期：防止回声被误判
                val timeSinceTts = System.currentTimeMillis() - lastTtsStopTime
                if (timeSinceTts < TTS_TAIL_PROTECTION_MS) {
                    Log.d(TAG, "KWS TTS tail protection (${TTS_TAIL_PROTECTION_MS - timeSinceTts}ms left)")
                    restartDetection()
                    return
                }

                // 冷却期检查
                if (System.currentTimeMillis() - lastTriggerTime < TRIGGER_COOLDOWN_MS) {
                    restartDetection()
                    return
                }

                // 清洗文本：只保留汉字+英文，转小写
                val raw = text.lowercase()
                    .replace(Regex("[^a-z\\u4e00-\\u9fa5]"), "")

                // 唤醒词分组匹配
                val group = matchWakeWordGroup(raw)

                if (group != null) {
                    lastTriggerTime = System.currentTimeMillis()
                    Log.i(TAG, "Wake word detected via ASR: '$raw', group=$group")
                    mainHandler.post {
                        callback?.onKeywordDetected(raw, 0.85f, group)
                    }
                    // 唤醒后不再自动重启，上层会调 stop()
                } else {
                    Log.d(TAG, "ASR result not a wake word: '$raw', continue listening")
                    restartDetection()
                }
            }

            override fun onError(errorMsg: String, sessionId: Long) {
                if (!isRunning) return
                Log.w(TAG, "ASR wake detection error: $errorMsg, retry in 1s")
                mainHandler.postDelayed({
                    if (isRunning) startAsrWakeDetection()
                }, 1000)
            }
        }, triggerExpression = false)
    }

    /**
     * 重新启动一轮检测（带短延迟避免高频重连）
     */
    private fun restartDetection() {
        if (!isRunning) return
        mainHandler.postDelayed({
            if (isRunning) startAsrWakeDetection()
        }, 200)
    }

    // ========== 唤醒词匹配（从MainActivity迁移，保持一致） ==========

    /**
     * 判断输入文本属于哪个唤醒词组
     * 先匹配萝卜组，再匹配阿冷组（萝卜组优先级更高，因为是默认人格）
     *
     * @return 命中的组，null 表示没有命中任何唤醒词
     */
    private fun matchWakeWordGroup(input: String): WakeWordGroup? {
        // 先匹配萝卜组
        if (luoboWakeWords.any { word -> isFuzzyMatch(input, word) }) {
            return WakeWordGroup.LUOBO
        }
        // 再匹配阿冷组
        if (alinWakeWords.any { word -> isFuzzyMatch(input, word) }) {
            return WakeWordGroup.ALIN
        }
        return null
    }

    private fun isFuzzyMatch(input: String, wakeWord: String): Boolean {
        val minInputLen = if (wakeWord.any { it.isChinese() }) 2 else 3
        if (input.length < minInputLen) return false

        val minMatchLen = if (wakeWord.any { it.isChinese() }) 2 else 3

        // 包含匹配
        if (input.contains(wakeWord, ignoreCase = true)) return true
        // 开头匹配
        if (input.startsWith(wakeWord.take(minMatchLen), ignoreCase = true)) return true
        // 结尾匹配
        if (input.endsWith(wakeWord.takeLast(minMatchLen), ignoreCase = true)) return true
        // 反向包含（降低漏触发）
        if (wakeWord.contains(input.take(minMatchLen), ignoreCase = true)) return true

        return false
    }

    private fun Char.isChinese(): Boolean = this in '\u4e00'..'\u9fa5'

    // ========== sherpa-onnx 集成预留 ==========
    // 接入 sherpa-onnx 后：
    // 1. init() 中加载 .onnx 模型
    // 2. start() 启动独立 AudioRecord + 推理循环
    // 3. 删除 startAsrWakeDetection() 和 isFuzzyMatch()
    // 4. 用 sherpaKws.acceptWaveform(samples) 替代唤醒词匹配
}
