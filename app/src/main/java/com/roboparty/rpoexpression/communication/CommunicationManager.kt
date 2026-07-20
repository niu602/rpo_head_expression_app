/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

import android.util.Log
import com.roboparty.rpoexpression.config.ConfigManager
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.communication.link.CommLink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主控通信管理器 v2.0
 *
 * 架构升级：
 * - 多链路冗余：TCP主链路 + UDP高频链路 + USB串口备用 + BLE备用
 * - Protobuf序列化：替代原JSON，传输效率提升≥50%
 * - 故障自动切换：<1秒完成链路切换
 * - 感知事件结构化上行：触摸/人脸/IMU事件Protobuf封装
 * - 向下兼容：保留原有ControlCommand JSON指令接口
 */
class CommunicationManager private constructor() {

    companion object {
        private const val TAG = "CommManager"
        private const val DEFAULT_TCP_PORT = 8899
        private const val DEFAULT_UDP_PORT = 8900
        private const val HEARTBEAT_INTERVAL = 3000L
        private const val STATUS_REPORT_INTERVAL = 1000L

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

    // 核心组件
    private val multiLinkManager = MultiLinkManager.getInstance()
    private val perceptionManager = PerceptionEventManager.getInstance()
    private val codec = ProtobufCodec.getInstance()
    private val expressionManager = ExpressionManager.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    // 配置
    private var masterHost: String = "192.168.1.100"
    private var tcpPort: Int = DEFAULT_TCP_PORT
    private var udpPort: Int = DEFAULT_UDP_PORT

    // 命令回调（兼容原有接口）
    private var commandListener: ((ControlCommand) -> Unit)? = null

    // 统计
    private var startTime: Long = 0

    /**
     * 设置主控地址
     */
    fun setMasterAddress(host: String, port: Int = DEFAULT_TCP_PORT) {
        this.masterHost = host
        this.tcpPort = port
        multiLinkManager.configure(host, tcpPort, udpPort)
    }

    /**
     * 设置命令监听器（兼容原有JSON接口）
     */
    fun setCommandListener(listener: (ControlCommand) -> Unit) {
        this.commandListener = listener
    }

    /**
     * 启动通信
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Starting communication manager v2.0 (multi-link + protobuf)")
            startTime = System.currentTimeMillis()

            // 配置多链路
            multiLinkManager.configure(masterHost, tcpPort, udpPort)

            // 设置数据接收回调
            multiLinkManager.setDataCallback { data ->
                scope.launch { processReceivedData(data) }
            }

            // 设置链路切换回调
            multiLinkManager.setLinkSwitchCallback { old, new, reason ->
                Log.i(TAG, "Link switched: $old -> $new, reason=$reason")
                // 发送链路切换通知
                val switchMsg = codec.encodeLinkSwitch(
                    msgId = multiLinkManager.nextMsgId(),
                    seq = multiLinkManager.nextSeq(),
                    oldLink = old.value,
                    newLink = new.value,
                    reason = reason
                )
                multiLinkManager.send(switchMsg)
            }

            // 启动多链路
            multiLinkManager.start()

            // 启动心跳
            startHeartbeat()

            // 启动状态上报（UDP高频）
            startStatusReport()

            // 监听连接状态
            scope.launch {
                multiLinkManager.isConnected.collect { connected ->
                    _connectionState.value = if (connected) {
                        ConnectionState.CONNECTED
                    } else {
                        ConnectionState.CONNECTING
                    }
                }
            }
        }
    }

    /**
     * 停止通信
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping communication manager")
            scope.coroutineContext.cancelChildren()
            multiLinkManager.stop()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * 发送指令（兼容原有接口，内部转Protobuf）
     */
    fun sendCommand(command: ControlCommand): Boolean {
        if (!multiLinkManager.isConnected.value) {
            Log.w(TAG, "Cannot send command, not connected")
            return false
        }

        return try {
            // 编码为Protobuf发送
            val data = when (command.command) {
                ControlCommand.CMD_PING -> codec.encodeHeartbeat(
                    multiLinkManager.nextMsgId(),
                    multiLinkManager.nextSeq()
                )
                ControlCommand.CMD_STATUS -> {
                    // 状态走UDP
                    perceptionManager.reportStatus(
                        batteryLevel = 80,
                        fps = 60,
                        currentExpr = 0,
                        uptimeMs = System.currentTimeMillis() - startTime
                    )
                    return true
                }
                else -> {
                    // 其他指令通过TCP发送
                    Log.d(TAG, "Sending protobuf command: ${command.command}")
                    codec.encodeExpressionSet(
                        msgId = multiLinkManager.nextMsgId(),
                        seq = multiLinkManager.nextSeq(),
                        exprType = mapCommandToExprType(command.command),
                        intensity = command.toIntensity(),
                        durationMs = command.duration ?: 0,
                        force = command.force
                    )
                }
            }

            multiLinkManager.send(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
            false
        }
    }

    /**
     * 发送状态上报（兼容原有接口）
     */
    fun sendStatus(brightness: Float, batteryLevel: Int) {
        perceptionManager.reportStatus(
            batteryLevel = batteryLevel,
            fps = 60,
            currentExpr = mapExprNameToType(expressionManager.expressionState.value.type.name),
            uptimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 上报触摸事件（新增API）
     */
    fun reportTouch(x: Float, y: Float, action: Int, pointerCount: Int = 1) {
        perceptionManager.reportTouchEvent(x, y, action, pointerCount)
    }

    /**
     * 上报人脸事件（新增API）
     */
    fun reportFace(hasFace: Boolean, faceCount: Int, offsetX: Float, offsetY: Float, faceSize: Float, smileProb: Float) {
        perceptionManager.reportFaceEvent(hasFace, faceCount, offsetX, offsetY, faceSize, smileProb)
    }

    /**
     * 上报IMU事件（新增API）
     */
    fun reportImu(pitch: Float, roll: Float, yaw: Float, acceleration: Float, action: Int) {
        perceptionManager.reportImuEvent(pitch, roll, yaw, acceleration, action)
    }

    /**
     * 获取当前活跃链路类型
     */
    fun getActiveLinkType(): CommLink.LinkType = multiLinkManager.getActiveLinkType()

    /**
     * 处理接收到的数据（Protobuf解码，兼容JSON fallback）
     */
    private fun processReceivedData(data: ByteArray) {
        try {
            // 尝试Protobuf解码
            val reader = ProtobufCodec.Reader(data)
            var cmdType = 0
            var exprType = 0
            var intensity = 100
            var duration = 0L
            var force = false

            while (!reader.isAtEnd()) {
                val (field, wireType) = reader.readTag()
                when (field) {
                    2 -> cmdType = reader.readEnum() // cmd_type
                    10 -> { // expression_set
                        // 解析表情设置
                        val innerReader = ProtobufCodec.Reader(reader.readBytes())
                        while (!innerReader.isAtEnd()) {
                            val (f, wt) = innerReader.readTag()
                            when (f) {
                                5 -> exprType = innerReader.readEnum()
                                6 -> intensity = innerReader.readEnum()
                                8 -> duration = innerReader.readVarint()
                                else -> innerReader.skipField(wt)
                            }
                        }
                    }
                    else -> reader.skipField(wireType)
                }
            }

            // 处理指令
            handleProtobufCommand(cmdType, exprType, intensity, duration, force)

        } catch (e: Exception) {
            // Protobuf解码失败，尝试JSON（兼容旧协议）
            Log.w(TAG, "Protobuf decode failed, trying JSON fallback", e)
            try {
                val json = String(data, Charsets.UTF_8).trim()
                if (json.startsWith("{")) {
                    processJsonCommand(json)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Both protobuf and JSON decode failed", e2)
            }
        }
    }

    /**
     * 处理Protobuf指令
     */
    private fun handleProtobufCommand(cmdType: Int, exprType: Int, intensity: Int, duration: Long, force: Boolean) {
        when (cmdType) {
            1 -> { // CMD_EXPRESSION_SET
                val type = mapProtoExprToType(exprType)
                if (type != null) {
                    expressionManager.requestExpression(
                        type = type,
                        intensity = intensity.coerceIn(0, 100),
                        duration = if (duration > 0) duration else null,
                        force = force
                    )
                    // 通知监听器（兼容）
                    commandListener?.invoke(ControlCommand(
                        command = ControlCommand.CMD_EXPRESSION,
                        type = type.name.lowercase(),
                        duration = duration,
                        force = force
                    ))
                }
            }
            7 -> { // CMD_HEARTBEAT
                // 回复心跳
                val pong = codec.encodeAck(
                    msgId = multiLinkManager.nextMsgId(),
                    seq = multiLinkManager.nextSeq(),
                    ackMsgId = 0
                )
                multiLinkManager.send(pong)
            }
        }
    }

    /**
     * 处理JSON指令（兼容旧协议）
     */
    private fun processJsonCommand(json: String) {
        try {
            val gson = com.google.gson.Gson()
            // 先解析为Map判断指令类型
            val cmdMap = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            val cmd = cmdMap?.get("cmd") as? String

            when (cmd) {
                // 配置热更新指令
                "config" -> {
                    val configJson = cmdMap["config"]?.let { gson.toJson(it) }
                    if (configJson != null) {
                        val success = ConfigManager.getInstance().updateFromJson(configJson, persist = true)
                        Log.i(TAG, "Config update from TCP: success=$success")
                        // 回复ACK
                        val ack = codec.encodeAck(
                            msgId = multiLinkManager.nextMsgId(),
                            seq = multiLinkManager.nextSeq(),
                            ackMsgId = 0,
                            code = if (success) 0 else 1
                        )
                        multiLinkManager.send(ack)
                    }
                }
                // 重置配置指令
                "reset_config" -> {
                    ConfigManager.getInstance().resetToDefault()
                    Log.i(TAG, "Config reset to default from TCP command")
                }
                // 普通表情控制指令
                else -> {
                    val command = gson.fromJson(json, ControlCommand::class.java)
                    commandListener?.invoke(command)

                    when (command.command) {
                        ControlCommand.CMD_PING -> {
                            sendCommand(ControlCommand(
                                command = "pong",
                                source = "expression_app"
                            ))
                        }
                        ControlCommand.CMD_EXPRESSION -> {
                            command.toExpressionType()?.let { exprType ->
                                expressionManager.requestExpression(
                                    type = exprType,
                                    intensity = command.toIntensity(),
                                    duration = command.duration,
                                    force = command.force
                                )
                            }
                        }
                        ControlCommand.CMD_CLEAR -> {
                            expressionManager.clearExpression()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON command process failed", e)
        }
    }

    /**
     * 心跳保活
     */
    private fun startHeartbeat() {
        scope.launch {
            while (isRunning.get()) {
                if (multiLinkManager.isConnected.value) {
                    val ping = codec.encodeHeartbeat(
                        msgId = multiLinkManager.nextMsgId(),
                        seq = multiLinkManager.nextSeq()
                    )
                    multiLinkManager.send(ping)
                }
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    /**
     * 周期性状态上报（UDP高频）
     */
    private fun startStatusReport() {
        scope.launch {
            while (isRunning.get()) {
                if (multiLinkManager.isConnected.value) {
                    sendStatus(1f, 80)
                }
                delay(STATUS_REPORT_INTERVAL)
            }
        }
    }

    // ========== 类型映射 ==========

    private fun mapCommandToExprType(cmd: String): Int {
        return when (cmd) {
            ControlCommand.CMD_EXPRESSION -> 0
            else -> 0
        }
    }

    private fun mapExprNameToType(name: String): Int {
        return when (name.uppercase()) {
            "IDLE" -> 0
            "BLINK" -> 1
            "SLEEPING" -> 2
            "THINKING" -> 3
            "RECOGNIZING" -> 4
            "RESPONSE" -> 5
            "SPEAKING" -> 6
            "HAPPY" -> 7
            "CONFUSED" -> 8
            "SURPRISED" -> 9
            "SAD" -> 10
            "ANGRY" -> 11
            "SLEEPY" -> 12
            "SHY" -> 13
            "LOW_BATTERY" -> 14
            "CHARGING" -> 15
            "ALERT" -> 16
            "UPDATING" -> 17
            else -> 0
        }
    }

    private fun mapProtoExprToType(protoType: Int): com.roboparty.rpoexpression.expression.ExpressionType? {
        return when (protoType) {
            0 -> com.roboparty.rpoexpression.expression.ExpressionType.IDLE
            1 -> com.roboparty.rpoexpression.expression.ExpressionType.BLINK
            2 -> com.roboparty.rpoexpression.expression.ExpressionType.SLEEPING
            3 -> com.roboparty.rpoexpression.expression.ExpressionType.THINKING
            4 -> com.roboparty.rpoexpression.expression.ExpressionType.RECOGNIZING
            5 -> com.roboparty.rpoexpression.expression.ExpressionType.RESPONSE
            6 -> com.roboparty.rpoexpression.expression.ExpressionType.SPEAKING
            7 -> com.roboparty.rpoexpression.expression.ExpressionType.HAPPY
            8 -> com.roboparty.rpoexpression.expression.ExpressionType.CONFUSED
            9 -> com.roboparty.rpoexpression.expression.ExpressionType.SURPRISED
            10 -> com.roboparty.rpoexpression.expression.ExpressionType.SAD
            11 -> com.roboparty.rpoexpression.expression.ExpressionType.ANGRY
            12 -> com.roboparty.rpoexpression.expression.ExpressionType.SLEEPY
            13 -> com.roboparty.rpoexpression.expression.ExpressionType.SHY
            14 -> com.roboparty.rpoexpression.expression.ExpressionType.LOW_BATTERY
            15 -> com.roboparty.rpoexpression.expression.ExpressionType.CHARGING
            16 -> com.roboparty.rpoexpression.expression.ExpressionType.ALERT
            17 -> com.roboparty.rpoexpression.expression.ExpressionType.UPDATING
            else -> null
        }
    }
}
