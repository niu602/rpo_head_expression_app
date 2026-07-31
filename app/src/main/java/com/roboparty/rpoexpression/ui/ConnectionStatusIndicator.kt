/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roboparty.rpoexpression.communication.CommunicationManager
import com.roboparty.rpoexpression.communication.HeadControlManager
import com.roboparty.rpoexpression.communication.link.CommLink

/**
 * 通信连接状态指示器
 * 显示在屏幕右上角，展示两条链路的连接状态
 *
 * 状态颜色：
 * - 绿色：已连接
 * - 黄色：连接中
 * - 红色：断开/错误
 */
@Composable
fun ConnectionStatusIndicator(
    modifier: Modifier = Modifier,
    commManager: CommunicationManager,
    headControlManager: HeadControlManager
) {
    // 收集两条链路的连接状态
    val mainState by commManager.connectionState.collectAsState()
    val headState by headControlManager.connectionState.collectAsState()
    val activeLinkType = commManager.getActiveLinkType()

    Column(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 主链路（表情控制）
        StatusRow(
            label = "主控",
            stateText = mapMainStateText(mainState, activeLinkType),
            color = mapStateToColor(mainState)
        )
        // 头部控制链路
        StatusRow(
            label = "头部",
            stateText = mapHeadStateText(headState),
            color = mapHeadStateToColor(headState)
        )
    }
}

/**
 * 单行状态显示：彩色圆点 + 标签 + 状态文字
 */
@Composable
private fun StatusRow(
    label: String,
    stateText: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态指示圆点
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(8.dp)
        ) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(6.dp))
        // 标签
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(4.dp))
        // 状态文字
        Text(
            text = stateText,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ========== 状态映射工具函数 ==========

/**
 * 主链路状态 → 颜色
 */
private fun mapStateToColor(state: CommunicationManager.ConnectionState): Color {
    return when (state) {
        CommunicationManager.ConnectionState.CONNECTED -> Color(0xFF4CAF50)  // 绿色
        CommunicationManager.ConnectionState.CONNECTING -> Color(0xFFFFC107) // 黄色
        CommunicationManager.ConnectionState.DISCONNECTED -> Color(0xFFF44336) // 红色
        CommunicationManager.ConnectionState.ERROR -> Color(0xFFF44336)      // 红色
    }
}

/**
 * 头部控制链路状态 → 颜色
 */
private fun mapHeadStateToColor(state: HeadControlManager.HeadConnectionState): Color {
    return when (state) {
        HeadControlManager.HeadConnectionState.CONNECTED -> Color(0xFF4CAF50)   // 绿色
        HeadControlManager.HeadConnectionState.CONNECTING -> Color(0xFFFFC107)  // 黄色
        HeadControlManager.HeadConnectionState.DISCONNECTED -> Color(0xFFF44336) // 红色
        HeadControlManager.HeadConnectionState.ERROR -> Color(0xFFF44336)       // 红色
    }
}

/**
 * 主链路状态 → 显示文字（含连接方式）
 */
private fun mapMainStateText(
    state: CommunicationManager.ConnectionState,
    linkType: CommLink.LinkType
): String {
    return when (state) {
        CommunicationManager.ConnectionState.CONNECTED -> {
            when (linkType) {
                CommLink.LinkType.LINK_TCP -> "TCP已连"
                CommLink.LinkType.LINK_UDP -> "UDP已连"
                CommLink.LinkType.LINK_UNKNOWN -> "已连接"
            }
        }
        CommunicationManager.ConnectionState.CONNECTING -> "连接中"
        CommunicationManager.ConnectionState.DISCONNECTED -> "未连接"
        CommunicationManager.ConnectionState.ERROR -> "连接错误"
    }
}

/**
 * 头部控制链路状态 → 显示文字
 */
private fun mapHeadStateText(state: HeadControlManager.HeadConnectionState): String {
    return when (state) {
        HeadControlManager.HeadConnectionState.CONNECTED -> "已连接"
        HeadControlManager.HeadConnectionState.CONNECTING -> "连接中"
        HeadControlManager.HeadConnectionState.DISCONNECTED -> "未连接"
        HeadControlManager.HeadConnectionState.ERROR -> "连接错误"
    }
}
