package com.eyebot.communication

import com.eyebot.protocol.EyeBotProto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通信管理器 - 多链路冗余核心
 *
 * 架构:
 *   TCP 主链路 (关键指令 + 可靠传输)
 *   UDP 辅链路 (高频状态上报, 低延迟)
 *   USB 串口 备用链路
 *   BLE 备用链路
 *
 * 切换策略:
 *   正常: TCP + UDP 同时工作
 *   TCP 断开 1s 内: 自动切 USB 串口
 *   USB 也不可用: 切 BLE
 *   主链路恢复: 自动切回 TCP
 */
class CommunicationManager private constructor() {

    // 链路状态
    private val _activeLink = MutableStateFlow(LinkType.LINK_TCP)
    val activeLink: StateFlow<LinkType> = _activeLink

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // 各链路实例
    private var tcpLink: TcpCommunicationLink? = null
    private var udpLink: UdpStatusLink? = null
    private var usbLink: UsbSerialLink? = null
    private var bleLink: BleCommunicationLink? = null

    // 消息ID生成器
    private val msgIdGenerator = AtomicInteger(0)

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 链路健康检测
    private var linkMonitorJob: Job? = null

    // 消息回调
    var onMessageReceived: ((EyeBotProto.EyeBotMessage) -> Unit)? = null
    var onLinkSwitched: ((LinkType, LinkType) -> Unit)? = null

    /**
     * 初始化所有链路
     */
    fun initialize(config: CommConfig) {
        // TCP 主链路
        tcpLink = TcpCommunicationLink(config.tcpHost, config.tcpPort).apply {
            onMessageCallback = { handleIncomingMessage(it, LinkType.LINK_TCP) }
            onConnectionChanged = { connected ->
                if (!connected && _activeLink.value == LinkType.LINK_TCP) {
                    scheduleFailover()
                } else if (connected && _activeLink.value != LinkType.LINK_TCP) {
                    // 主链路恢复，切回
                    switchToMainLink()
                }
            }
        }

        // UDP 状态上报链路
        udpLink = UdpStatusLink(config.udpHost, config.udpPort, config.udpReportIntervalMs)

        // USB 串口备用链路
        usbLink = UsbSerialLink().apply {
            onMessageCallback = { handleIncomingMessage(it, LinkType.LINK_USB_SERIAL) }
            onConnectionChanged = { connected ->
                if (connected && needFailover()) {
                    performFailover(LinkType.LINK_USB_SERIAL)
                }
            }
        }

        // BLE 备用链路
        bleLink = BleCommunicationLink().apply {
            onMessageCallback = { handleIncomingMessage(it, LinkType.LINK_BLE) }
            onConnectionChanged = { connected ->
                if (connected && needFailover()) {
                    performFailover(LinkType.LINK_BLE)
                }
            }
        }

        startLinkMonitor()
    }

    /**
     * 连接主链路
     */
    suspend fun connect(): Boolean {
        val tcpSuccess = tcpLink?.connect() ?: false
        if (tcpSuccess) {
            _activeLink.value = LinkType.LINK_TCP
            _isConnected.value = true
            udpLink?.start()
            startStatusReport()
        } else {
            // TCP连不上，尝试备用
            tryBackupLinks()
        }
        return _isConnected.value
    }

    /**
     * 发送关键指令 (走当前活跃链路, TCP优先)
     */
    suspend fun sendCommand(message: EyeBotProto.EyeBotMessage): Boolean {
        if (!_isConnected.value) return false

        return when (_activeLink.value) {
            LinkType.LINK_TCP -> tcpLink?.sendMessage(message) ?: false
            LinkType.LINK_USB_SERIAL -> usbLink?.sendMessage(message) ?: false
            LinkType.LINK_BLE -> bleLink?.sendMessage(message) ?: false
            else -> false
        }
    }

    /**
     * 发送高频状态 (走UDP, 低优先级)
     */
    fun sendStatusReport(report: EyeBotProto.StatusReport) {
        udpLink?.sendStatusReport(buildStatusMessage(report))
    }

    /**
     * 上报感知事件
     */
    fun sendPerceptionEvent(event: EyeBotProto.EyeBotMessage) {
        // 感知事件走当前活跃链路发送
        scope.launch {
            sendCommand(event)
        }
    }

    /**
     * 链路健康监测
     * 检测TCP心跳超时，触发故障转移
     */
    private fun startLinkMonitor() {
        linkMonitorJob = scope.launch {
            while (isActive) {
                delay(500) // 每500ms检测一次

                if (_activeLink.value == LinkType.LINK_TCP) {
                    val lastSeen = tcpLink?.lastMessageTime ?: 0L
                    val timeout = System.currentTimeMillis() - lastSeen
                    if (timeout > LINK_TIMEOUT_MS && tcpLink?.isConnected == false) {
                        // TCP超时，触发故障转移
                        scheduleFailover()
                    }
                }
            }
        }
    }

    /**
     * 调度故障转移 (1s内完成切换)
     */
    private fun scheduleFailover() {
        scope.launch {
            // 先尝试USB串口
            val usbReady = usbLink?.isConnected == true || usbLink?.connect() == true
            if (usbReady) {
                performFailover(LinkType.LINK_USB_SERIAL)
                return@launch
            }

            // USB不行，尝试BLE
            val bleReady = bleLink?.isConnected == true || bleLink?.connect() == true
            if (bleReady) {
                performFailover(LinkType.LINK_BLE)
                return@launch
            }

            // 所有链路都断了
            _isConnected.value = false
        }
    }

    private fun needFailover(): Boolean {
        return _activeLink.value == LinkType.LINK_TCP && tcpLink?.isConnected == false
    }

    private fun performFailover(targetLink: LinkType) {
        val oldLink = _activeLink.value
        if (oldLink == targetLink) return

        _activeLink.value = targetLink
        _isConnected.value = true

        // 发送链路切换通知
        val switchEvent = buildLinkSwitchMessage(oldLink, targetLink)
        scope.launch {
            sendCommand(switchEvent)
        }

        onLinkSwitched?.invoke(oldLink, targetLink)
    }

    private fun switchToMainLink() {
        val oldLink = _activeLink.value
        _activeLink.value = LinkType.LINK_TCP

        val switchEvent = buildLinkSwitchMessage(oldLink, LinkType.LINK_TCP)
        scope.launch {
            sendCommand(switchEvent)
        }

        onLinkSwitched?.invoke(oldLink, LinkType.LINK_TCP)
    }

    private suspend fun tryBackupLinks() {
        // 依次尝试备用链路
        usbLink?.connect()
        if (usbLink?.isConnected == true) {
            performFailover(LinkType.LINK_USB_SERIAL)
            return
        }

        bleLink?.connect()
        if (bleLink?.isConnected == true) {
            performFailover(LinkType.LINK_BLE)
            return
        }
    }

    private fun handleIncomingMessage(msg: EyeBotProto.EyeBotMessage, fromLink: LinkType) {
        // 更新对应链路的最后消息时间
        when (fromLink) {
            LinkType.LINK_TCP -> tcpLink?.updateLastSeen()
            LinkType.LINK_USB_SERIAL -> usbLink?.updateLastSeen()
            LinkType.LINK_BLE -> bleLink?.updateLastSeen()
            else -> {}
        }
        onMessageReceived?.invoke(msg)
    }

    private fun buildStatusMessage(report: EyeBotProto.StatusReport): EyeBotProto.EyeBotMessage {
        return EyeBotProto.EyeBotMessage.newBuilder()
            .setHeader(
                EyeBotProto.MessageHeader.newBuilder()
                    .setMsgId(msgIdGenerator.incrementAndGet())
                    .setCmdType(EyeBotProto.CmdType.EVT_STATUS_REPORT)
                    .setTimestamp(System.currentTimeMillis())
                    .setLinkType(_activeLink.value)
            )
            .setStatusReport(report)
            .build()
    }

    private fun buildLinkSwitchMessage(
        oldLink: EyeBotProto.LinkType,
        newLink: EyeBotProto.LinkType
    ): EyeBotProto.EyeBotMessage {
        return EyeBotProto.EyeBotMessage.newBuilder()
            .setHeader(
                EyeBotProto.MessageHeader.newBuilder()
                    .setMsgId(msgIdGenerator.incrementAndGet())
                    .setCmdType(EyeBotProto.CmdType.EVT_LINK_SWITCH)
                    .setTimestamp(System.currentTimeMillis())
                    .setLinkType(newLink)
            )
            .setLinkSwitchEvent(
                EyeBotProto.LinkSwitchEvent.newBuilder()
                    .setOldLink(oldLink)
                    .setNewLink(newLink)
                    .setReason(
                        if (newLink == EyeBotProto.LinkType.LINK_TCP)
                            EyeBotProto.LinkSwitchEvent.SwitchReason.REASON_MAIN_RECOVER
                        else
                            EyeBotProto.LinkSwitchEvent.SwitchReason.REASON_MAIN_LOST
                    )
            )
            .build()
    }

    private fun startStatusReport() {
        scope.launch {
            // 周期性状态上报由外部调用 sendStatusReport 触发
            // 此处仅启动UDP发送队列
        }
    }

    fun release() {
        linkMonitorJob?.cancel()
        scope.cancel()
        tcpLink?.disconnect()
        udpLink?.stop()
        usbLink?.disconnect()
        bleLink?.disconnect()
    }

    companion object {
        private const val LINK_TIMEOUT_MS = 3000L // 链路超时阈值 3s
        private const val FAILOVER_DEADLINE_MS = 1000L // 故障转移必须在1s内完成

        @Volatile
        private var instance: CommunicationManager? = null

        fun getInstance(): CommunicationManager {
            return instance ?: synchronized(this) {
                instance ?: CommunicationManager().also { instance = it }
            }
        }
    }
}

/**
 * 通信配置
 */
data class CommConfig(
    val tcpHost: String,
    val tcpPort: Int,
    val udpHost: String,
    val udpPort: Int,
    val udpReportIntervalMs: Long = 100,
    val heartbeatIntervalMs: Long = 2000
)

/**
 * 链路类型枚举 (镜像proto定义, 便于模块内部使用)
 */
enum class LinkType {
    LINK_UNKNOWN,
    LINK_TCP,
    LINK_UDP,
    LINK_USB_SERIAL,
    LINK_BLE
}
