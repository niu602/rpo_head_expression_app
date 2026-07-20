/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication.link

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP高频状态链路
 * 承载高频状态上报、感知事件等非强可靠但要求低延迟的数据
 * 支持丢包，不保证送达，但延迟低、开销小
 */
class UdpLink : CommLink {

    companion object {
        private const val TAG = "UdpLink"
        private const val READ_BUFFER_SIZE = 2048
        private const val SOCKET_TIMEOUT = 1000
    }

    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val isRunning = AtomicBoolean(false)
    private val _connected = AtomicBoolean(false)
    private val _lastReceiveTime = AtomicLong(System.currentTimeMillis())
    private val _lastSendTime = AtomicLong(0)
    private var _quality = 80  // UDP初始质量略低于TCP

    private var dataCallback: ((ByteArray) -> Unit)? = null
    private var stateCallback: ((CommLink.LinkState) -> Unit)? = null

    override fun getType() = CommLink.LinkType.LINK_UDP
    override fun getPriority() = 2  // 次优先级（高频状态链路）
    override fun isConnected() = _connected.get()
    override fun getQuality() = _quality
    override fun getLastReceiveTime() = _lastReceiveTime.get()
    override fun getLastSendTime() = _lastSendTime.get()

    override suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            stateCallback?.invoke(CommLink.LinkState.CONNECTING)
            Log.d(TAG, "Connecting UDP to $host:$port")

            socket = DatagramSocket().apply {
                soTimeout = SOCKET_TIMEOUT
                broadcast = false
            }
            remoteAddress = InetAddress.getByName(host)
            remotePort = port

            _connected.set(true)
            _lastReceiveTime.set(System.currentTimeMillis())
            _quality = 80
            isRunning.set(true)
            stateCallback?.invoke(CommLink.LinkState.CONNECTED)
            startReadLoop()
            Log.i(TAG, "UDP connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP connect failed", e)
            _connected.set(false)
            _quality = 0
            stateCallback?.invoke(CommLink.LinkState.ERROR)
            false
        }
    }

    override fun disconnect() {
        isRunning.set(false)
        readJob?.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP", e)
        }
        _connected.set(false)
        _quality = 0
        socket = null
        stateCallback?.invoke(CommLink.LinkState.DISCONNECTED)
        Log.d(TAG, "UDP disconnected")
    }

    override fun send(data: ByteArray): Boolean {
        if (!_connected.get()) return false
        return try {
            val addr = remoteAddress ?: return false
            val packet = DatagramPacket(data, data.size, addr, remotePort)
            socket?.send(packet)
            _lastSendTime.set(System.currentTimeMillis())
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP send failed", e)
            _quality = maxOf(0, _quality - 10)
            false
        }
    }

    override fun setDataCallback(callback: (ByteArray) -> Unit) {
        this.dataCallback = callback
    }

    override fun setStateCallback(callback: (CommLink.LinkState) -> Unit) {
        this.stateCallback = callback
    }

    private fun startReadLoop() {
        readJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            val sock = socket ?: return@launch

            while (isRunning.get() && _connected.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)

                    _lastReceiveTime.set(System.currentTimeMillis())
                    _quality = minOf(100, _quality + 1)
                    val data = packet.data.copyOf(packet.length)
                    dataCallback?.invoke(data)

                } catch (e: SocketTimeoutException) {
                    // 超时是正常的，继续循环
                    continue
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "UDP read error", e)
                        _quality = maxOf(0, _quality - 15)
                    }
                }
            }
        }
    }
}
