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
     * @param callback 播报状态回调
     */
    fun speak(text: String, callback: TtsManager.TtsStateCallback? = null)

    /**
     * 设置状态回调（流式模式用）
     */
    fun setStateCallback(callback: TtsManager.TtsStateCallback?)

    /**
     * 流式播报：喂入一个文本块
     * @param text 一个句子或一段文本
     * @param isFirst 是否是本轮第一句
     */
    fun speakChunk(text: String, isFirst: Boolean)

    /**
     * 标记本轮流式播报的输入已结束
     */
    fun endStream()

    /**
     * 停止播报并清空队列
     */
    fun stop()

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
}
