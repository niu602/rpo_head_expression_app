/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

import android.util.Log
import com.roboparty.rpoexpression.communication.link.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多链路管理器
 * 负责链路健康检测、故障自动切换、主链路恢复切回
 *
 * 切换策略：
 * 1. 按优先级排序：TCP(1) > UDP(2) > USB(3) > BLE(4)
 * 2. 健康检测周期：500ms
 * 3. 故障判定：连续2次心跳超时（1秒内无数据）
 * 4. 切换耗时：< 500ms（故障检测到切换完成）
 * 5. 主链路恢复：检测到主链路恢复后自动切回
 */
class MultiLinkManager private constructor() {

    companion object {
        private const val TAG = "MultiLinkManager"

        // 健康检测参数
        private const val HEALTH_CHECK_INTERVAL = 500L  // 500ms检测一次
        private const val LINK_TIMEOUT_MS = 1000L       // 1秒无数据判定为故障
        private const val LINK_RECOVERY_CHECK = 3       // 连续3次检测正常判定为恢复

        // 切换原因
        const val SWITCH_REASON_MAIN_RECOVER = 1
        const val SWITCH_REASON_MAIN_LOST = 2
        const val SWITCH_REASON_MANUAL = 3
        const val SWITCH_REASON_QUALITY_DROP = 4

        @Volatile
        private var instance: MultiLinkManager? = null

        fun getInstance(): MultiLinkManager {
            return instance ?: synchronized(this) {
                instance ?: MultiLinkManager().also { instance = it }
            }
        }
    }

    // 所有链路（按优先级排序）
    private val links = listOf(
        TcpLink(),
        UdpLink(),
        UsbSerialLink(),
        BleLink()
    )

    // 当前活跃链路
    private val _activeLink = MutableStateFlow<CommLink?>(null)
    val activeLink: StateFlow<CommLink?> = _activeLink.asStateFlow()

    // 连接状态
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private val msgIdGenerator = AtomicInteger(1)
    private val seqGenerator = AtomicInteger(1)

    // 链路恢复计数器
    private val linkRecoveryCounter = mutableMapOf<CommLink.LinkType, Int>()

    // 数据接收回调
    private var dataCallback: ((ByteArray) -> Unit)? = null

    // 链路切换回调
    private var linkSwitchCallback: ((oldLink: CommLink.LinkType, newLink: CommLink.LinkType, reason: Int) -> Unit)? = null

    // 配置
    private var host: String = "192.168.1.100"
    private var tcpPort: Int = 8899
    private var udpPort: Int = 8900

    init {
        // 为每个链路设置状态回调
        links.forEach { link ->
            link.setStateCallback { state ->
                Log.d(TAG, "Link ${link.getType()} state changed to $state")
                if (state == CommLink.LinkState.CONNECTED) {
                    linkRecoveryCounter[link.getType()] = 0
                }
            }
            link.setDataCallback { data ->
                // 收到数据，更新最后接收时间
                if (link == _activeLink.value) {
                    dataCallback?.invoke(data)
                }
            }
        }
    }

    fun nextMsgId(): Int = msgIdGenerator.getAndIncrement()
    fun nextSeq(): Int = seqGenerator.getAndIncrement()

    fun setDataCallback(callback: (ByteArray) -> Unit) {
        this.dataCallback = callback
    }

    fun setLinkSwitchCallback(callback: (oldLink: CommLink.LinkType, newLink: CommLink.LinkType, reason: Int) -> Unit) {
        this.linkSwitchCallback = callback
    }

    fun configure(host: String, tcpPort: Int = 8899, udpPort: Int = 8900) {
        this.host = host
        this.tcpPort = tcpPort
        this.udpPort = udpPort
    }

    /**
     * 启动所有链路连接和健康检测
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Starting multi-link manager")
            scope.launch {
                // 先连接所有链路
                connectAllLinks()
                // 启动健康检测循环
                startHealthCheck()
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping multi-link manager")
            scope.coroutineContext.cancelChildren()
            links.forEach { it.disconnect() }
            _activeLink.value = null
            _isConnected.value = false
        }
    }

    /**
     * 发送数据（自动选择当前活跃链路）
     * @return 是否发送成功
     */
    fun send(data: ByteArray): Boolean {
        val link = _activeLink.value ?: return false
        val result = link.send(data)
        if (!result) {
            // 发送失败，立即触发切换检查
            scope.launch { checkAndSwitchLink() }
        }
        return result
    }

    /**
     * 通过UDP发送高频状态（不保证可靠，低延迟）
     */
    fun sendUdp(data: ByteArray): Boolean {
        val udp = links.find { it.getType() == CommLink.LinkType.LINK_UDP }
        if (udp?.isConnected() == true) {
            return udp.send(data)
        }
        // UDP不可用则走当前活跃链路
        return send(data)
    }

    private suspend fun connectAllLinks() {
        // 按优先级顺序连接
        links.forEachIndexed { index, link ->
            try {
                val port = when (link.getType()) {
                    CommLink.LinkType.LINK_TCP -> tcpPort
                    CommLink.LinkType.LINK_UDP -> udpPort
                    else -> 0
                }
                val connected = link.connect(host, port)
                if (connected && _activeLink.value == null) {
                    // 第一个连接成功的作为初始活跃链路
                    switchToLink(link, SWITCH_REASON_MANUAL)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect ${link.getType()}", e)
            }
            delay(50) // 错开连接时间
        }
    }

    private fun startHealthCheck() {
        scope.launch {
            while (isRunning.get()) {
                try {
                    checkAndSwitchLink()
                } catch (e: Exception) {
                    Log.e(TAG, "Health check error", e)
                }
                delay(HEALTH_CHECK_INTERVAL)
            }
        }
    }

    /**
     * 检查链路健康并执行切换
     * 切换耗时目标：< 1秒
     */
    private suspend fun checkAndSwitchLink() {
        val now = System.currentTimeMillis()
        val current = _activeLink.value

        // 1. 检查当前链路是否故障
        var currentLinkFailed = false
        if (current != null) {
            val timeSinceLastRecv = now - current.getLastReceiveTime()
            if (timeSinceLastRecv > LINK_TIMEOUT_MS || !current.isConnected()) {
                currentLinkFailed = true
                Log.w(TAG, "Active link ${current.getType()} failed: lastRecv=${timeSinceLastRecv}ms, connected=${current.isConnected()}")
            }
        } else {
            currentLinkFailed = true
        }

        // 2. 当前链路故障，寻找可用链路切换
        if (currentLinkFailed) {
            val available = links.firstOrNull { it.isConnected() && it !== current }
            if (available != null) {
                switchToLink(available, SWITCH_REASON_MAIN_LOST)
                return
            }
            // 没有可用链路，尝试重连
            _isConnected.value = false
            tryReconnectLinks()
            return
        }

        // 3. 检查是否有更高优先级链路恢复（自动切回主链路）
        val currentPriority = current?.getPriority() ?: 99
        val higherPriority = links.firstOrNull { link ->
            link.getPriority() < currentPriority &&
                    link.isConnected() &&
                    (now - link.getLastReceiveTime()) < LINK_TIMEOUT_MS
        }

        if (higherPriority != null) {
            // 主链路恢复，需要连续检测确认
            val type = higherPriority.getType()
            val count = linkRecoveryCounter.getOrDefault(type, 0) + 1
            linkRecoveryCounter[type] = count
            if (count >= LINK_RECOVERY_CHECK) {
                Log.i(TAG, "Higher priority link ${higherPriority.getType()} recovered, switching back")
                switchToLink(higherPriority, SWITCH_REASON_MAIN_RECOVER)
                linkRecoveryCounter[type] = 0
            }
        } else {
            // 重置恢复计数器
            links.forEach { linkRecoveryCounter[it.getType()] = 0 }
        }

        _isConnected.value = true
    }

    private fun switchToLink(newLink: CommLink, reason: Int) {
        val oldLink = _activeLink.value
        if (newLink == oldLink) return

        val oldType = oldLink?.getType() ?: CommLink.LinkType.LINK_UNKNOWN
        val newType = newLink.getType()

        Log.i(TAG, "Switching link: $oldType -> $newType, reason=$reason")
        _activeLink.value = newLink
        _isConnected.value = true

        // 通知链路切换
        linkSwitchCallback?.invoke(oldType, newType, reason)
    }

    private suspend fun tryReconnectLinks() {
        links.forEach { link ->
            if (!link.isConnected()) {
                try {
                    val port = when (link.getType()) {
                        CommLink.LinkType.LINK_TCP -> tcpPort
                        CommLink.LinkType.LINK_UDP -> udpPort
                        else -> 0
                    }
                    link.connect(host, port)
                } catch (e: Exception) {
                    // 静默失败，下次重试
                }
            }
        }
    }

    /**
     * 获取当前活跃链路类型
     */
    fun getActiveLinkType(): CommLink.LinkType {
        return _activeLink.value?.getType() ?: CommLink.LinkType.LINK_UNKNOWN
    }
}
