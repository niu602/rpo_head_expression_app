/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import com.roboparty.rpoexpression.expression.ExpressionType
import kotlin.math.PI
import kotlin.math.sin

/**
 * 微动画层 - 仅保留整体自然动作，不修改图片内容
 * 仅做整体缩放和Y轴偏移，消除机械感，不干预图片本身
 */
class MicroAnimationLayer private constructor() {

    companion object {
        private const val BREATH_PERIOD_MS = 3000L // 呼吸周期3s
        private const val BREATH_SCALE_AMPLITUDE = 0.02f // 呼吸缩放幅度±2%
        private const val TALK_HEAD_AMPLITUDE = 0.01f // 说话头部晃动幅度±1%

        @Volatile
        private var instance: MicroAnimationLayer? = null

        fun getInstance(): MicroAnimationLayer {
            return instance ?: synchronized(this) {
                instance ?: MicroAnimationLayer().also { instance = it }
            }
        }
    }

    private var startTime = 0L
    private var isRunning = false

    // 呼吸缩放缓存：30fps更新一次足够，减少sin计算
    private var cachedScale = 1f
    private var cachedOffsetY = 0f
    private var lastCacheTime = 0L
    private val CACHE_INTERVAL_MS = 33L // 30fps

    /**
     * 微动画结果：仅整体变换参数
     */
    data class MicroAnimResult(
        val scale: Float,
        val offsetY: Float
    )

    fun start() {
        if (!isRunning) {
            startTime = System.currentTimeMillis()
            isRunning = true
        }
    }

    fun stop() {
        isRunning = false
    }

    /**
     * 叠加微动画，仅返回整体缩放和Y偏移
     * 性能优化：呼吸缩放30fps更新一次缓存，其余帧直接读取缓存
     */
    fun apply(currentType: ExpressionType): MicroAnimResult {
        if (!isRunning) {
            return MicroAnimResult(1f, 0f)
        }

        val now = System.currentTimeMillis()

        // 缓存命中：30fps更新一次即可，呼吸动画慢，不需要60fps
        if (now - lastCacheTime < CACHE_INTERVAL_MS) {
            return MicroAnimResult(cachedScale, cachedOffsetY)
        }
        lastCacheTime = now

        val timeFromStart = now - startTime

        // 1. 呼吸缩放：3s周期±2%
        cachedScale = 1f + sin(2f * PI.toFloat() * timeFromStart / BREATH_PERIOD_MS) * BREATH_SCALE_AMPLITUDE

        // 2. 说话时头部轻微上下晃动
        cachedOffsetY = if (currentType == ExpressionType.SPEAKING) {
            TALK_HEAD_AMPLITUDE * sin(timeFromStart * 0.01f) * 20f
        } else {
            0f
        }

        return MicroAnimResult(cachedScale, cachedOffsetY)
    }
}
