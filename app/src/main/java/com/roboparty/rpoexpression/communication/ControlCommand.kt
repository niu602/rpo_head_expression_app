/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

import com.google.gson.annotations.SerializedName
import com.roboparty.rpoexpression.expression.ExpressionType

/**
 * 主控通信指令数据类
 * 与roboto_origin系统通信的标准JSON格式
 *
 * 指令格式规范：
 * {
 *   "cmd": "expression",       // 指令类型
 *   "type": "speaking",        // 表情类型
 *   "duration": 1000,          // 可选：持续时长(ms)
 *   "force": false,            // 可选：是否强制切换
 *   "timestamp": 1234567890,   // 时间戳
 *   "seq": 1,                  // 序列号
 *   "source": "roboto_origin"  // 指令来源
 * }
 */
data class ControlCommand(
    @SerializedName("cmd") val command: String,
    @SerializedName("type") val type: String? = null,
    @SerializedName("duration") val duration: Long? = null,
    @SerializedName("force") val force: Boolean = false,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("seq") val sequence: Int = 0,
    @SerializedName("source") val source: String = "unknown",
    @SerializedName("data") val data: Map<String, Any>? = null
) {
    companion object {
        // 指令类型常量
        const val CMD_EXPRESSION = "expression"
        const val CMD_SEQUENCE = "sequence"
        const val CMD_CLEAR = "clear"
        const val CMD_PING = "ping"
        const val CMD_STATUS = "status"
        const val CMD_BRIGHTNESS = "brightness"

        // 指令来源
        const val SOURCE_MASTER = "roboto_origin"
        const val SOURCE_ASR = "asr_module"
        const val SOURCE_FACE = "face_recognition"
        const val SOURCE_GAMEPAD = "gamepad"
        const val SOURCE_SYSTEM = "system"
    }

    /**
     * 解析为表情类型
     */
    fun toExpressionType(): ExpressionType? {
        return type?.let { ExpressionType.fromCommand(it) }
    }

    /**
     * 解析表情序列
     */
    fun toExpressionSequence(): List<ExpressionType>? {
        if (command != CMD_SEQUENCE) return null
        val types = data?.get("types") as? List<*>
        return types?.mapNotNull {
            ExpressionType.fromCommand(it.toString())
        }
    }
}

/**
 * 设备状态上报数据类
 * 手机端向主控上报当前状态
 */
data class DeviceStatus(
    @SerializedName("device") val device: String = "redmi_note_12_turbo",
    @SerializedName("status") val status: String = "running",
    @SerializedName("current_expression") val currentExpression: String,
    @SerializedName("brightness") val brightness: Float,
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
