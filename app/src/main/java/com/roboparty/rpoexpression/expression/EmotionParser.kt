/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 情绪标签解析器
 *
 * 解析 LLM 流式输出中的情绪标签前缀，格式：[情绪]文本内容
 * 支持的情绪：happy, confused, surprised, sad, angry, shy, sleepy, singing
 *
 * 使用方式：
 * 1. 在 LLM onDelta 回调中逐块 append 到 EmotionStreamParser
 * 2. 当标签解析完成后，通过 onEmotionParsed 回调获取情绪
 * 3. 当标签后的文本就绪时，通过 onTextReady 回调获取纯文本（喂给 TTS）
 */
object EmotionParser {

    /**
     * 支持的情绪标签列表
     */
    val SUPPORTED_EMOTIONS = listOf(
        "happy", "confused", "surprised", "sad", "angry", "shy", "sleepy", "singing"
    )

    /**
     * 单次解析：从文本中提取情绪标签和纯文本
     * @param raw 原始文本，如 "[happy]嘿嘿，你好呀~"
     * @return 解析结果，emotion 为 null 表示无标签
     */
    data class Parsed(val emotion: String?, val text: String)

    fun parse(raw: String): Parsed {
        val regex = Regex("^\\[(\\w+)\\]\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(raw.trim())
        return if (match != null) {
            Parsed(match.groupValues[1], match.groupValues[2])
        } else {
            Parsed(null, raw)
        }
    }

    /**
     * 将情绪标签映射到表情类型
     * @param emotion 情绪标签字符串（如 "happy"），null 返回 null
     * @return 对应的 ExpressionType，无法映射时返回 null
     */
    fun emotionToExpression(emotion: String?): ExpressionType? = when (emotion) {
        "happy" -> ExpressionType.HAPPY
        "confused" -> ExpressionType.CONFUSED
        "surprised" -> ExpressionType.SURPRISED
        "sad" -> ExpressionType.SAD
        "angry" -> ExpressionType.ANGRY
        "shy" -> ExpressionType.SHY
        "sleepy" -> ExpressionType.SLEEPY
        "singing" -> ExpressionType.HAPPY  // 唱歌时显示开心表情
        else -> null
    }

    /**
     * 流式解析器：用于逐块（delta）喂入 LM 流式输出，自动处理标签边界
     *
     * 使用方式：
     * ```
     * val streamParser = EmotionParser.StreamParser(
     *     onEmotionParsed = { emotion -> expressionManager.requestEmotionExpression(emotion) },
     *     onTextReady = { text -> audioManager.feedStreamChunk(text) }
     * )
     * // LLM onDelta 回调中：
     * streamParser.feed(delta)
     * // LLM onComplete 回调中：
     * streamParser.flush()
     * streamParser.reset()
     * ```
     */
    class StreamParser(
        private val onEmotionParsed: (String) -> Unit,
        private val onTextReady: (String) -> Unit
    ) {
        private val buffer = StringBuilder()
        private var emotionParsed = false
        private var tagClosed = false

        /**
         * 喂入增量文本
         * 在情绪标签未完成前，缓冲文本直到检测到 "]" 或确认无标签
         */
        fun feed(delta: String) {
            if (emotionParsed && tagClosed) {
                // 标签已解析完，后续 delta 直接输出
                onTextReady(delta)
                return
            }

            buffer.append(delta)

            if (!emotionParsed) {
                // 还在等标签
                if (buffer.contains("]")) {
                    // 标签结束
                    val parsed = parse(buffer.toString())
                    emotionParsed = true
                    tagClosed = true

                    // 回调情绪
                    if (parsed.emotion != null) {
                        onEmotionParsed(parsed.emotion)
                    }

                    // 输出标签后的文本
                    if (parsed.text.isNotEmpty()) {
                        onTextReady(parsed.text)
                    }
                } else if (buffer.length > 30 || buffer.contains("\n")) {
                    // 缓冲超过30字符或出现换行，说明没有标签，按纯文本处理
                    emotionParsed = true
                    tagClosed = true
                    onTextReady(buffer.toString())
                }
                // 否则继续缓冲，等下一个 delta
            }
        }

        /**
         * 刷出缓冲区中剩余的文本（LLM onComplete 时调用）
         */
        fun flush() {
            if (!emotionParsed || !tagClosed) {
                if (buffer.isNotEmpty()) {
                    val parsed = parse(buffer.toString())
                    if (!emotionParsed && parsed.emotion != null) {
                        onEmotionParsed(parsed.emotion)
                    }
                    if (parsed.text.isNotEmpty()) {
                        onTextReady(parsed.text)
                    }
                }
                emotionParsed = true
                tagClosed = true
            }
        }

        /**
         * 重置解析器状态，为下一轮对话准备
         */
        fun reset() {
            buffer.clear()
            emotionParsed = false
            tagClosed = false
        }
    }
}
