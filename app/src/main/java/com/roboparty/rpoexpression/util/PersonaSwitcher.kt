/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.util

import com.roboparty.rpoexpression.llm.LlmManager

/**
 * 人格切换指令匹配器
 *
 * 功能：判断用户说的话是不是"切换人格"的指令
 * 规则：
 * 1. 整句话 ≤ 8个字才可能是切换指令，长句子直接返回null
 * 2. 模糊匹配关键词，包含就算
 * 3. 匹配男款关键词 → 返回 ALIN
 * 4. 匹配女款关键词 → 返回 LUOBO
 * 5. 匹配模糊切换关键词 → 返回当前人格的对立面
 * 6. 都不匹配 → 返回 null
 *
 * 设计目的：避免"我喜欢阿冷"、"阿冷是谁"这种长句子误触发切换
 */
object PersonaSwitcher {

    /** 切换指令最大长度，超过这个长度的句子不认为是切换指令 */
    private const val MAX_COMMAND_LENGTH = 8

    /** 男款（阿冷）切换关键词 */
    private val MALE_KEYWORDS = listOf(
        "阿冷",
        "换成阿冷",
        "切换阿冷",
        "变成阿冷",
        "叫阿冷来",
        "切换男款",
        "换成男款",
        "男声音",
        "男声",
        "换男的",
        "换男生"
    )

    /** 女款（萝卜）切换关键词 */
    private val FEMALE_KEYWORDS = listOf(
        "萝卜",
        "换成萝卜",
        "切换萝卜",
        "变成萝卜",
        "叫萝卜来",
        "切换女款",
        "换成女款",
        "女声音",
        "女声",
        "换女的",
        "换女生"
    )

    /** 模糊切换关键词（切换到另一个人格） */
    private val SWITCH_OTHER_KEYWORDS = listOf(
        "换个声音",
        "换个人",
        "换一下",
        "换人",
        "换一个",
        "换个",
        "换声",
        "换个声"
    )

    /**
     * 匹配切换指令
     *
     * @param text 用户说的话（原始文本，不需要预处理）
     * @param currentPersona 当前人格，用于模糊切换指令时返回对立面
     * @return 匹配到的人格，null表示不是切换指令
     */
    fun matchSwitchCommand(text: String, currentPersona: LlmManager.Persona): LlmManager.Persona? {
        if (text.isBlank()) return null

        // 第一步：长度过滤，长句子直接排除
        // 去掉所有空白字符后再判断长度，避免"换 成 阿 冷"这种空格干扰
        val cleanText = text.replace(Regex("\\s"), "")
        if (cleanText.length > MAX_COMMAND_LENGTH) {
            return null
        }

        // 第二步：统一转小写（应对英文/拼音混入的情况）
        val lowerText = cleanText.lowercase()

        // 第三步：先匹配男款（阿冷）
        for (keyword in MALE_KEYWORDS) {
            if (lowerText.contains(keyword.lowercase())) {
                return LlmManager.Persona.ALIN
            }
        }

        // 第四步：再匹配女款（萝卜）
        for (keyword in FEMALE_KEYWORDS) {
            if (lowerText.contains(keyword.lowercase())) {
                return LlmManager.Persona.LUOBO
            }
        }

        // 第五步：匹配模糊切换指令（切换到另一个人格）
        for (keyword in SWITCH_OTHER_KEYWORDS) {
            if (lowerText.contains(keyword.lowercase())) {
                // 返回当前人格的对立面
                return when (currentPersona) {
                    LlmManager.Persona.LUOBO -> LlmManager.Persona.ALIN
                    LlmManager.Persona.ALIN -> LlmManager.Persona.LUOBO
                }
            }
        }

        // 都不匹配
        return null
    }
}
