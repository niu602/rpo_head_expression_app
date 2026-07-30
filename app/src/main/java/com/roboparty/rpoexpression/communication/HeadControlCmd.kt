/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * APP 下发给设备：头部控制指令
 * 与嵌入式 head_control.proto 定义严格一致
 *
 * 字段编号对应：
 *   1: emotion (enum) — 情绪类型，板端自动查动作库执行对应动作
 *
 * 设计说明：
 * 电机运动细节（角度、速度、幅度等）已由嵌入式动作库封装，
 * APP只需发送情绪类型，板端自动映射到对应动作序列。
 */
data class HeadControlCmd(
    val emotion: EmotionType = EmotionType.EMOTION_NONE
) {
    /**
     * 序列化为 Protobuf 二进制数据
     * 复用项目 ProtobufCodec 的 Writer，保持编码风格一致
     */
    fun toByteArray(): ByteArray {
        val writer = ProtobufCodec.Writer()
        // field 1: emotion (enum, varint编码)
        writer.writeEnum(1, emotion.value)
        return writer.toByteArray()
    }

    companion object {
        /**
         * 构建器，方便链式调用
         */
        fun newBuilder(): Builder = Builder()
    }

    class Builder {
        private var emotion: EmotionType = EmotionType.EMOTION_NONE

        fun setEmotion(value: EmotionType) = apply { emotion = value }

        fun build(): HeadControlCmd = HeadControlCmd(emotion = emotion)
    }
}
