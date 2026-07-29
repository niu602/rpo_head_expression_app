/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * 人脸偏移数据（高频，约15fps）
 * APP → 嵌入式端，控制头部电机跟随人脸转动
 *
 * 与 robothead.proto 中 FaceOffset 定义一致：
 *   1: ox (float) — 水平偏移 [-1,1]，0=居中，正=人脸在右
 *   2: oy (float) — 垂直偏移 [-1,1]，0=居中，正=人脸在下
 *   3: confidence (float) — 置信度 [0,1]，>0.5 板端才跟踪
 */
data class FaceOffset(
    val ox: Float = 0f,
    val oy: Float = 0f,
    val confidence: Float = 0f
) {
    /**
     * 序列化为 Protobuf 二进制数据
     */
    fun toByteArray(): ByteArray {
        val writer = ProtobufCodec.Writer()
        // field 1: ox (float)
        writer.writeFloat(1, ox)
        // field 2: oy (float)
        writer.writeFloat(2, oy)
        // field 3: confidence (float)
        writer.writeFloat(3, confidence)
        return writer.toByteArray()
    }

    companion object {
        /**
         * 构建器，方便链式调用
         */
        fun newBuilder(): Builder = Builder()

        /**
         * 从 Protobuf 二进制数据解析
         */
        fun parseFrom(data: ByteArray): FaceOffset {
            val reader = ProtobufCodec.Reader(data)
            var ox = 0f
            var oy = 0f
            var confidence = 0f

            while (!reader.isAtEnd()) {
                val (field, wireType) = reader.readTag()
                when (field) {
                    1 -> ox = reader.readFloat()
                    2 -> oy = reader.readFloat()
                    3 -> confidence = reader.readFloat()
                    else -> reader.skipField(wireType)
                }
            }

            return FaceOffset(
                ox = ox,
                oy = oy,
                confidence = confidence
            )
        }
    }

    class Builder {
        private var ox: Float = 0f
        private var oy: Float = 0f
        private var confidence: Float = 0f

        fun setOx(value: Float) = apply { ox = value }
        fun setOy(value: Float) = apply { oy = value }
        fun setConfidence(value: Float) = apply { confidence = value }

        fun build(): FaceOffset = FaceOffset(
            ox = ox,
            oy = oy,
            confidence = confidence
        )
    }
}
