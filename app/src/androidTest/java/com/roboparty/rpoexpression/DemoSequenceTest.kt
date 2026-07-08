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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 演示序列测试
 * 真机运行，测试Demo时序和动作回调
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
        val actionCount = AtomicInteger(0)
        val startCalled = AtomicBoolean(false)
        val finishCalled = AtomicBoolean(false)

        // 注册动作监听器
        demoSequenceManager.setListener(object : DemoSequenceManager.DemoActionListener {
            override fun onDemoStart() {
                startCalled.set(true)
            }

            override fun onThinkingAction() {
                actionCount.incrementAndGet()
                // 此时表情应该是THINKING
                assertEquals(ExpressionType.THINKING, app.expressionManager.expressionState.value.type)
            }

            override fun onRecognizingAction() {
                actionCount.incrementAndGet()
                assertEquals(ExpressionType.RECOGNIZING, app.expressionManager.expressionState.value.type)
            }

            override fun onResponseAction() {
                actionCount.incrementAndGet()
                assertEquals(ExpressionType.RESPONSE, app.expressionManager.expressionState.value.type)
            }

            override fun onSpeakingAction() {
                actionCount.incrementAndGet()
                assertEquals(ExpressionType.SPEAKING, app.expressionManager.expressionState.value.type)
            }

            override fun onDemoFinish() {
                finishCalled.set(true)
            }
        })

        // 触发Demo
        assertFalse(demoSequenceManager.isRunning())
        demoSequenceManager.triggerStandardDemo()
        assertTrue(demoSequenceManager.isRunning())
        assertTrue(startCalled.get())

        // 等待所有动作完成（总时长1.5+2+1+3=7.5秒，等8秒）
        delay(8000)

        // 验证所有动作都被调用
        assertEquals(4, actionCount.get())
        assertTrue(finishCalled.get())
        assertFalse(demoSequenceManager.isRunning())
        // 结束后回到IDLE
        assertEquals(ExpressionType.IDLE, app.expressionManager.expressionState.value.type)
    }

    @Test
    fun testDemoNotReentrant() {
        demoSequenceManager.triggerStandardDemo()
        assertTrue(demoSequenceManager.isRunning())

        // 再次触发，应该被忽略
        val result = runCatching { demoSequenceManager.triggerStandardDemo() }
        assertTrue(result.isSuccess)
        // 还是运行中，不会重启
        assertTrue(demoSequenceManager.isRunning())
    }

    @Test
    fun testStopDemo() = runBlocking {
        demoSequenceManager.triggerStandardDemo()
        assertTrue(demoSequenceManager.isRunning())

        delay(500)
        demoSequenceManager.stopDemo()
        delay(100)

        // 停止后回到IDLE
        assertFalse(demoSequenceManager.isRunning())
        assertEquals(ExpressionType.IDLE, app.expressionManager.expressionState.value.type)
    }

    @Test
    fun testGamepadKeyTrigger() {
        // 模拟手柄按键触发，和物理按键效果一致
        demoSequenceManager.onGamepadKeyPressed()
        assertTrue(demoSequenceManager.isRunning())
        demoSequenceManager.stopDemo()
    }
}
