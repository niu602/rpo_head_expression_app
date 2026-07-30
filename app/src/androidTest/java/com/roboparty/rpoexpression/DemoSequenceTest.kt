package com.roboparty.rpoexpression

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roboparty.rpoexpression.expression.DemoSequenceManager
import com.roboparty.rpoexpression.expression.ExpressionType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 演示序列测试
 * 真机运行，测试Demo时序和表情切换
 */
@RunWith(AndroidJUnit4::class)
class DemoSequenceTest {

    private lateinit var demoSequenceManager: DemoSequenceManager
    private lateinit var app: RpoExpressionApp

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        app = appContext.applicationContext as RpoExpressionApp
        demoSequenceManager = app.demoSequenceManager
        app.expressionManager.start()
        demoSequenceManager.stopDemo()
        runBlocking { delay(100) }
        app.expressionManager.requestExpression(ExpressionType.IDLE, force = true)
    }

    @Test
    fun testDemoTriggerAndSequence() = runBlocking {
        // 触发Demo
        assertFalse(demoSequenceManager.isRunning())
        demoSequenceManager.triggerStandardDemo()
        assertTrue(demoSequenceManager.isRunning())

        // 等待第一个表情切换（THINKING ~1800ms）
        delay(2000)
        assertTrue(demoSequenceManager.isRunning())

        // 等待更多表情播放
        delay(3000)

        // Demo应该仍在运行（完整序列很长）
        assertTrue(demoSequenceManager.isRunning())

        demoSequenceManager.stopDemo()
    }

    @Test
    fun testDemoNotReentrant() {
        demoSequenceManager.triggerStandardDemo()
        assertTrue(demoSequenceManager.isRunning())

        // 再次触发，应该被忽略（不会重启）
        val result = runCatching { demoSequenceManager.triggerStandardDemo() }
        assertTrue(result.isSuccess)
        assertTrue(demoSequenceManager.isRunning())

        demoSequenceManager.stopDemo()
    }

    @Test
    fun testStopDemo() = runBlocking {
        demoSequenceManager.triggerStandardDemo()
        assertTrue(demoSequenceManager.isRunning())

        delay(500)
        demoSequenceManager.stopDemo()
        delay(100)

        // 停止后不再运行
        assertFalse(demoSequenceManager.isRunning())
    }

    @Test
    fun testStopReturnsToIdle() = runBlocking {
        demoSequenceManager.triggerStandardDemo()
        delay(500)
        demoSequenceManager.stopDemo()
        delay(200)

        // 停止后回到IDLE
        assertFalse(demoSequenceManager.isRunning())
        assertEquals(ExpressionType.IDLE, app.expressionManager.expressionState.value.type)
    }
}
