package com.roboparty.rpoexpression

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roboparty.rpoexpression.sensor.BrightnessManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 亮度传感器测试
 * 真机运行，测试亮度自动调节功能
 */
@RunWith(AndroidJUnit4::class)
class BrightnessSensorTest {

    private lateinit var brightnessManager: BrightnessManager

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val app = appContext.applicationContext as RpoExpressionApp
        brightnessManager = app.brightnessManager
    }

    @Test
    fun testBrightnessManagerStartStop() {
        brightnessManager.start()
        // 启动后亮度有初始值
        runBlocking {
            val brightness = brightnessManager.brightness.first()
            assertTrue(brightness in 0.3f..1.0f)
        }
        brightnessManager.stop()
    }

    @Test
    fun testBrightnessRange() = runBlocking {
        brightnessManager.start()
        delay(1000)
        val brightness = brightnessManager.brightness.first()
        // 亮度始终在0.3-1.0之间
        assertTrue("Brightness should be between 0.3 and 1.0, actual: $brightness",
            brightness in 0.3f..1.0f)
        brightnessManager.stop()
    }

    @Test
    fun testManualSetBrightness() {
        val activity = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // 手动设置亮度
        brightnessManager.setBrightness(
            InstrumentationRegistry.getInstrumentation().targetContext as MainActivity,
            0.5f
        )
        runBlocking { delay(200) }
        assertEquals(0.5f, brightnessManager.brightness.value, 0.01f)
    }
}
