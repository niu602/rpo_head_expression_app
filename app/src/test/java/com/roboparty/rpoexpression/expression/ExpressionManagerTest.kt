package com.roboparty.rpoexpression.expression

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * 表情状态机单元测试
 * 验证状态切换逻辑与优先级规则
 */
class ExpressionManagerTest {

    private lateinit var expressionManager: ExpressionManager

    @Before
    fun setup() {
        // 重置单例，每次测试获取新实例
        expressionManager = ExpressionManager.getInstance()
        // 启动管理器
        expressionManager.start()
    }

    @Test
    fun `默认状态为IDLE待机`() {
        val state = expressionManager.expressionState.value
        assertEquals(ExpressionType.IDLE, state.type)
    }

    @Test
    fun `高优先级表情可以抢占低优先级表情`() {
        println("【测试开始】高优先级抢占低优先级")
        // 先切到思考（优先级2）
        expressionManager.requestExpression(ExpressionType.THINKING)
        println("调用THINKING后，状态: ${expressionManager.expressionState.value.type}")
        assertEquals(ExpressionType.THINKING, expressionManager.expressionState.value.type)
        Thread.sleep(150)
        // 切到说话（优先级4），应该成功
        val result = expressionManager.requestExpression(ExpressionType.SPEAKING)
        println("调用SPEAKING后，返回值: $result，状态: ${expressionManager.expressionState.value.type}")
        assertTrue(result)
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)
    }

    @Test
    fun `低优先级表情不能抢占高优先级表情`() {
        // 先切到说话（优先级4）
        expressionManager.requestExpression(ExpressionType.SPEAKING)
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)
        Thread.sleep(150)
        // 切到思考（优先级2），应该失败
        val result = expressionManager.requestExpression(ExpressionType.THINKING)
        assertFalse(result)
        // 状态保持说话
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)
    }

    @Test
    fun `强制切换可以忽略优先级`() {
        // 先切到说话（优先级4）
        expressionManager.requestExpression(ExpressionType.SPEAKING)
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)
        Thread.sleep(150)
        // 强制切到待机（优先级0），应该成功
        val result = expressionManager.requestExpression(ExpressionType.IDLE, force = true)
        assertTrue(result)
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }

    @Test
    fun `相同表情不重复触发`() {
        // 切到说话
        expressionManager.requestExpression(ExpressionType.SPEAKING)
        val firstTime = expressionManager.expressionState.value.startTime

        // 立即再次请求相同表情
        val result = expressionManager.requestExpression(ExpressionType.SPEAKING)
        assertFalse(result)
        // 开始时间不变，说明没有重复切换
        assertEquals(firstTime, expressionManager.expressionState.value.startTime)
    }

    @Test
    fun `临时表情自动回落到IDLE`() = runBlocking {
        // 切到应答表情，默认1秒自动回落
        expressionManager.requestExpression(ExpressionType.RESPONSE)
        assertEquals(ExpressionType.RESPONSE, expressionManager.expressionState.value.type)

        // 等待1.2秒，应该自动回到IDLE
        delay(1200)
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }

    @Test
    fun `100ms内重复请求被防抖拦截`() {
        // 第一次请求成功
        val firstResult = expressionManager.requestExpression(ExpressionType.THINKING)
        assertTrue(firstResult)

        // 立即再次请求不同表情，应该被防抖拦截
        val secondResult = expressionManager.requestExpression(ExpressionType.RECOGNIZING)
        assertFalse(secondResult)
        // 状态保持思考
        assertEquals(ExpressionType.THINKING, expressionManager.expressionState.value.type)
    }

    @Test
    fun `clearExpression回到IDLE状态`() {
        // 切到说话
        expressionManager.requestExpression(ExpressionType.SPEAKING)
        assertEquals(ExpressionType.SPEAKING, expressionManager.expressionState.value.type)

        // 清除表情
        expressionManager.clearExpression()
        assertEquals(ExpressionType.IDLE, expressionManager.expressionState.value.type)
    }
}
