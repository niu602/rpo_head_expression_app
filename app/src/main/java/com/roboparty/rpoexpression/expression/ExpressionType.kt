/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 机器人表情类型枚举
 * 定义6类基础表情状态及优先级
 * 优先级规则：说话 > 应答反馈 > 识别中 > 思考 > 待机（眨眼为待机附属状态）
 * 高优先级可以抢占低优先级，低优先级无法抢占高优先级
 */
enum class ExpressionType(
    val priority: Int,
    val defaultDuration: Long,
    val isAutoFallback: Boolean,
    val description: String
) {
    /**
     * 待机状态：默认状态，循环低幅度微动
     * 优先级最低，其他表情结束后自动回落至此
     */
    IDLE(
        priority = 0,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        description = "待机"
    ),

    /**
     * 眨眼状态：待机状态下随机触发，单次时长200ms
     * 待机附属状态，不参与优先级竞争
     */
    BLINK(
        priority = 0,
        defaultDuration = 200L,
        isAutoFallback = true,
        description = "眨眼"
    ),

    /**
     * 说话状态：语音播报时触发，口型循环动画
     * 优先级最高
     */
    SPEAKING(
        priority = 4,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        description = "说话"
    ),

    /**
     * 思考状态：ASR准备/任务处理时触发
     * 优先级高于待机
     */
    THINKING(
        priority = 1,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        description = "思考"
    ),

    /**
     * 识别中状态：语音识别/人脸识别工作时触发
     * 优先级高于思考
     */
    RECOGNIZING(
        priority = 2,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        description = "识别中"
    ),

    /**
     * 应答反馈状态：识别成功/指令执行完成时触发，持续1s后回落
     * 优先级高于识别/思考，低于说话，不会打断播报
     */
    RESPONSE(
        priority = 3,
        defaultDuration = 1000L,
        isAutoFallback = true,
        description = "应答反馈"
    );

    companion object {
        /**
         * 根据指令字符串获取表情类型
         */
        fun fromCommand(command: String): ExpressionType? {
            return when (command.lowercase()) {
                "idle", "待机" -> IDLE
                "blink", "眨眼" -> BLINK
                "speaking", "speak", "说话" -> SPEAKING
                "thinking", "think", "思考" -> THINKING
                "recognizing", "recognize", "识别中" -> RECOGNIZING
                "response", "reply", "应答" -> RESPONSE
                else -> null
            }
        }
    }
}
