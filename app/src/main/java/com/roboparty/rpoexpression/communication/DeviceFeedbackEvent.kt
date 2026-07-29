/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

/**
 * 设备上报给APP：传感器交互事件
 * 与 head_control.proto 中 DeviceFeedbackEvent 定义一致
 *
 * 字段编号对应：
 *   1: event_code (int32) — 1=拍打头部 2=触摸脸部 3=机身晃动
 *   2: sensor_value (float)
 *   3: device_online (bool)
 */
data class DeviceFeedbackEvent(
    val eventCode: Int = 0,
    val sensorValue: Float = 0f,
    val deviceOnline: Boolean = false
) {
    companion object {
        /**
         * 从 Protobuf 二进制数据解析
         * 复用项目 ProtobufCodec 的 Reader
         */
        fun parseFrom(data: ByteArray): DeviceFeedbackEvent {
            val reader = ProtobufCodec.Reader(data)
            var eventCode = 0
            var sensorValue = 0f
            var deviceOnline = false

            while (!reader.isAtEnd()) {
                val (field, wireType) = reader.readTag()
                when (field) {
                    1 -> eventCode = reader.readUint32()
                    2 -> sensorValue = reader.readFloat()
                    3 -> deviceOnline = reader.readBool()
                    else -> reader.skipField(wireType)
                }
            }

            return DeviceFeedbackEvent(
                eventCode = eventCode,
                sensorValue = sensorValue,
                deviceOnline = deviceOnline
            )
        }
    }
}
