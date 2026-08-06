/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.util

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 人格切换事务管理器
 *
 * 解决问题：
 * 1. 状态更新非原子化 —— 人格切换三步（LLM/表情/TTS）分别操作，无事务锁，切换期间外部输入可介入
 * 2. 流式任务未取消 —— setPersona() 只做内存赋值，不取消正在进行的流式任务
 *
 * 设计目标：
 * - 确保切换过程的排他性（同一时间只有一个切换在进行）
 * - 切换期间屏蔽所有外部输入（ASR/KWS/TCP指令）
 * - 统一清理旧流（停止 TTS、停止 ASR、取消 LLM 请求）
 * - 超时熔断，防止死锁
 *
 * 事务流程（5步法简化版）：
 * 1. 申请锁：tryStartSwitch()
 * 2. 切断旧流：stopSpeaking + stopListening + cancelRequest
 * 3. 并行下发：setPersona + setStyle + setTtsVoice
 * 4. 等待完成：等待动画/各模块就绪
 * 5. 释放锁：finishSwitch()
 */
object PersonaSwitchManager {

    private const val TAG = "PersonaSwitchMgr"

    /**
     * 切换状态
     */
    enum class SwitchState {
        /** 正常交互中 */
        IDLE,
        /** 人格切换中 */
        SWITCHING
    }

    /**
     * 切换超时时间（毫秒）
     * 正常切换：toggleGender 动画约 700ms + 其他操作约 100ms = 约 800ms
     * 超时设为 2000ms，留足余量
     */
    private const val SWITCH_TIMEOUT_MS = 2000L

    // ==================== 状态管理 ====================

    @Volatile
    private var state: SwitchState = SwitchState.IDLE

    /** 当前切换的来源（用于日志排查） */
    @Volatile
    private var currentSwitchSource: String = ""

    /** 切换开始时间（用于超时检测） */
    @Volatile
    private var switchStartTime: Long = 0L

    /** 切换计数（用于统计） */
    @Volatile
    var switchCount: Int = 0
        private set

    /** 超时熔断次数 */
    @Volatile
    var timeoutCount: Int = 0
        private set

    /** 主线程 Handler（用于超时熔断） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 超时熔断 Runnable */
    private val timeoutRunnable = Runnable {
        if (state == SwitchState.SWITCHING) {
            Log.e(TAG, "⚠️ Switch timeout! source=$currentSwitchSource, " +
                    "duration=${System.currentTimeMillis() - switchStartTime}ms")
            timeoutCount++
            // 强制释放锁，防止死锁
            state = SwitchState.IDLE
            currentSwitchSource = ""
        }
    }

    // ==================== 公开 API ====================

    /**
     * 是否正在切换中
     */
    val isSwitching: Boolean
        get() = state == SwitchState.SWITCHING

    /**
     * 获取当前状态
     */
    fun getState(): SwitchState = state

    /**
     * 尝试开始人格切换
     *
     * @param source 切换来源（用于日志排查），如 "triggerWakeUp"、"switchPersona"、"styleChange"
     * @return true 表示成功获取锁，可以开始切换；false 表示正在切换中，本次请求被丢弃
     */
    fun tryStartSwitch(source: String): Boolean {
        if (state == SwitchState.SWITCHING) {
            Log.w(TAG, "Switch in progress, drop request. " +
                    "currentSource=$currentSwitchSource, newSource=$source")
            return false
        }

        synchronized(this) {
            if (state == SwitchState.SWITCHING) {
                Log.w(TAG, "Switch in progress (double check), drop request. " +
                        "currentSource=$currentSwitchSource, newSource=$source")
                return false
            }

            state = SwitchState.SWITCHING
            currentSwitchSource = source
            switchStartTime = System.currentTimeMillis()
            switchCount++

            // 启动超时熔断
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, SWITCH_TIMEOUT_MS)

            Log.i(TAG, "▶️ Switch started: source=$source, count=$switchCount")
            return true
        }
    }

    /**
     * 完成人格切换，释放锁
     *
     * @param source 切换来源（用于校验，防止错配）
     */
    fun finishSwitch(source: String) {
        if (state != SwitchState.SWITCHING) {
            Log.w(TAG, "finishSwitch called but state is $state, source=$source")
            return
        }

        synchronized(this) {
            if (state != SwitchState.SWITCHING) {
                Log.w(TAG, "finishSwitch double check failed, state=$state, source=$source")
                return
            }

            val duration = System.currentTimeMillis() - switchStartTime
            state = SwitchState.IDLE
            currentSwitchSource = ""

            // 取消超时熔断
            mainHandler.removeCallbacks(timeoutRunnable)

            Log.i(TAG, "✅ Switch finished: source=$source, duration=${duration}ms")
        }
    }

    /**
     * 强制释放锁（紧急情况使用）
     *
     * @param reason 强制释放的原因
     */
    fun forceRelease(reason: String) {
        if (state != SwitchState.SWITCHING) return

        synchronized(this) {
            if (state != SwitchState.SWITCHING) return

            Log.w(TAG, "⚠️ Force release lock: reason=$reason, " +
                    "currentSource=$currentSwitchSource, " +
                    "duration=${System.currentTimeMillis() - switchStartTime}ms")

            state = SwitchState.IDLE
            currentSwitchSource = ""
            mainHandler.removeCallbacks(timeoutRunnable)
        }
    }

    /**
     * 获取当前切换信息（用于调试）
     */
    fun getSwitchInfo(): String {
        return if (state == SwitchState.SWITCHING) {
            "SWITCHING(source=$currentSwitchSource, " +
                    "duration=${System.currentTimeMillis() - switchStartTime}ms)"
        } else {
            "IDLE"
        }
    }

    /**
     * 重置统计数据
     */
    fun resetStats() {
        switchCount = 0
        timeoutCount = 0
    }
}
