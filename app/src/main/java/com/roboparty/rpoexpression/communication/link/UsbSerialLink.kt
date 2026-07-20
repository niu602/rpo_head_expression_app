/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication.link

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * USB串口备用链路
 * 当TCP/UDP不可用时，通过USB OTG串口通信
 * 预留实现框架，实际串口接入时完善读写逻辑
 */
class UsbSerialLink : CommLink {

    companion object {
        private const val TAG = "UsbSerialLink"
        private const val DEFAULT_BAUD_RATE = 115200
    }

    private val _connected = AtomicBoolean(false)
    private val _lastReceiveTime = AtomicLong(0)
    private val _lastSendTime = AtomicLong(0)
    private var _quality = 0

    private var dataCallback: ((ByteArray) -> Unit)? = null
    private var stateCallback: ((CommLink.LinkState) -> Unit)? = null

    override fun getType() = CommLink.LinkType.LINK_USB_SERIAL
    override fun getPriority() = 3  // 备用链路优先级3
    override fun isConnected() = _connected.get()
    override fun getQuality() = _quality
    override fun getLastReceiveTime() = _lastReceiveTime.get()
    override fun getLastSendTime() = _lastSendTime.get()

    override suspend fun connect(host: String, port: Int): Boolean {
        // TODO: 接入USB串口库（如usb-serial-for-android）实现实际连接
        // host: 串口设备名，port: 波特率（默认115200）
        Log.d(TAG, "USB Serial connect: device=$host, baudRate=${if (port != 0) port else DEFAULT_BAUD_RATE}")
        stateCallback?.invoke(CommLink.LinkState.CONNECTING)

        // 预留：实际硬件接入后实现连接逻辑
        // 目前框架预留，返回false表示未实际连接
        stateCallback?.invoke(CommLink.LinkState.DISCONNECTED)
        return false
    }

    override fun disconnect() {
        _connected.set(false)
        _quality = 0
        stateCallback?.invoke(CommLink.LinkState.DISCONNECTED)
        Log.d(TAG, "USB Serial disconnected")
    }

    override fun send(data: ByteArray): Boolean {
        if (!_connected.get()) return false
        // TODO: 实际串口写入
        _lastSendTime.set(System.currentTimeMillis())
        return true
    }

    override fun setDataCallback(callback: (ByteArray) -> Unit) {
        this.dataCallback = callback
    }

    override fun setStateCallback(callback: (CommLink.LinkState) -> Unit) {
        this.stateCallback = callback
    }

    /**
     * 模拟USB连接成功（测试用）
     */
    fun simulateConnected() {
        _connected.set(true)
        _quality = 70
        _lastReceiveTime.set(System.currentTimeMillis())
        stateCallback?.invoke(CommLink.LinkState.CONNECTED)
    }
}
