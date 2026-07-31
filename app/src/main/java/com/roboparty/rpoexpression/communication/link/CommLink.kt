/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication.link

/**
 * 通信链路抽象接口
 * 所有链路（TCP/UDP/USB/BLE）统一实现此接口
 */
interface CommLink {

    /** 链路类型 */
    fun getType(): LinkType

    /** 链路优先级（数字越小优先级越高，主链路优先级最高） */
    fun getPriority(): Int

    /** 是否连接 */
    fun isConnected(): Boolean

    /** 链路质量评分 0-100，用于切换决策 */
    fun getQuality(): Int

    /** 最后一次收到数据的时间戳(ms)，用于健康检测 */
    fun getLastReceiveTime(): Long

    /** 最后一次发送成功的时间戳(ms) */
    fun getLastSendTime(): Long

    /**
     * 连接链路
     * @param host 目标地址（IP/串口名/BLE地址）
     * @param port 端口/波特率
     */
    suspend fun connect(host: String, port: Int): Boolean

    /** 断开连接 */
    fun disconnect()

    /**
     * 发送数据
     * @return 是否发送成功
     */
    fun send(data: ByteArray): Boolean

    /**
     * 设置数据接收回调
     */
    fun setDataCallback(callback: (ByteArray) -> Unit)

    /**
     * 设置链路状态变化回调
     */
    fun setStateCallback(callback: (LinkState) -> Unit)

    /** 链路状态 */
    enum class LinkState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    /** 链路类型枚举（对应协议定义） */
    enum class LinkType(val value: Int) {
        LINK_UNKNOWN(0),
        LINK_TCP(1),
        LINK_UDP(2);

        companion object {
            fun fromValue(v: Int): LinkType = values().find { it.value == v } ?: LINK_UNKNOWN
        }
    }
}
