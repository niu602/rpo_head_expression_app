/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication.link

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BLE蓝牙备用链路
 * 最后一级备用，当有线/WiFi都不可用时使用
 * 预留实现框架，实际BLE接入时完善GATT读写逻辑
 */
class BleLink : CommLink {

    companion object {
        private const val TAG = "BleLink"
        private const val BLE_MTU = 20  // BLE默认MTU扣除头后有效载荷
    }

    private val _connected = AtomicBoolean(false)
    private val _lastReceiveTime = AtomicLong(0)
    private val _lastSendTime = AtomicLong(0)
    private var _quality = 0

    private var dataCallback: ((ByteArray) -> Unit)? = null
    private var stateCallback: ((CommLink.LinkState) -> Unit)? = null

    override fun getType() = CommLink.LinkType.LINK_BLE
    override fun getPriority() = 4  // 最低优先级（最后备用）
    override fun isConnected() = _connected.get()
    override fun getQuality() = _quality
    override fun getLastReceiveTime() = _lastReceiveTime.get()
    override fun getLastSendTime() = _lastSendTime.get()

    override suspend fun connect(host: String, port: Int): Boolean {
        // TODO: 接入Android BLE API实现GATT连接
        // host: BLE MAC地址，port: 暂未使用
        Log.d(TAG, "BLE connect: device=$host")
        stateCallback?.invoke(CommLink.LinkState.CONNECTING)

        // 预留：实际BLE接入后实现连接逻辑
        // 注意：BLE MTU限制，大包需要分片
        stateCallback?.invoke(CommLink.LinkState.DISCONNECTED)
        return false
    }

    override fun disconnect() {
        _connected.set(false)
        _quality = 0
        stateCallback?.invoke(CommLink.LinkState.DISCONNECTED)
        Log.d(TAG, "BLE disconnected")
    }

    override fun send(data: ByteArray): Boolean {
        if (!_connected.get()) return false
        // TODO: BLE GATT写入，超过MTU需要分片
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
     * 模拟BLE连接成功（测试用）
     */
    fun simulateConnected() {
        _connected.set(true)
        _quality = 50
        _lastReceiveTime.set(System.currentTimeMillis())
        stateCallback?.invoke(CommLink.LinkState.CONNECTED)
    }
}
