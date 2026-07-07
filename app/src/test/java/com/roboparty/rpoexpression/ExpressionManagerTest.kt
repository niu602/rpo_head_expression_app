/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression

import com.roboparty.rpoexpression.expression.ExpressionType
import org.junit.Assert.*
import org.junit.Test

/**
 * 表情管理器单元测试
 */
class ExpressionManagerTest {

    @Test
    fun `expression type priority is correct`() {
        // 验证优先级顺序：说话 > 识别中 > 思考 > 应答 > 待机
        assertTrue(ExpressionType.SPEAKING.priority > ExpressionType.RECOGNIZING.priority)
        assertTrue(ExpressionType.RECOGNIZING.priority > ExpressionType.THINKING.priority)
        assertTrue(ExpressionType.THINKING.priority > ExpressionType.RESPONSE.priority)
        assertTrue(ExpressionType.RESPONSE.priority > ExpressionType.IDLE.priority)
    }

    @Test
    fun `expression type from command works correctly`() {
        assertEquals(ExpressionType.IDLE, ExpressionType.fromCommand("idle"))
        assertEquals(ExpressionType.IDLE, ExpressionType.fromCommand("待机"))
        assertEquals(ExpressionType.SPEAKING, ExpressionType.fromCommand("speaking"))
        assertEquals(ExpressionType.SPEAKING, ExpressionType.fromCommand("说话"))
        assertEquals(ExpressionType.THINKING, ExpressionType.fromCommand("thinking"))
        assertEquals(ExpressionType.RECOGNIZING, ExpressionType.fromCommand("recognizing"))
        assertEquals(ExpressionType.RESPONSE, ExpressionType.fromCommand("response"))
        assertEquals(ExpressionType.BLINK, ExpressionType.fromCommand("blink"))
        assertNull(ExpressionType.fromCommand("unknown"))
    }

    @Test
    fun `auto fallback flags are correct`() {
        assertTrue(ExpressionType.BLINK.isAutoFallback)
        assertTrue(ExpressionType.RESPONSE.isAutoFallback)
        assertFalse(ExpressionType.IDLE.isAutoFallback)
        assertFalse(ExpressionType.SPEAKING.isAutoFallback)
        assertFalse(ExpressionType.THINKING.isAutoFallback)
        assertFalse(ExpressionType.RECOGNIZING.isAutoFallback)
    }

    @Test
    fun `default durations are correct`() {
        assertEquals(200L, ExpressionType.BLINK.defaultDuration)
        assertEquals(1000L, ExpressionType.RESPONSE.defaultDuration)
        assertEquals(Long.MAX_VALUE, ExpressionType.IDLE.defaultDuration)
    }
}
