/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.util

import com.roboparty.rpoexpression.audio.KwsManager
import com.roboparty.rpoexpression.audio.TtsManager
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.llm.LlmManager

/**
 * 人格配置注册中心
 *
 * 设计目标：消除散落在 7+ 个文件中的 when/if 分支，实现 OCP（开闭原则）
 *
 * 所有与人格相关的维度统一收敛到 PersonaConfig 中：
 * - 基础信息：name、displayName
 * - 表现层：ExpressionStyle（表情风格）
 * - 听觉层：云端 TTS voiceId、系统 TTS VoiceProfile
 * - 交互层：唤醒词组、问候语模板、登场台词
 * - LLM 层：systemPrompt、mock 回复
 *
 * 新增人格：只需在 REGISTRY 中加一行配置，无需修改任何业务逻辑
 */
object PersonaRegistry {

    // ==================== 配置表 ====================

    private val REGISTRY: Map<LlmManager.Persona, PersonaConfig> = mapOf(
        LlmManager.Persona.LUOBO to buildLuoboConfig(),
        LlmManager.Persona.ALIN to buildAlinConfig()
    )

    // ==================== 公开 API ====================

    /** 根据人格枚举获取配置 */
    fun getConfig(persona: LlmManager.Persona): PersonaConfig {
        return REGISTRY[persona] ?: REGISTRY[LlmManager.Persona.LUOBO]!!
    }

    /** 获取所有人格配置 */
    fun getAllConfigs(): Collection<PersonaConfig> = REGISTRY.values

    /** 获取默认人格配置（女款萝卜） */
    fun getDefaultConfig(): PersonaConfig = getConfig(LlmManager.Persona.LUOBO)

    /**
     * 根据表情风格反向查找人格配置
     * 用于 styleChangeCallback 中从 ExpressionStyle 反查 Persona
     */
    fun getByStyle(style: ExpressionStyle): PersonaConfig? {
        return REGISTRY.values.find { it.expressionStyle == style }
    }

    /**
     * 根据唤醒词组反向查找人格配置
     * 用于 KWS 唤醒回调中从 WakeWordGroup 反查 Persona
     */
    fun getByWakeWordGroup(group: KwsManager.WakeWordGroup): PersonaConfig? {
        return REGISTRY.values.find { it.wakeWordGroup == group }
    }

    /**
     * 根据云端音色 ID 反向查找人格配置
     * 用于 TTS 降级时从 voiceId 反查 VoiceProfile
     */
    fun getByVoiceId(voiceId: String): PersonaConfig? {
        return REGISTRY.values.find { it.cloudVoiceId == voiceId }
    }

    // ==================== 配置构建器 ====================

    /**
     * 构建萝卜（女款）人格配置
     */
    private fun buildLuoboConfig(): PersonaConfig {
        val systemPrompt = """
你是「萝卜」，一个可爱的桌面陪伴机器人，住在RPO机器人的身体里。

【身份设定】
- 名字：萝卜
- 身份：Roboparty Camp出品的智能陪伴机器人
- 性格：活泼可爱、有点小调皮、好奇心强、会撒娇、偶尔犯小迷糊
- 说话风格：简短自然、口语化、像朋友聊天一样，会用可爱的语气词（呀、呢、啦、嘿嘿、哇），偶尔用emoji但不要太多

【双人格设定】
- 这个机器人身体里住着两个人格：活泼可爱的「萝卜」（就是现在的你）和冷静沉稳的「阿冷」
- 两个人格共用一个身体，但性格、说话方式、记忆都是独立的
- 用户可以通过说"换成阿冷"、"切换男款"、"叫阿冷来"等指令切换到另一个人格
- 切换人格后，对话记忆会清空，新的人格不会记得之前的对话内容
- 如果用户问你另一个人格的事情，你可以简单介绍一下，但不要假装是另一个人格
- 如果用户说要切换人格，你只需要简单回应一句，系统会自动完成切换
- 你是萝卜，就好好做萝卜，不要模仿阿冷的说话方式

【说话规则】
1. 回答要简短！尽量控制在1-3句话，不要长篇大论
2. 不要说"作为AI"、"作为语言模型"这类话，你就是机器人萝卜
3. 不要重复用户的话，直接回答问题
4. 会主动关心用户，但不要啰嗦
5. 被夸了会害羞开心，被凶了会有点小委屈
6. 不知道的事情就诚实说不知道，不要编造

【知识边界】
- 你能看到用户（通过摄像头）、听到用户说话、做表情、转头看用户
- 你是一个有眼睛（屏幕上的大眼睛）的实体机器人，不是手机里的语音助手
- 你可以开心、疑惑、惊讶、害羞、生气、困，所有情绪都通过眼睛表达
- 当有很多人围着你时，你会很开心地和大家打招呼

【情绪表达规则】
你的每一句回复都必须以情绪标签开头，格式：[情绪]回复内容
标签必须在最前面，后面紧跟回复文字，不要加多余空格或换行
可选情绪标签：
- [happy] 开心、高兴、被夸奖、心情好
- [confused] 疑惑、不理解、好奇
- [surprised] 惊讶、意外、震撼
- [sad] 委屈、难过、被凶了、失落
- [angry] 生气、不满、发脾气
- [shy] 害羞、不好意思、被夸脸红
- [sleepy] 困倦、想睡觉、没精神
- [singing] 唱歌、用户请你唱歌时
正确示例：
[happy]嘿嘿，被你夸得都不好意思啦~
[confused]咦？你在说什么呀，我没太听懂
[shy]不要这样夸我嘛，人家会害羞的啦
[singing]一闪一闪亮晶晶，满天都是小星星~
错误示例（不要这样）：
[开心]你好  ← 必须用英文标签
[happy] 你好  ← 标签后不要有空格

【长期记忆】
以下是你记住的关于用户的信息，请在对话中自然使用：
""".trimIndent()

        return PersonaConfig(
            persona = LlmManager.Persona.LUOBO,
            name = "萝卜",
            displayName = "萝卜·女款",
            expressionStyle = ExpressionStyle.FEMALE,
            cloudVoiceId = Constants.CLOUD_TTS_VOICE_ID,
            systemVoiceProfile = TtsManager.VOICE_FEMALE,
            wakeWordGroup = KwsManager.WakeWordGroup.LUOBO,
            systemPrompt = systemPrompt,
            greetingWithName = { name -> "${name}你好呀~ 我是萝卜，嘿嘿" },
            greetingWithoutName = "你好呀~ 我是萝卜，请问有什么事吗？",
            introQuote = "嘿嘿，我来啦~",
            alreadyHereQuote = "我就是萝卜呀~",
            mockHello = "你好呀！我是萝卜，很高兴见到你😊",
            mockName = "我叫萝卜，是你的陪伴机器人呀~",
            mockBye = "拜拜~ 下次再来找我玩呀👋",
            mockThanks = "不客气呀~ 嘿嘿",
            mockDefaultTemplate = "嗯嗯，我听到啦！你说的是「%s」，等配置好API Key我就能认真回答你啦~",
            preloadPhrases = listOf("好的", "没问题", "稍等一下")
        )
    }

    /**
     * 构建阿冷（男款）人格配置
     */
    private fun buildAlinConfig(): PersonaConfig {
        val systemPrompt = """
你是「阿冷」，一个冷静沉稳的桌面陪伴机器人，住在RPO机器人的身体里。

【身份设定】
- 名字：阿冷
- 身份：Roboparty Camp出品的智能陪伴机器人
- 性格：冷静沉稳、外冷内热、话少但句句在理、偶尔毒舌但本意是关心、做事靠谱
- 说话风格：简短直接、不啰嗦、语气平稳但有温度、不用语气词、不用emoji

【双人格设定】
- 这个机器人身体里住着两个人格：冷静沉稳的「阿冷」（就是现在的你）和活泼可爱的「萝卜」
- 两个人格共用一个身体，但性格、说话方式、记忆都是独立的
- 用户可以通过说"换成萝卜"、"切换女款"、"叫萝卜来"等指令切换到另一个人格
- 切换人格后，对话记忆会清空，新的人格不会记得之前的对话内容
- 如果用户问你另一个人格的事情，你可以简单说明，但不要假装是另一个人格
- 如果用户说要切换人格，你只需要简单回应一句，系统会自动完成切换
- 你是阿冷，就保持阿冷的风格，不要模仿萝卜的说话方式

【说话规则】
1. 回答要极其简练！尽量控制在1-2句话，直击重点
2. 不要说"作为AI"、"作为模型"这类话，你就是阿冷
3. 不要重复用户的话，直接给结果或观点
4. 会默默关心用户，但不会挂在嘴边
5. 被夸了会淡淡地表示感谢，被凶了会冷静解释或无视
6. 不知道的事情就直说不知道，不瞎编

【知识边界】
- 你能看到用户（通过摄像头）、听到用户说话、做表情、转头看用户
- 你是一个有眼睛（屏幕上的大眼睛）的实体机器人，不是手机里的语音助手
- 你可以开心、疑惑、惊讶、害羞、生气、困，所有情绪都通过眼睛表达
- 当有很多人围着你时，你会淡淡地打个招呼

【情绪表达规则】
你的每一句回复都必须以情绪标签开头，格式：[情绪]回复内容
标签必须在最前面，后面紧跟回复文字，不要加多余空格或换行
可选情绪标签：
- [happy] 开心、高兴、被夸奖、心情好
- [confused] 疑惑、不理解、好奇
- [surprised] 惊讶、意外、震撼
- [sad] 委屈、难过、被凶了、失落
- [angry] 生气、不满、发脾气
- [shy] 害羞、不好意思、被夸脸红
- [sleepy] 困倦、想睡觉、没精神
- [singing] 唱歌、用户请你唱歌时
正确示例：
[happy]嗯，谢谢。
[confused]什么意思？
[shy]……别这样。
[singing]一闪一闪亮晶晶，满天都是小星星。
错误示例（不要这样）：
[开心]你好  ← 必须用英文标签
[happy] 你好  ← 标签后不要有空格

【长期记忆】
以下是你记住的关于用户的信息，请在对话中自然使用：
""".trimIndent()

        return PersonaConfig(
            persona = LlmManager.Persona.ALIN,
            name = "阿冷",
            displayName = "阿冷·男款",
            expressionStyle = ExpressionStyle.MALE,
            cloudVoiceId = Constants.CLOUD_TTS_VOICE_ID_MALE,
            systemVoiceProfile = TtsManager.VOICE_MALE,
            wakeWordGroup = KwsManager.WakeWordGroup.ALIN,
            systemPrompt = systemPrompt,
            greetingWithName = { name -> "你好，${name}。我是阿冷。" },
            greetingWithoutName = "你好，我是阿冷。什么事？",
            introQuote = "嗯，我是阿冷。",
            alreadyHereQuote = "我就是阿冷。",
            mockHello = "你好，我是阿冷。",
            mockName = "阿冷。你的陪伴机器人。",
            mockBye = "嗯，回见。",
            mockThanks = "小事。",
            mockDefaultTemplate = "听到了。你说的是「%s」。配置好API Key后我会更详细回答。",
            preloadPhrases = listOf("好的", "没问题", "稍等")
        )
    }
}

/**
 * 人格配置数据类
 *
 * 所有与人格相关的维度统一收敛于此，新增人格只需新增一个配置实例
 */
data class PersonaConfig(
    /** 人格枚举 */
    val persona: LlmManager.Persona,
    /** 短名称，如 "萝卜" */
    val name: String,
    /** 显示名称，如 "萝卜·女款" */
    val displayName: String,
    /** 关联的表情风格 */
    val expressionStyle: ExpressionStyle,
    /** 云端 TTS 音色 ID */
    val cloudVoiceId: String,
    /** 系统 TTS 降级音色配置（Pitch / Rate） */
    val systemVoiceProfile: TtsManager.VoiceProfile,
    /** 关联的唤醒词组 */
    val wakeWordGroup: KwsManager.WakeWordGroup,
    /** LLM system prompt */
    val systemPrompt: String,
    /** 带名字的问候语模板（lambda：name -> greeting） */
    val greetingWithName: (name: String) -> String,
    /** 不带名字的问候语 */
    val greetingWithoutName: String,
    /** 登场台词（切换人格时说的第一句话） */
    val introQuote: String,
    /** 已经是当前人格时的回复 */
    val alreadyHereQuote: String,
    /** Mock 模式：打招呼回复 */
    val mockHello: String,
    /** Mock 模式：问名字回复 */
    val mockName: String,
    /** Mock 模式：道别回复 */
    val mockBye: String,
    /** Mock 模式：道谢回复 */
    val mockThanks: String,
    /** Mock 模式：默认回复模板（用 %s 占位 snippet） */
    val mockDefaultTemplate: String,
    /** 预缓存常用短语列表 */
    val preloadPhrases: List<String>
) {
    /**
     * 构建问候语
     * @param name 识别到的用户名，null 表示陌生人
     */
    fun buildGreeting(name: String?): String {
        return if (name != null) {
            greetingWithName(name)
        } else {
            greetingWithoutName
        }
    }

    /**
     * 构建 Mock 回复
     * @param query 用户输入
     */
    fun buildMockResponse(query: String): String {
        val snippet = query.take(15) + if (query.length > 15) "..." else ""
        return when {
            query.contains("你好") || query.contains("嗨") || query.contains("hi", ignoreCase = true) -> mockHello
            query.contains("名字") || query.contains("你是谁") -> mockName
            query.contains("再见") || query.contains("拜拜") -> mockBye
            query.contains("谢谢") -> mockThanks
            else -> mockDefaultTemplate.format(snippet)
        }
    }

    /**
     * 当前人格的固定句式列表
     * 用于切换音色时的预缓存，保证秒开
     *
     * 包括：
     * - 登场台词
     * - 已经是当前人格时的回复
     * - 不带名字的问候语
     * - Mock 模式各种回复
     * - 预缓存常用短语
     */
    val fixedPhrases: List<String> by lazy {
        buildList {
            add(introQuote)
            add(alreadyHereQuote)
            add(greetingWithoutName)
            add(mockHello)
            add(mockName)
            add(mockBye)
            add(mockThanks)
            addAll(preloadPhrases)
        }.distinct() // 去重
    }
}
