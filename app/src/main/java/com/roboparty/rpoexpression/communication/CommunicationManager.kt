/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.roboparty.rpoexpression.expression.ExpressionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主控通信管理器
 * 实现与roboto_origin主控的TCP Socket通信
 *
 * 功能：
 * 1. 自动重连机制
 * 2. JSON指令解析
 * 3. 心跳保活
 * 4. 状态上报
 * 5. 预留USB串口接口
 */
class CommunicationManager private constructor() {

    companion object {
        private const val TAG = "CommManager"
        private const val DEFAULT_PORT = 8899 // 与接口规范一致
        private const val CONNECT_TIMEOUT = 5000
        private const val RECONNECT_DELAY = 3000L
        private const val HEARTBEAT_INTERVAL = 3000L // 3秒心跳，与接口规范一致
        private const val READ_BUFFER_SIZE = 1024

        @Volatile
        private var instance: CommunicationManager? = null

        fun getInstance(): CommunicationManager {
            return instance ?: synchronized(this) {
                instance ?: CommunicationManager().also { instance = it }
            }
        }
    }

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    // 依赖
    private val gson = Gson()
    private val expressionManager = ExpressionManager.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Socket相关
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val isRunning = AtomicBoolean(false)
    private val sequenceNumber = AtomicInteger(0)

    // 配置
    private var masterHost: String = "192.168.1.100"
    private var masterPort: Int = DEFAULT_PORT

    // 命令回调
    private var commandListener: ((ControlCommand) -> Unit)? = null

    /**
     * 设置主控地址
     */
    fun setMasterAddress(host: String, port: Int = DEFAULT_PORT) {
        this.masterHost = host
        this.masterPort = port
    }

    /**
     * 设置命令监听器
     */
    fun setCommandListener(listener: (ControlCommand) -> Unit) {
        this.commandListener = listener
    }

    /**
     * 启动通信
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Starting communication manager")
            scope.launch {
                connectLoop()
            }
            startHeartbeat()
        }
    }

    /**
     * 停止通信
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping communication manager")
            scope.coroutineContext.cancelChildren()
            closeConnection()
        }
    }

    /**
     * 发送指令
     */
    fun sendCommand(command: ControlCommand): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send command, not connected")
            return false
        }

        return try {
            val json = gson.toJson(command)
            Log.d(TAG, "Sending: $json")
            writer?.println(json)
            writer?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
            false
        }
    }

    /**
     * 发送状态上报
     */
    fun sendStatus(brightness: Float, batteryLevel: Int) {
        val status = DeviceStatus(
            currentExpression = expressionManager.expressionState.value.type.name.lowercase(),
            brightness = brightness,
            batteryLevel = batteryLevel
        )
        sendCommand(
            ControlCommand(
                command = ControlCommand.CMD_STATUS,
                source = "expression_app",
                sequence = sequenceNumber.incrementAndGet(),
                data = gson.fromJson(gson.toJson(status), Map::class.java) as Map<String, Any>
            )
        )
    }

    /**
     * 连接循环 - 自动重连
     */
    private suspend fun connectLoop() {
        while (isRunning.get()) {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                Log.d(TAG, "Connecting to $masterHost:$masterPort")

                connect()
                _connectionState.value = ConnectionState.CONNECTED
                Log.i(TAG, "Connected to master")

                // 连接成功后开始读取消息
                readLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionState.value = ConnectionState.ERROR
                closeConnection()
            }

            // 重连延迟
            if (isRunning.get()) {
                delay(RECONNECT_DELAY)
            }
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 建立TCP连接
     */
    private fun connect() {
        socket = Socket().apply {
            connect(InetSocketAddress(masterHost, masterPort), CONNECT_TIMEOUT)
            soTimeout = 0
            tcpNoDelay = true
            keepAlive = true
        }

        socket?.let { sock ->
            writer = PrintWriter(sock.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        }
    }

    /**
     * 读取消息循环
     */
    private suspend fun readLoop() {
        val buffer = CharArray(READ_BUFFER_SIZE)
        val stringBuilder = StringBuilder()

        while (isRunning.get() && socket?.isConnected == true) {
            try {
                val charsRead = reader?.read(buffer) ?: -1
                if (charsRead == -1) {
                    throw Exception("Connection closed by remote")
                }

                stringBuilder.append(buffer, 0, charsRead)

                // 按行解析JSON
                var newlineIndex: Int
                while (stringBuilder.indexOf('\n').also { newlineIndex = it } != -1) {
                    val line = stringBuilder.substring(0, newlineIndex).trim()
                    stringBuilder.delete(0, newlineIndex + 1)

                    if (line.isNotEmpty()) {
                        processCommand(line)
                    }
                }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Read error", e)
                    throw e
                }
            }
        }
    }

    /**
     * 处理接收到的指令
     */
    private fun processCommand(json: String) {
        Log.d(TAG, "Received: $json")

        try {
            val command = gson.fromJson(json, ControlCommand::class.java)

            // 先通知监听器
            commandListener?.invoke(command)

            // 内置指令处理
            when (command.command) {
                ControlCommand.CMD_PING -> {
                    // 回复pong
                    sendCommand(
                        ControlCommand(
                            command = "pong",
                            source = "expression_app",
                            sequence = command.sequence
                        )
                    )
                }

                ControlCommand.CMD_EXPRESSION -> {
                    // 切换表情，支持强度参数
                    command.toExpressionType()?.let { exprType ->
                        val intensity = command.toIntensity()
                        expressionManager.requestExpression(
                            type = exprType,
                            intensity = intensity,
                            duration = command.duration,
                            force = command.force
                        )
                    }
                }

                ControlCommand.CMD_SEQUENCE -> {
                    // 播放表情序列
                    command.toExpressionSequence()?.let { sequence ->
                        expressionManager.playExpressionSequence(sequence)
                    }
                }

                ControlCommand.CMD_CLEAR -> {
                    // 清除表情，回到待机
                    expressionManager.clearExpression()
                }

                ControlCommand.CMD_SET_STYLE -> {
                    // 设置渲染风格，支持动态切换风格包
                    command.toStyle()?.let { style ->
                        Log.d(TAG, "Setting expression style to: $style")
                        expressionManager.setStyle(style)
                    }
                }

                ControlCommand.CMD_SET_INTENSITY -> {
                    // 设置全局表情强度（后续表情使用此强度）
                    val intensity = command.toIntensity()
                    Log.d(TAG, "Setting global expression intensity to: $intensity")
                    // 对当前表情也应用强度
                    val currentState = expressionManager.expressionState.value
                    expressionManager.requestExpression(
                        type = currentState.type,
                        intensity = intensity,
                        force = true
                    )
                }
            }

        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Invalid JSON: $json", e)
        }
    }

    /**
     * 心跳保活
     */
    private fun startHeartbeat() {
        scope.launch {
            while (isRunning.get()) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    sendCommand(
                        ControlCommand(
                            command = ControlCommand.CMD_PING,
                            source = "expression_app",
                            sequence = sequenceNumber.incrementAndGet()
                        )
                    )
                }
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    /**
     * 关闭连接
     */
    private fun closeConnection() {
        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection", e)
        } finally {
            reader = null
            writer = null
            socket = null
        }
    }

    /**
     * USB串口通信预留接口
     * 后续可扩展UsbManager实现
     */
    interface SerialCommunication {
        fun connect(baudRate: Int = 115200): Boolean
        fun send(data: ByteArray)
        fun disconnect()
    }
}
