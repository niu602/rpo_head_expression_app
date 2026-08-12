/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.audio

/**
 * 会话统一标识与状态追踪
 *
 * 设计目标：
 * - 每一轮"用户说话 → ASR识别 → LLM推理 → TTS播报"绑定同一个 conversationId
 * - 人格切换 / Barge-in / 超时 / 错误时，通过 sessionId 校验丢弃过期回调
 * - 根治旧架构中"旧会话回调到达后覆盖新会话状态"的竞态问题
 *
 * 生命周期：
 * 1. MainActivity 在触发新一轮对话时生成新的 ConversationSession
 * 2. sessionId 沿 ASR → LLM → TTS 全链路透传
 * 3. 任意层回调到达时，先校验 sessionId == currentSessionId，不匹配则丢弃
 * 4. 会话结束（COMPLETED/FAILED/INTERRUPTED）后，currentSessionId 前移，旧回调自然失效
 */
data class ConversationSession(
    /** 唯一会话ID，使用 AtomicLong 递增生成，保证单调递增且线程安全 */
    val id: Long,
    /** 会话创建时间戳（System.currentTimeMillis()） */
    val startedAt: Long,
    /** 用户输入文本（ASR识别结果） */
    var userText: String = "",
    /** 助手回复文本（LLM完整输出） */
    var assistantText: String = "",
    /** 当前会话状态 */
    var state: ConversationState = ConversationState.IDLE
)

/**
 * 会话状态枚举
 *
 * 状态流转：
 * IDLE → LISTENING → TRANSCRIBING → THINKING → SPEAKING → COMPLETED
 *                                    ↘ INTERRUPTED（任意阶段可被打断）
 *                                    ↘ FAILED（任意阶段可出错）
 */
enum class ConversationState {
    /** 空闲，未开始 */
    IDLE,
    /** 正在聆听（麦克风已开启，等待用户说话） */
    LISTENING,
    /** 正在转写（ASR识别中） */
    TRANSCRIBING,
    /** 正在思考（LLM推理中） */
    THINKING,
    /** 正在播报（TTS播放中） */
    SPEAKING,
    /** 已被打断（Barge-in / 唤醒词 / 硬件指令等） */
    INTERRUPTED,
    /** 正常完成 */
    COMPLETED,
    /** 出错失败 */
    FAILED
}

/**
 * 打断原因枚举
 *
 * 用于日志追踪和后续差异化处理（如不同打断原因的恢复策略）
 */
enum class InterruptReason {
    /** 用户说话打断（Barge-in） */
    USER_SPEECH,
    /** 唤醒词打断（KWS检测到新唤醒词） */
    WAKE_WORD,
    /** 硬件指令打断（手柄按键 / ADB命令等） */
    HARDWARE_COMMAND,
    /** 超时打断（ASR识别超时 / 连续对话空闲超时等） */
    TIMEOUT,
    /** 系统错误打断 */
    SYSTEM_ERROR
}
