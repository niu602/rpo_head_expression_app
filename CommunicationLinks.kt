package com.eyebot.communication

import com.eyebot.protocol.EyeBotProto
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// ============================================================
//  TCP 主链路 - 承载关键指令，可靠传输
// ============================================================

class TcpCommunicationLink(
    private val host: String,
    private val port: Int
) {
    var onMessageCallback: ((EyeBotProto.EyeBotMessage) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    var isConnected: Boolean = false
        private set

    var lastMessageTime: Long = 0L
        private set

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private val sendMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = Socket(host, port).apply {
                tcpNoDelay = true
                keepAlive = true
                sendBufferSize = 65536
                receiveBufferSize = 65536
            }
            inputStream = DataInputStream(socket!!.getInputStream())
            outputStream = DataOutputStream(socket!!.getOutputStream())
            isConnected = true
            lastMessageTime = System.currentTimeMillis()
            startReceiveLoop()
            onConnectionChanged?.invoke(true)
            true
        } catch (e: Exception) {
            isConnected = false
            false
        }
    }

    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            try {
                while (isActive && isConnected) {
                    // Protobuf 长度前缀帧格式: 4字节长度 + protobuf数据
                    val length = inputStream?.readInt() ?: break
                    if (length <= 0 || length > MAX_MESSAGE_SIZE) continue

                    val buffer = ByteArray(length)
                    inputStream?.readFully(buffer)

                    val message = EyeBotProto.EyeBotMessage.parseFrom(buffer)
                    lastMessageTime = System.currentTimeMillis()
                    onMessageCallback?.invoke(message)
                }
            } catch (e: Exception) {
                // 连接断开
            } finally {
                handleDisconnect()
            }
        }
    }

    suspend fun sendMessage(message: EyeBotProto.EyeBotMessage): Boolean {
        if (!isConnected) return false
        return sendMutex.withLock {
            try {
                val data = message.toByteArray()
                withContext(Dispatchers.IO) {
                    outputStream?.writeInt(data.size)
                    outputStream?.write(data)
                    outputStream?.flush()
                }
                true
            } catch (e: Exception) {
                handleDisconnect()
                false
            }
        }
    }

    fun updateLastSeen() {
        lastMessageTime = System.currentTimeMillis()
    }

    private fun handleDisconnect() {
        if (!isConnected) return
        isConnected = false
        receiveJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        onConnectionChanged?.invoke(false)
    }

    fun disconnect() {
        handleDisconnect()
        scope.cancel()
    }

    companion object {
        private const val MAX_MESSAGE_SIZE = 65536 // 64KB 单条消息上限
    }
}

// ============================================================
//  UDP 状态上报链路 - 高频低延迟，不保证可靠
// ============================================================

class UdpStatusLink(
    private val host: String,
    private val port: Int,
    private val reportIntervalMs: Long
) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false

    fun start() {
        try {
            address = InetAddress.getByName(host)
            socket = DatagramSocket()
            running = true
        } catch (e: Exception) {
            // UDP启动失败不影响主链路
        }
    }

    fun sendStatusReport(message: EyeBotProto.EyeBotMessage) {
        if (!running || socket == null) return
        scope.launch {
            try {
                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, address, port)
                socket?.send(packet)
            } catch (e: Exception) {
                // UDP丢包正常，不做重连处理
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}

// ============================================================
//  USB 串口备用链路
// ============================================================

class UsbSerialLink {
    var onMessageCallback: ((EyeBotProto.EyeBotMessage) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    var isConnected: Boolean = false
        private set

    var lastMessageTime: Long = 0L
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null

    // USB串口相关 (基于UsbSerialDriver库)
    // 实际项目需引入 com.github.mik3y:usb-serial-for-android

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            // TODO: 实际实现需枚举USB设备，打开串口
            // 此处为框架占位
            isConnected = false // 默认未连接，需实际硬件接入
            false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendMessage(message: EyeBotProto.EyeBotMessage): Boolean {
        if (!isConnected) return false
        return try {
            val data = message.toByteArray()
            // 串口帧格式: 0xAA 0x55 + 长度(2字节) + 数据 + 校验和
            // TODO: 实际写入串口
            true
        } catch (e: Exception) {
            false
        }
    }

    fun updateLastSeen() {
        lastMessageTime = System.currentTimeMillis()
    }

    fun disconnect() {
        isConnected = false
        receiveJob?.cancel()
        scope.cancel()
        onConnectionChanged?.invoke(false)
    }
}

// ============================================================
//  BLE 备用链路 (GATT Peripheral模式)
// ============================================================

class BleCommunicationLink {
    var onMessageCallback: ((EyeBotProto.EyeBotMessage) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    var isConnected: Boolean = false
        private set

    var lastMessageTime: Long = 0L
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // MTU协商后最大512字节，超大数据需分包
    private val mtu = 512
    private val sendQueue = ArrayDeque<ByteArray>()

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            // TODO: 实际实现需启动BluetoothGattServer广播
            // 等待中心设备连接
            isConnected = false // 需外部设备连接后变为true
            false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendMessage(message: EyeBotProto.EyeBotMessage): Boolean {
        if (!isConnected) return false
        return try {
            val data = message.toByteArray()
            if (data.size <= mtu - 3) {
                // 单包发送
                sendNotification(data)
            } else {
                // 分包发送
                sendFragmented(data)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendNotification(data: ByteArray) {
        // TODO: 通过GATT特征值Notify发送
    }

    private fun sendFragmented(data: ByteArray) {
        // 简单分包: 首包包头+序号，后续包序号+数据
        // 实际项目建议使用标准BLE分包协议如NUS
    }

    // 收到写入特征值数据时调用
    fun onDataReceived(data: ByteArray) {
        lastMessageTime = System.currentTimeMillis()
        try {
            val message = EyeBotProto.EyeBotMessage.parseFrom(data)
            onMessageCallback?.invoke(message)
        } catch (e: Exception) {
            // 分包数据，需要重组
        }
    }

    fun updateLastSeen() {
        lastMessageTime = System.currentTimeMillis()
    }

    fun disconnect() {
        isConnected = false
        scope.cancel()
        onConnectionChanged?.invoke(false)
    }
}
