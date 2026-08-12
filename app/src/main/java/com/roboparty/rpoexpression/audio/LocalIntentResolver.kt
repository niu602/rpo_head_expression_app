/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.audio

import android.util.Log

/**
 * 本地意图识别器（P1：快速响应路径）
 *
 * 设计目标：
 * - 常用指令无需走 LLM，本地关键词匹配后直接 TTS 响应，延迟 < 300ms
 * - 预合成后延迟 < 100ms
 * - 与 LLM 流程解耦：匹配成功直接返回，未匹配走原有 LLM 流程
 *
 * 匹配策略：
 * 1. 文本清洗：去除标点、空格、统一小写
 * 2. 关键词精确匹配 + 包含匹配
 * 3. 优先级：STOP_SPEAKING > 其他意图（停止指令优先级最高）
 *
 * 第一期支持 5 种意图，后续可扩展。
 */
object LocalIntentResolver {

    private const val TAG = "LocalIntentResolver"

    /**
     * 意图识别结果
     *
     * @param matched 是否匹配到本地意图
     * @param intent 匹配到的意图类型
     * @param responseText 响应文本（STOP_SPEAKING 为空，表示静默停止不回复）
     */
    data class IntentResult(
        val matched: Boolean,
        val intent: IntentType,
        val responseText: String = ""
    )

    /**
     * 本地意图类型枚举
     */
    enum class IntentType {
        /** 停止说话：静默停止，不回复 */
        STOP_SPEAKING,
        /** 问候 */
        GREETING,
        /** 自我介绍 */
        WHO_ARE_YOU,
        /** 开始演示 */
        START_DEMO,
        /** 电量查询（响应文本含占位符，需调用方填充实际电量） */
        BATTERY_STATUS,
        /** 未匹配（走 LLM 流程） */
        UNKNOWN
    }

    // ==================== 触发词配置 ====================

    /** 停止说话触发词（优先级最高） */
    private val STOP_KEYWORDS = listOf(
        "停下", "停止", "闭嘴", "别说话了", "别说话", "不要说了",
        "安静", "静音", "别说了", "住口", "打住"
    )

    /** 问候触发词 */
    private val GREETING_KEYWORDS = listOf(
        "你好", "您好", "嗨", "哈喽", "hello", "hi",
        "在吗", "在不在", "有人吗", "在么"
    )

    /** 自我介绍触发词 */
    private val WHO_ARE_YOU_KEYWORDS = listOf(
        "你是谁", "你叫什么", "介绍一下自己", "自我介绍",
        "你是什么", "你叫什么名字", "你是哪位"
    )

    /** 开始演示触发词 */
    private val START_DEMO_KEYWORDS = listOf(
        "开始演示", "演示模式", "进入演示", "启动演示",
        "演示一下", "开始展示", "展示模式"
    )

    /** 电量查询触发词 */
    private val BATTERY_KEYWORDS = listOf(
        "还有多少电", "电量多少", "电量", "还剩多少电",
        "剩多少电", "没电了吗", "电量充足吗", "还有电吗"
    )

    // ==================== 响应文本配置 ====================

    /** 问候响应（第一期统一响应，后续可按人格差异化） */
    private const val GREETING_RESPONSE = "你好呀，我在呢～"

    /** 自我介绍响应 */
    private const val WHO_ARE_YOU_RESPONSE = "我是 RPO 纯眼机器人，一个只有眼睛的可爱机器人头。我可以陪你聊天、识别你的表情、还能做很多有趣的事情哦～"

    /** 开始演示响应 */
    private const val START_DEMO_RESPONSE = "好的，开始演示～"

    /** 电量查询响应模板（{percent} 为占位符，调用方替换为实际电量） */
    private const val BATTERY_RESPONSE_TEMPLATE = "当前电量大约 {percent}%"

    // ==================== 对外 API ====================

    /**
     * 解析用户输入文本，识别本地意图
     *
     * @param text ASR 识别结果
     * @return 意图识别结果，matched=false 表示未匹配，应走 LLM 流程
     */
    fun resolve(text: String): IntentResult {
        if (text.isBlank()) {
            return IntentResult(matched = false, intent = IntentType.UNKNOWN)
        }

        // 文本清洗：去除标点、空格、统一小写
        val cleaned = cleanText(text)
        Log.d(TAG, "resolve: raw=\"$text\", cleaned=\"$cleaned\"")

        // 优先级 1：停止说话（最高优先级，任何时候都应立即响应）
        if (matchAny(cleaned, STOP_KEYWORDS)) {
            Log.i(TAG, "Matched STOP_SPEAKING: \"$text\"")
            return IntentResult(
                matched = true,
                intent = IntentType.STOP_SPEAKING,
                responseText = ""  // 静默停止，不回复
            )
        }

        // 优先级 2：问候
        if (matchAny(cleaned, GREETING_KEYWORDS)) {
            Log.i(TAG, "Matched GREETING: \"$text\"")
            return IntentResult(
                matched = true,
                intent = IntentType.GREETING,
                responseText = GREETING_RESPONSE
            )
        }

        // 优先级 3：自我介绍
        if (matchAny(cleaned, WHO_ARE_YOU_KEYWORDS)) {
            Log.i(TAG, "Matched WHO_ARE_YOU: \"$text\"")
            return IntentResult(
                matched = true,
                intent = IntentType.WHO_ARE_YOU,
                responseText = WHO_ARE_YOU_RESPONSE
            )
        }

        // 优先级 4：开始演示
        if (matchAny(cleaned, START_DEMO_KEYWORDS)) {
            Log.i(TAG, "Matched START_DEMO: \"$text\"")
            return IntentResult(
                matched = true,
                intent = IntentType.START_DEMO,
                responseText = START_DEMO_RESPONSE
            )
        }

        // 优先级 5：电量查询
        if (matchAny(cleaned, BATTERY_KEYWORDS)) {
            Log.i(TAG, "Matched BATTERY_STATUS: \"$text\"")
            return IntentResult(
                matched = true,
                intent = IntentType.BATTERY_STATUS,
                responseText = BATTERY_RESPONSE_TEMPLATE  // 含占位符，调用方填充
            )
        }

        // 未匹配，走 LLM 流程
        Log.d(TAG, "No local intent matched, fallback to LLM: \"$text\"")
        return IntentResult(matched = false, intent = IntentType.UNKNOWN)
    }

    /**
     * 获取所有需要预加载的响应文本
     * 用于 APP 启动时调用 audioManager.preloadTts() 预合成
     *
     * 注意：BATTERY_STATUS 的响应是模板，无法预加载（实际电量动态变化），
     *       因此不在预加载列表中。
     *
     * @return 需要预合成的文本列表
     */
    fun getPreloadTexts(): List<String> = listOf(
        GREETING_RESPONSE,
        WHO_ARE_YOU_RESPONSE,
        START_DEMO_RESPONSE
    )

    /**
     * 填充电量响应模板
     *
     * @param batteryPercent 电量百分比（0-100）
     * @return 填充后的响应文本
     */
    fun fillBatteryResponse(batteryPercent: Int): String {
        return BATTERY_RESPONSE_TEMPLATE.replace("{percent}", batteryPercent.toString())
    }

    // ==================== 内部工具方法 ====================

    /**
     * 文本清洗：去除标点、空格、统一小写
     * 保留中英文和数字，去除其他所有字符
     */
    private fun cleanText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), "")
            .trim()
    }

    /**
     * 检查清洗后的文本是否匹配任意关键词
     *
     * 匹配规则：
     * - 精确匹配：清洗后文本 == 关键词（如 "你好"）
     * - 包含匹配：清洗后文本包含关键词（如 "你好啊" 包含 "你好"）
     *
     * 注意：短关键词（如 "嗨"、"电量"）使用包含匹配可能误判，
     *       但在当前意图集合中风险可控。后续可引入正则或编辑距离优化。
     */
    private fun matchAny(cleanedText: String, keywords: List<String>): Boolean {
        return keywords.any { keyword ->
            val cleanedKeyword = cleanText(keyword)
            cleanedText == cleanedKeyword || cleanedText.contains(cleanedKeyword)
        }
    }
}
