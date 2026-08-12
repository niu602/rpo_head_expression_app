/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.util

import com.roboparty.rpoexpression.audio.TtsManager
import com.roboparty.rpoexpression.expression.ExpressionStyle

/**
 * 音色选项数据结构
 * 代表一个可选的 TTS 音色
 *
 * @property voiceId 云端音色 ID（唯一键，同时作为缓存 key 前缀）
 * @property displayName 展示名（用户看到的名字）
 * @property tag 一句话风格描述
 * @property gender 所属性别分组（MALE / FEMALE）
 * @property systemProfile 系统 TTS 降级用的 VoiceProfile（pitch / rate）
 * @property sampleText 试听句
 */
data class VoiceOption(
    val voiceId: String,
    val displayName: String,
    val tag: String,
    val gender: ExpressionStyle,
    val systemProfile: TtsManager.VoiceProfile,
    val sampleText: String = "你好，我是你的机器人伙伴。"
)

/**
 * 音色库（单例）
 * 按性别分组管理所有可选音色
 *
 * 设计原则：
 * - 与 PersonaRegistry、EyeStyleFactory 模式一致
 * - 新增音色只需加一行配置，不改业务逻辑
 * - 用 ExpressionStyle 作为分组键，与人格配置天然对齐
 *
 * 使用方式：
 * - VoiceLibrary.getVoices(gender) → 获取该性别的音色列表（面板渲染用）
 * - VoiceLibrary.getById(voiceId) → 反查单个音色（降级映射与校验用）
 * - VoiceLibrary.getDefault(gender) → 获取该性别默认音色
 */
object VoiceLibrary {

    // ==================== 音色配置（占位符，待用户填写实际 voiceId 和展示名） ====================

    /**
     * 女款音色列表
     * 注意：第一个为默认音色
     */
    private val FEMALE_VOICES = listOf(
        VoiceOption(
            voiceId = "longyan_v3", // 龙颜（默认女声）
            displayName = "龙颜",
            tag = "清亮少女",
            gender = ExpressionStyle.FEMALE,
            systemProfile = TtsManager.VOICE_FEMALE,
            sampleText = "你好，我是萝卜，很高兴认识你呀~"
        ),
        VoiceOption(
            voiceId = "longwanjun_v3", // 龙婉君
            displayName = "龙婉君",
            tag = "温柔知性",
            gender = ExpressionStyle.FEMALE,
            systemProfile = TtsManager.VoiceProfile(
                pitch = 1.05f,
                speechRate = 0.95f,
                cloudVoiceId = "longwanjun_v3"
            ),
            sampleText = "你好，很高兴见到你。"
        ),
        VoiceOption(
            voiceId = "longyingtao_v3", // 龙应桃
            displayName = "龙应桃",
            tag = "元气活泼",
            gender = ExpressionStyle.FEMALE,
            systemProfile = TtsManager.VoiceProfile(
                pitch = 1.2f,
                speechRate = 1.05f,
                cloudVoiceId = "longyingtao_v3"
            ),
            sampleText = "嗨，今天过得怎么样呀？"
        )
    )

    /**
     * 男款音色列表
     * 注意：第一个为默认音色
     */
    private val MALE_VOICES = listOf(
        VoiceOption(
            voiceId = "longfei_v3", // 龙飞（默认男声）
            displayName = "龙飞",
            tag = "热血磁性",
            gender = ExpressionStyle.MALE,
            systemProfile = TtsManager.VOICE_MALE,
            sampleText = "你好，我是阿冷。"
        ),
        VoiceOption(
            voiceId = "longsanshu_v3", // 龙三叔
            displayName = "龙三叔",
            tag = "低沉磁性",
            gender = ExpressionStyle.MALE,
            systemProfile = TtsManager.VoiceProfile(
                pitch = 0.6f,
                speechRate = 0.95f,
                cloudVoiceId = "longsanshu_v3"
            ),
            sampleText = "你好，有什么可以帮你的。"
        ),
        VoiceOption(
            voiceId = "longyichen_v3", // 龙逸尘
            displayName = "龙逸尘",
            tag = "稳重播报",
            gender = ExpressionStyle.MALE,
            systemProfile = TtsManager.VoiceProfile(
                pitch = 0.65f,
                speechRate = 0.9f,
                cloudVoiceId = "longyichen_v3"
            ),
            sampleText = "你好，请问有什么事。"
        )
    )

    // ==================== 公开 API ====================

    /**
     * 获取指定性别的音色列表
     * 用于设置面板渲染
     *
     * @param gender 性别（MALE / FEMALE）
     * @return 该性别的音色列表
     */
    fun getVoices(gender: ExpressionStyle): List<VoiceOption> {
        return when (gender) {
            ExpressionStyle.MALE, ExpressionStyle.SHAPE_MALE -> MALE_VOICES
            ExpressionStyle.FEMALE, ExpressionStyle.SHAPE_FEMALE -> FEMALE_VOICES
            else -> emptyList()
        }
    }

    /**
     * 根据 voiceId 反查音色
     * 用于降级映射、性别校验
     *
     * @param voiceId 云端音色 ID
     * @return 对应的 VoiceOption，找不到返回 null
     */
    fun getById(voiceId: String): VoiceOption? {
        return FEMALE_VOICES.find { it.voiceId == voiceId }
            ?: MALE_VOICES.find { it.voiceId == voiceId }
    }

    /**
     * 获取指定性别的默认音色
     * 即列表中的第一个
     *
     * @param gender 性别（MALE / FEMALE）
     * @return 默认音色
     */
    fun getDefault(gender: ExpressionStyle): VoiceOption {
        return when (gender) {
            ExpressionStyle.MALE, ExpressionStyle.SHAPE_MALE -> MALE_VOICES.first()
            ExpressionStyle.FEMALE, ExpressionStyle.SHAPE_FEMALE -> FEMALE_VOICES.first()
            else -> MALE_VOICES.first()
        }
    }

    /**
     * 校验 voiceId 的性别是否匹配
     * 三道关卡之一：写入层校验，防止绕过 UI 选到异性音色
     *
     * @param voiceId 要校验的音色 ID
     * @param expectedGender 期望的性别
     * @return 是否匹配
     */
    fun isVoiceMatchingGender(voiceId: String, expectedGender: ExpressionStyle): Boolean {
        val voice = getById(voiceId) ?: return false
        return isMaleStyle(voice.gender) == isMaleStyle(expectedGender)
    }

    /**
     * 判断风格是否属于男性分组
     */
    private fun isMaleStyle(style: ExpressionStyle): Boolean {
        return when (style) {
            ExpressionStyle.MALE, ExpressionStyle.SHAPE_MALE -> true
            ExpressionStyle.FEMALE, ExpressionStyle.SHAPE_FEMALE -> false
        }
    }
}
