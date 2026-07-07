/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 表情状态数据类
 * 封装当前表情的完整状态信息，用于状态机管理和动画渲染
 *
 * @property type 当前表情类型
 * @property startTime 表情开始时间戳（毫秒）
 * @property duration 表情持续时长（毫秒），Long.MAX_VALUE表示持续直到被打断
 * @property progress 动画进度 0.0f ~ 1.0f
 * @property isLooping 是否循环播放动画
 * @property transitionProgress 过渡动画进度，用于表情切换平滑过渡
 */
data class ExpressionState(
    val type: ExpressionType = ExpressionType.IDLE,
    val startTime: Long = System.currentTimeMillis(),
    val duration: Long = type.defaultDuration,
    val progress: Float = 0f,
    val isLooping: Boolean = true,
    val transitionProgress: Float = 1f
) {
    /**
     * 表情是否已过期（需要自动回落）
     */
    fun isExpired(): Boolean {
        if (!type.isAutoFallback) return false
        return System.currentTimeMillis() - startTime >= duration
    }

    /**
     * 获取当前动画已播放时间（毫秒）
     */
    fun getElapsedTime(): Long = System.currentTimeMillis() - startTime

    /**
     * 更新动画进度
     */
    fun updateProgress(): ExpressionState {
        val elapsed = getElapsedTime()
        val newProgress = if (duration == Long.MAX_VALUE) {
            // 循环动画，使用正弦波计算进度
            ((elapsed % 2000) / 2000f)
        } else {
            (elapsed.coerceAtMost(duration) / duration.toFloat()).coerceIn(0f, 1f)
        }
        return copy(progress = newProgress)
    }
}
