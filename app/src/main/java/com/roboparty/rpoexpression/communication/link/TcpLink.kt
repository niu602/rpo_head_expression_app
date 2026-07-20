/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication.link

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP主链路实现
 * 可靠指令传输，承载控制指令、表情切换等关键数据
 */
class TcpLink : CommLink {

    companion object {
        private const val TAG = "TcpLink"
        private const val CONNECT_TIMEOUT = 3000
        private const val READ_BUFFER_SIZE = 2048
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val isRunning = AtomicBoolean(false)
    private val _connected = AtomicBoolean(false)
    private val _lastReceiveTime = AtomicLong(System.currentTimeMillis())
    private val _lastSendTime = AtomicLong(0)
    private var _quality = 100

    private var dataCallback: ((ByteArray) -> Unit)? = null
    private var stateCallback: ((CommLink.LinkState) -> Unit)? = null

    override fun getType() = CommLink.LinkType.LINK_TCP
    override fun getPriority() = 1  // 最高优先级（主链路）
    override fun isConnected() = _connected.get()
    override fun getQuality() = _quality
    override fun getLastReceiveTime() = _lastReceiveTime.get()
    override fun getLastSendTime() = _lastSendTime.get()

    override suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            stateCallback?.invoke(CommLink.LinkState.CONNECTING)
            Log.d(TAG, "Connecting TCP to $host:$port")

            socket = Socket().apply {
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
                soTimeout = 0
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = READ_BUFFER_SIZE
                sendBufferSize = READ_BUFFER_SIZE
            }

            socket?.let { sock ->
                outputStream = sock.getOutputStream()
                _connected.set(true)
                _lastReceiveTime.set(System.currentTimeMillis())
                _quality = 100
                isRunning.set(true)
                stateCallback?.invoke(CommLink.LinkState.CONNECTED)
                startReadLoop()
                Log.i(TAG, "TCP connected to $host:$port")
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "TCP connect failed", e)
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
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TCP", e)
        }
        _connected.set(false)
        _quality = 0
        outputStream = null
        socket = null
        stateCallback?.invoke(CommLink.LinkState.DISCONNECTED)
        Log.d(TAG, "TCP disconnected")
    }

    override fun send(data: ByteArray): Boolean {
        if (!_connected.get()) return false
        return try {
            outputStream?.apply {
                // 2字节长度前缀 + 数据
                val len = data.size
                write(byteArrayOf(
                    ((len shr 8) and 0xFF).toByte(),
                    (len and 0xFF).toByte()
                ))
                write(data)
                flush()
            }
            _lastSendTime.set(System.currentTimeMillis())
            true
        } catch (e: Exception) {
            Log.e(TAG, "TCP send failed", e)
            _quality = maxOf(0, _quality - 20)
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
            val inputStream = socket?.getInputStream()?.let { BufferedInputStream(it) } ?: return@launch

            while (isRunning.get() && _connected.get()) {
                try {
                    // 先读2字节长度前缀
                    val lenHigh = inputStream.read()
                    val lenLow = inputStream.read()
                    if (lenHigh == -1 || lenLow == -1) {
                        throw Exception("Connection closed")
                    }
                    val dataLen = (lenHigh shl 8) or lenLow
                    if (dataLen <= 0 || dataLen > READ_BUFFER_SIZE * 4) {
                        continue
                    }

                    // 读取完整数据
                    var readTotal = 0
                    val data = ByteArray(dataLen)
                    while (readTotal < dataLen) {
                        val n = inputStream.read(data, readTotal, dataLen - readTotal)
                        if (n == -1) throw Exception("Connection closed during read")
                        readTotal += n
                    }

                    _lastReceiveTime.set(System.currentTimeMillis())
                    _quality = minOf(100, _quality + 2)
                    dataCallback?.invoke(data)

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "TCP read error", e)
                        _connected.set(false)
                        _quality = 0
                        stateCallback?.invoke(CommLink.LinkState.ERROR)
                    }
                    break
                }
            }
        }
    }
}
