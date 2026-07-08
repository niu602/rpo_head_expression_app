package com.roboparty.rpoexpression

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import com.roboparty.rpoexpression.integration.ModuleStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 多模块联动集成测试
 * 真机运行，测试ASR/TTS/人脸识别状态切换逻辑
 */
@RunWith(AndroidJUnit4::class)
class ModuleIntegrationTest {

    private lateinit var expressionManager: ExpressionManager

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val app = appContext.applicationContext as RpoExpressionApp
        expressionManager = app.expressionManager
        expressionManager.start()
        // 先回到IDLE
        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
    }

    @Test
    fun testAsrStateFlow() {
        // ASR开始识别 -> 思考
        ModuleStateManager.onAsrStartListening()
        assertEquals(ExpressionType.THINKING, expressionManager.expressionState.value.type)

        // ASR处理中 -> 识别中
        ModuleStateManager.onAsrProcessing()
        assertEquals(ExpressionType.RECOGNIZING, expressionManager.expressionState.value.type)

        // ASR结束 -> 1秒后回IDLE
        ModuleStateManager.onAsrFinish()
        runBlocking { delay(1200) }
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }

    @Test
    fun testAsrError() {
        ModuleStateManager.onAsrError()
        assertEquals(ExpressionType.RESPONSE, expressionManager.expressionState.value.type)
        runBlocking { delay(1200) }
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }

    @Test
    fun testTtsStateFlow() {
        // 先切到思考
        expressionManager.requestExpression(ExpressionType.THINKING, force = true)
        assertEquals(ExpressionType.THINKING, expressionManager.expressionState.value.type)

        // TTS开始 -> 说话
        ModuleStateManager.onTtsStartSpeaking()
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)

        // TTS结束 -> 回到思考
        ModuleStateManager.onTtsFinishSpeaking()
        assertEquals(ExpressionType.THINKING, expressionManager.expressionState.value.type)
    }

    @Test
    fun testTtsFromIdle() {
        // 待机状态下TTS开始
        ModuleStateManager.onTtsStartSpeaking()
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)

        // TTS结束 -> 回待机
        ModuleStateManager.onTtsFinishSpeaking()
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }

    @Test
    fun testFaceDetectFlow() {
        // 开始识别 -> 识别中
        ModuleStateManager.onFaceDetectStart()
        assertEquals(ExpressionType.RECOGNIZING, expressionManager.expressionState.value.type)

        // 识别成功 -> 应答
        ModuleStateManager.onFaceDetectSuccess()
        assertEquals(ExpressionType.RESPONSE, expressionManager.expressionState.value.type)
        runBlocking { delay(1200) }
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }

    @Test
    fun testFaceDetectFailed() {
        ModuleStateManager.onFaceDetectStart()
        assertEquals(ExpressionType.RECOGNIZING, expressionManager.expressionState.value.type)

        ModuleStateManager.onFaceDetectFailed()
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }

    @Test
    fun testTtsPreemptAsr() {
        // ASR识别中
        ModuleStateManager.onAsrProcessing()
        assertEquals(ExpressionType.RECOGNIZING, expressionManager.expressionState.value.type)

        // TTS开始，优先级更高，抢占
        ModuleStateManager.onTtsStartSpeaking()
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)
    }
}
