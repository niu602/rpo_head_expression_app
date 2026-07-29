/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * APP 下发给设备：头部控制总指令
 * 与嵌入式提供的简化版 head_control.proto 定义一致
 *
 * 字段编号对应：
 *   1: emotion (enum) — 情绪
 *   2: move_mode (enum) — 运动模式
 *
 * 说明：电机运动细节（角度、速度等）已由嵌入式封装，APP只需下发情绪和模式
 */
data class HeadControlCmd(
    val emotion: EmotionType = EmotionType.EMOTION_NONE,
    val moveMode: HeadMoveMode = HeadMoveMode.MOVE_NONE
) {
    /**
     * 序列化为 Protobuf 二进制数据
     * 复用项目 ProtobufCodec 的 Writer，保持编码风格一致
     */
    fun toByteArray(): ByteArray {
        val writer = ProtobufCodec.Writer()
        // field 1: emotion (enum)
        writer.writeEnum(1, emotion.value)
        // field 2: move_mode (enum)
        writer.writeEnum(2, moveMode.value)
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
        private var moveMode: HeadMoveMode = HeadMoveMode.MOVE_NONE

        fun setEmotion(value: EmotionType) = apply { emotion = value }
        fun setMoveMode(value: HeadMoveMode) = apply { moveMode = value }

        fun build(): HeadControlCmd = HeadControlCmd(
            emotion = emotion,
            moveMode = moveMode
        )
    }
}
