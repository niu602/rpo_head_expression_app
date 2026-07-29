/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.sensor

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音量管理器
 * 通过右侧边缘上下滑动手势调节媒体音量，类似视频播放器交互
 *
 * 功能：
 * 1. 单指上滑 → 逐级提高音量
 * 2. 单指下滑 → 逐级降低音量
 * 3. 滑到最低/最高音量时，触发快速眨眼提示边界
 * 4. 音量调节实时生效，系统自动持久化
 */
class VolumeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VolumeManager"

        // 滑动灵敏度：每滑动多少像素调节一级
        private const val SWIPE_SENSITIVITY = 30f

        @Volatile
        private var instance: VolumeManager? = null

        fun getInstance(context: Context): VolumeManager {
            return instance ?: synchronized(this) {
                instance ?: VolumeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 当前音量比例 0f~1f
    private val _volumeRatio = MutableStateFlow(getCurrentVolumeRatio())
    val volumeRatio: StateFlow<Float> = _volumeRatio.asStateFlow()

    // 音量视觉反馈强度（用于眼睛大小/光效变化）
    private val _volumeFeedback = MutableStateFlow(0f)
    val volumeFeedback: StateFlow<Float> = _volumeFeedback.asStateFlow()

    // 边界眨眼回调
    var onBoundaryReached: (() -> Unit)? = null

    // 滑动过程中的累积偏移
    private var swipeAccumulator = 0f

    // 反馈动画Job
    private var feedbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 最大音量
    private val maxVolume: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val minVolume: Int = 0

    /**
     * 启动
     */
    fun start() {
        _volumeRatio.value = getCurrentVolumeRatio()
        Log.d(TAG, "Volume manager started, current volume: ${_volumeRatio.value}")
    }

    /**
     * 停止
     */
    fun stop() {
        feedbackJob?.cancel()
    }

    /**
     * 获取当前音量比例
     */
    private fun getCurrentVolumeRatio(): Float {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return current.toFloat() / maxVolume.toFloat()
    }

    /**
     * 处理滑动手势
     * @param deltaY Y轴滑动偏移，负值=上滑（增大音量），正值=下滑（减小音量）
     * @return 是否到达边界
     */
    fun onSwipe(deltaY: Float): Boolean {
        swipeAccumulator += -deltaY // 上滑为正，增大音量

        var reachedBoundary = false

        while (Math.abs(swipeAccumulator) >= SWIPE_SENSITIVITY) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (swipeAccumulator > 0) {
                // 上滑：增大音量
                if (currentVolume >= maxVolume) {
                    reachedBoundary = true
                    break
                }
                swipeAccumulator -= SWIPE_SENSITIVITY
                adjustVolume(1)
            } else {
                // 下滑：减小音量
                if (currentVolume <= minVolume) {
                    reachedBoundary = true
                    break
                }
                swipeAccumulator += SWIPE_SENSITIVITY
                adjustVolume(-1)
            }
        }

        // 到达边界时重置累积器并触发回调
        if (reachedBoundary) {
            swipeAccumulator = 0f
            scope.launch {
                onBoundaryReached?.invoke()
            }
        }

        return reachedBoundary
    }

    /**
     * 滑动结束，重置累积器
     */
    fun onSwipeEnd() {
        swipeAccumulator = 0f
    }

    /**
     * 调节音量
     */
    private fun adjustVolume(delta: Int) {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (current + delta).coerceIn(minVolume, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0) // FLAG_REMOVE_SOUND_AND_VIBRATE=0
        _volumeRatio.value = newVolume.toFloat() / maxVolume.toFloat()

        // 触发视觉反馈
        triggerVolumeFeedback()

        Log.d(TAG, "Volume adjusted to: $newVolume/$maxVolume (${_volumeRatio.value})")
    }

    /**
     * 触发音量调节视觉反馈（瞳孔短暂放大）
     */
    private fun triggerVolumeFeedback() {
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            _volumeFeedback.value = 0.15f
            delay(150)
            _volumeFeedback.value = 0f
        }
    }
}
