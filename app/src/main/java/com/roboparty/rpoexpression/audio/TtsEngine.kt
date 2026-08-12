/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

/**
 * TTS 引擎抽象接口
 *
 * 统一云端 TTS（CosyVoice）和系统 TTS 的对外行为，
 * TtsManager 内部持有此接口的实现，对外接口完全不变。
 */
interface TtsEngine {

    /**
     * 初始化引擎（异步）
     * @param callback 初始化结果回调
     */
    fun init(callback: ((Boolean) -> Unit)? = null)

    /**
     * 整句合成播报
     * @param text 要播报的文本
     * @param sessionId 会话唯一ID，用于回调校验
     * @param callback 播报状态回调
     */
    fun speak(text: String, sessionId: Long, callback: TtsManager.TtsStateCallback? = null)

    /**
     * 设置状态回调（流式模式用）
     */
    fun setStateCallback(callback: TtsManager.TtsStateCallback?)

    /**
     * 流式播报：喂入一个文本块
     * @param text 一个句子或一段文本
     * @param isFirst 是否是本轮第一句
     * @param sessionId 会话唯一ID，用于队列绑定和回调校验
     */
    fun speakChunk(text: String, isFirst: Boolean, sessionId: Long)

    /**
     * 标记本轮流式播报的输入已结束
     */
    fun endStream()

    /**
     * 停止播报
     *
     * @param clearQueue 是否清空播放队列
     *                   - true（默认）：打断场景，清空所有待播句子，无残留
     *                   - false：仅停止当前播放，保留队列（预留场景）
     */
    fun stop(clearQueue: Boolean = true)

    /**
     * 是否正在播报
     */
    fun isSpeaking(): Boolean

    /**
     * 引擎是否可用
     */
    fun isAvailable(): Boolean

    /**
     * 释放资源
     */
    fun release()

    /**
     * 句间倾听窗回调（P3：句间打断机制）
     *
     * 在每句播放完成、下一句开始前的停顿期间触发。
     * 上层可在此期间监听用户语音，检测到则调用 interruptInterSentence() 打断剩余队列。
     */
    interface OnInterSentenceListener {
        /** 进入句间倾听窗 */
        fun onInterSentenceStart(remainingCount: Int) {}
        /** 句间倾听窗结束（interrupted=true 表示被用户打断，false 表示正常继续播放） */
        fun onInterSentenceEnd(interrupted: Boolean) {}
    }

    /**
     * 设置句间倾听窗回调
     */
    fun setOnInterSentenceListener(listener: OnInterSentenceListener?) {}

    /**
     * 打断当前句间倾听窗（用户在停顿期间说话时调用）
     * 调用后剩余队列被清空，触发 onDone 回调
     */
    fun interruptInterSentence() {}
}
