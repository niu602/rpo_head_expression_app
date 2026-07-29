/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 机器人头部控制管理器
 *
 * 功能：
 * 1. TCP长连接管理（连接/断开/自动重连）
 * 2. 4字节长度头 + Protobuf二进制 封包解包，解决粘包问题
 * 3. 统一使用 PhoneMessage 封装下发消息（oneof: face/command）
 * 4. 业务指令构建与发送（疑惑摇头、开心点头、停止动作、人脸追踪）
 * 5. 后台数据监听线程，接收设备传感器事件
 * 6. 所有网络操作在IO线程执行，不阻塞主线程
 *
 * 协议规范（按 robothead.proto）：
 * - 手机→板端：PhoneMessage（oneof: FaceOffset / HeadControlCmd）
 * - 板端→手机：DeviceFeedbackEvent
 * - 封包格式：[4字节大端长度][protobuf二进制数据]
 * - 长度字段：int32大端序，表示后续protobuf数据的字节数
 */
class HeadControlManager private constructor() {

    companion object {
        private const val TAG = "HeadControlManager"

        // 默认RDK板子IP与端口（可通过setTarget修改）
        // 按协议要求：人脸追踪端口 8765
        private const val DEFAULT_HOST = "10.43.2.69"
        private  const val DEFAULT_PORT = 8765

        // 重连间隔（毫秒）
        private const val RECONNECT_INTERVAL_MS = 3000L

        // 读取超时（毫秒），0表示无限等待
        private const val SO_TIMEOUT_MS = 0

        // 人脸追踪发送频率限制：约50fps（20ms间隔），保证头部跟踪流畅
        private const val FACE_SEND_INTERVAL_MS = 20L

        @Volatile
        private var instance: HeadControlManager? = null

        fun getInstance(): HeadControlManager {
            return instance ?: synchronized(this) {
                instance ?: HeadControlManager().also { instance = it }
            }
        }
    }

    // ========== 连接配置 ==========
    private var targetHost: String = DEFAULT_HOST
    private var targetPort: Int = DEFAULT_PORT

    // ========== 网络资源 ==========
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    // ========== 协程作用域（IO线程） ==========
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var connectJob: Job? = null
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null  // 待执行的重连任务

    // ========== 状态 ==========
    private val _connectionState = MutableStateFlow(HeadConnectionState.DISCONNECTED)
    val connectionState: StateFlow<HeadConnectionState> = _connectionState.asStateFlow()

    /**
     * 头部控制连接状态枚举
     */
    enum class HeadConnectionState {
        DISCONNECTED,  // 已断开
        CONNECTING,    // 连接中
        CONNECTED,     // 已连接
        ERROR          // 错误
    }

    @Volatile
    private var isManualStop = false // 是否手动停止，用于区分异常断开

    // ========== 人脸追踪发送节流 ==========
    @Volatile private var lastFaceSendTime = 0L

    // ========== 事件回调 ==========
    interface OnDeviceFeedbackListener {
        /**
         * 收到设备上报的传感器交互事件
         * @param event 设备反馈事件
         */
        fun onDeviceFeedback(event: DeviceFeedbackEvent)
    }

    private var feedbackListener: OnDeviceFeedbackListener? = null

    fun setOnDeviceFeedbackListener(listener: OnDeviceFeedbackListener?) {
        feedbackListener = listener
    }

    // ========== 连接管理 ==========

    /**
     * 设置目标设备地址
     * @param host RDK板子IP
     * @param port 端口号，默认8765
     */
    fun setTarget(host: String, port: Int = DEFAULT_PORT) {
        targetHost = host
        targetPort = port
        Log.d(TAG, "Target updated: $host:$port")
    }

    /**
     * 热更新TCP连接配置（运行时修改IP/端口，自动重连）
     * 支持配置热更新：不重启APP即可切换目标设备
     *
     * @param host 新的RDK板子IP
     * @param port 新的端口号
     */
    fun updateConfig(host: String, port: Int = DEFAULT_PORT) {
        val oldHost = targetHost
        val oldPort = targetPort
        targetHost = host
        targetPort = port
        Log.i(TAG, "Config hot-update: $oldHost:$oldPort -> $host:$port, reconnecting...")

        // 如果正在连接或已连接，先断开再重连
        if (_connectionState.value != HeadConnectionState.DISCONNECTED) {
            isManualStop = false // 标记为非手动停止，允许自动重连
            disconnectInternal()
        }
        // 自动重连到新地址
        connect()
    }

    /**
     * 内部断开连接（不设置手动停止标志，用于重连场景）
     */
    private fun disconnectInternal() {
        _connectionState.value = HeadConnectionState.DISCONNECTED
        receiveJob?.cancel()
        receiveJob = null
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Internal disconnect error: ${e.message}")
        } finally {
            outputStream = null
            inputStream = null
            socket = null
        }
    }

    /**
     * 建立TCP长连接
     * 异步执行，连接成功后自动启动接收线程
     */
    fun connect() {
        if (_connectionState.value == HeadConnectionState.CONNECTED) {
            Log.w(TAG, "Already connected, skip connect")
            return
        }
        isManualStop = false
        _connectionState.value = HeadConnectionState.CONNECTING

        // 取消之前的重连任务，避免重复建连
        reconnectJob?.cancel()
        connectJob?.cancel()

        connectJob = scope.launch {
            try {
                Log.d(TAG, "Connecting to $targetHost:$targetPort ...")
                socket = Socket(targetHost, targetPort).apply {
                    soTimeout = SO_TIMEOUT_MS
                    tcpNoDelay = true // 禁用Nagle算法，降低延迟
                    keepAlive = true
                }
                outputStream = DataOutputStream(socket!!.getOutputStream())
                inputStream = DataInputStream(socket!!.getInputStream())
                _connectionState.value = HeadConnectionState.CONNECTED
                Log.i(TAG, "Connected successfully to $targetHost:$targetPort")

                // 启动后台接收线程
                startReceiveLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}", e)
                _connectionState.value = HeadConnectionState.ERROR
                // 非手动停止时自动重连
                if (!isManualStop) {
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * 断开TCP连接
     * 手动断开不会触发自动重连
     */
    fun disconnect() {
        isManualStop = true
        _connectionState.value = HeadConnectionState.DISCONNECTED

        receiveJob?.cancel()
        receiveJob = null
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close socket error: ${e.message}", e)
        } finally {
            outputStream = null
            inputStream = null
            socket = null
        }
        Log.i(TAG, "Disconnected")
    }

    /**
     * 调度自动重连
     */
    private fun scheduleReconnect() {
        // 如果已经有重连任务在排队，直接返回，不重复调度
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            Log.d(TAG, "Schedule reconnect after ${RECONNECT_INTERVAL_MS}ms ...")
            delay(RECONNECT_INTERVAL_MS)
            if (!isManualStop && _connectionState.value != HeadConnectionState.CONNECTED) {
                connect()
            }
        }
    }

    // ========== 封包与发送 ==========

    /**
     * 发送统一封装消息 PhoneMessage（内部方法，IO线程执行）
     * 封包格式：4字节大端长度 + Protobuf二进制数据
     */
    private fun sendPhoneMessageInternal(message: PhoneMessage) {
        if (_connectionState.value != HeadConnectionState.CONNECTED || outputStream == null) {
            return
        }

        try {
            val protoBytes = message.toByteArray()
            // 4字节长度头：大端序int32
            val lengthBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(protoBytes.size)
                .array()

            synchronized(this) {
                outputStream?.write(lengthBytes)
                outputStream?.write(protoBytes)
                outputStream?.flush()
            }

            // 日志：区分人脸数据和指令
            when {
                message.face != null -> {
                    Log.v(TAG, "Sent face offset: ox=%.3f, oy=%.3f, conf=%.2f, size=%dB".format(
                        message.face.ox, message.face.oy, message.face.confidence, protoBytes.size
                    ))
                }
                message.command != null -> {
                    Log.d(TAG, "Sent command: emotion=${message.command.emotion}, moveMode=${message.command.moveMode}, size=${protoBytes.size}B")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send message failed: ${e.message}", e)
            // 发送失败，标记断开并触发重连
            _connectionState.value = HeadConnectionState.ERROR
            if (!isManualStop) {
                scheduleReconnect()
            }
        }
    }

    /**
     * 发送头部控制指令（异步，内部自动切到IO线程）
     * 封装为 PhoneMessage(command=cmd) 发送
     *
     * @param cmd 头部控制指令
     * @return 是否已提交发送（不代表最终发送成功）
     */
    fun sendCommand(cmd: HeadControlCmd): Boolean {
        if (_connectionState.value != HeadConnectionState.CONNECTED) {
            Log.w(TAG, "Not connected, cannot send command")
            return false
        }

        scope.launch {
            sendPhoneMessageInternal(PhoneMessage.command(cmd))
        }
        return true
    }

    /**
     * 发送人脸偏移数据（高频，约30fps）
     * 封装为 PhoneMessage(face=offset) 发送
     *
     * 内置简单时间节流：距上次发送不足间隔时直接丢弃（上层VisionManager已控制约30fps检测频率）
     *
     * @param ox 水平偏移 [-1,1]，0=居中，正=人脸在右
     * @param oy 垂直偏移 [-1,1]，0=居中，正=人脸在下
     * @param confidence 置信度 [0,1]，>0.5 板端才跟踪
     * @return 是否已提交发送
     */
    fun sendFaceOffset(ox: Float, oy: Float, confidence: Float): Boolean {
        if (_connectionState.value != HeadConnectionState.CONNECTED) {
            return false
        }

        val now = System.currentTimeMillis()
        // 简单节流：距上次发送不足间隔时跳过（丢弃旧帧，只发送最新数据）
        if (now - lastFaceSendTime < FACE_SEND_INTERVAL_MS) {
            return true
        }
        lastFaceSendTime = now

        val faceOffset = FaceOffset(ox = ox, oy = oy, confidence = confidence)
        scope.launch {
            sendPhoneMessageInternal(PhoneMessage.face(faceOffset))
        }
        return true
    }

    // ========== 业务指令构建函数 ==========

    /**
     * 构建并发送【疑惑情绪 + 摇头】指令
     * 触发疑惑表情时调用，头部自动摇头
     * 电机运动细节（角度、速度等）已由嵌入式封装，APP只需下发模式
     *
     * @return 是否发送成功
     */
    fun sendConfusionShake(): Boolean {
        val cmd = HeadControlCmd.newBuilder()
            .setEmotion(EmotionType.EMOTION_CONFUSION)
            .setMoveMode(HeadMoveMode.MOVE_SHAKE)
            .build()
        return sendCommand(cmd)
    }

    /**
     * 构建并发送【开心情绪 + 点头】指令
     * 触发开心表情时调用，头部自动点头
     *
     * @return 是否已提交发送
     */
    fun sendHappyNod(): Boolean {
        val cmd = HeadControlCmd.newBuilder()
            .setEmotion(EmotionType.EMOTION_HAPPY)
            .setMoveMode(HeadMoveMode.MOVE_NOD)
            .build()
        return sendCommand(cmd)
    }

    /**
     * 构建并发送【停止所有头部动作】指令
     * 表情回到待机/切换其他表情时调用
     *
     * @return 是否发送成功
     */
    fun sendStop(): Boolean {
        val cmd = HeadControlCmd.newBuilder()
            .setEmotion(EmotionType.EMOTION_NONE)
            .setMoveMode(HeadMoveMode.MOVE_NONE)
            .build()
        return sendCommand(cmd)
    }

    // ========== 后台数据接收循环 ==========

    /**
     * 启动后台接收线程
     * 持续读取设备回传的二进制数据并解析
     * 板端→手机：DeviceFeedbackEvent
     */
    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            Log.d(TAG, "Receive loop started")
            val buffer = ByteArray(4096)

            while (isActive && _connectionState.value == HeadConnectionState.CONNECTED && inputStream != null) {
                try {
                    // 第1步：读取4字节长度头（大端序）
                    val lengthBytes = ByteArray(4)
                    var totalRead = 0
                    while (totalRead < 4) {
                        val n = inputStream!!.read(lengthBytes, totalRead, 4 - totalRead)
                        if (n == -1) {
                            // 连接断开
                            throw java.net.SocketException("Connection closed by peer")
                        }
                        totalRead += n
                    }
                    val dataLength = ByteBuffer.wrap(lengthBytes)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int

                    if (dataLength <= 0 || dataLength > 65536) {
                        Log.w(TAG, "Invalid data length: $dataLength, skip")
                        continue
                    }

                    // 第2步：读取完整的protobuf数据
                    val dataBuffer = ByteArray(dataLength)
                    totalRead = 0
                    while (totalRead < dataLength) {
                        val n = inputStream!!.read(dataBuffer, totalRead, dataLength - totalRead)
                        if (n == -1) {
                            throw java.net.SocketException("Connection closed by peer")
                        }
                        totalRead += n
                    }

                    // 第3步：解析protobuf消息（设备反馈事件）
                    val event = DeviceFeedbackEvent.parseFrom(dataBuffer)
                    Log.d(TAG, "Received device feedback: eventCode=${event.eventCode}, value=${event.sensorValue}, online=${event.deviceOnline}")

                    // 回调到上层（切到主线程由调用方决定）
                    feedbackListener?.onDeviceFeedback(event)

                } catch (e: Exception) {
                    if (isActive && !isManualStop) {
                        Log.e(TAG, "Receive loop error: ${e.message}", e)
                    }
                    break
                }
            }

            // 接收循环结束，更新状态并触发重连
            if (!isManualStop) {
                _connectionState.value = HeadConnectionState.DISCONNECTED
                scheduleReconnect()
            }
            Log.d(TAG, "Receive loop ended")
        }
    }

    // ========== 资源释放 ==========

    /**
     * 释放所有资源
     */
    fun release() {
        disconnect()
        scope.cancel()
        instance = null
    }
}
