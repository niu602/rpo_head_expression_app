/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * 手机下发给板端的统一消息封装
 * 与 robothead.proto 中 PhoneMessage 定义一致
 *
 * oneof payload 保证一条消息只能是 face 或 command，不能同时存在：
 *   1: face (FaceOffset)       — 人脸追踪数据（高频，约15fps）
 *   2: command (HeadControlCmd) — 动作/情绪指令（低频，按键触发）
 *
 * 封包格式（外层）：[4字节大端长度][PhoneMessage protobuf二进制]
 */
data class PhoneMessage(
    val face: FaceOffset? = null,
    val command: HeadControlCmd? = null
) {
    init {
        // oneof 语义校验：不能同时设置 face 和 command
        require(!(face != null && command != null)) {
            "PhoneMessage oneof payload: face and command cannot be set at the same time"
        }
    }

    /**
     * 序列化为 Protobuf 二进制数据
     * oneof 语义：只序列化设置了的那个字段
     */
    fun toByteArray(): ByteArray {
        val writer = ProtobufCodec.Writer()
        // field 1: face (FaceOffset) — 嵌套消息
        face?.let {
            writer.writeBytes(1, it.toByteArray())
        }
        // field 2: command (HeadControlCmd) — 嵌套消息
        command?.let {
            writer.writeBytes(2, it.toByteArray())
        }
        return writer.toByteArray()
    }

    companion object {
        /**
         * 构建一个人脸追踪消息
         */
        fun face(faceOffset: FaceOffset): PhoneMessage {
            return PhoneMessage(face = faceOffset, command = null)
        }

        /**
         * 构建一个头部控制指令消息
         */
        fun command(cmd: HeadControlCmd): PhoneMessage {
            return PhoneMessage(face = null, command = cmd)
        }

        /**
         * 从 Protobuf 二进制数据解析
         * oneof 语义：后读到的字段会覆盖先读到的（符合Protobuf标准行为）
         */
        fun parseFrom(data: ByteArray): PhoneMessage {
            val reader = ProtobufCodec.Reader(data)
            var face: FaceOffset? = null
            var command: HeadControlCmd? = null

            while (!reader.isAtEnd()) {
                val (field, wireType) = reader.readTag()
                when (field) {
                    // field 1: face (FaceOffset) — 嵌套消息，length-delimited
                    1 -> {
                        val faceBytes = reader.readBytes()
                        if (faceBytes.isNotEmpty()) {
                            face = FaceOffset.parseFrom(faceBytes)
                            command = null // oneof：设置face时清空command
                        }
                    }
                    // field 2: command (HeadControlCmd) — 嵌套消息，length-delimited
                    2 -> {
                        val cmdBytes = reader.readBytes()
                        if (cmdBytes.isNotEmpty()) {
                            // HeadControlCmd 目前没有 parseFrom，手动解析
                            command = parseHeadControlCmd(cmdBytes)
                            face = null // oneof：设置command时清空face
                        }
                    }
                    else -> reader.skipField(wireType)
                }
            }

            return PhoneMessage(face = face, command = command)
        }

        /**
         * 手动解析 HeadControlCmd（复用ProtobufCodec.Reader）
         */
        private fun parseHeadControlCmd(data: ByteArray): HeadControlCmd {
            val reader = ProtobufCodec.Reader(data)
            var emotion = EmotionType.EMOTION_NONE
            var moveMode = HeadMoveMode.MOVE_NONE

            while (!reader.isAtEnd()) {
                val (field, wireType) = reader.readTag()
                when (field) {
                    1 -> emotion = EmotionType.fromValue(reader.readEnum())
                    2 -> moveMode = HeadMoveMode.fromValue(reader.readEnum())
                    else -> reader.skipField(wireType)
                }
            }

            return HeadControlCmd(emotion = emotion, moveMode = moveMode)
        }
    }
}
