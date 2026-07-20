/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.communication

import android.util.Log
import com.roboparty.rpoexpression.communication.link.CommLink
import java.util.concurrent.atomic.AtomicLong

/**
 * 感知事件管理器
 * 统一处理触摸、人脸、IMU等感知事件的结构化Protobuf上行
 * 形成交互闭环
 */
class PerceptionEventManager private constructor() {

    companion object {
        private const val TAG = "PerceptionEvent"

        // 事件上报节流（避免事件风暴）
        private const val TOUCH_THROTTLE_MS = 50L
        private const val FACE_THROTTLE_MS = 100L
        private const val IMU_THROTTLE_MS = 80L

        @Volatile
        private var instance: PerceptionEventManager? = null

        fun getInstance(): PerceptionEventManager {
            return instance ?: synchronized(this) {
                instance ?: PerceptionEventManager().also { instance = it }
            }
        }
    }

    private val multiLinkManager = MultiLinkManager.getInstance()
    private val codec = ProtobufCodec.getInstance()

    private val lastTouchReport = AtomicLong(0)
    private val lastFaceReport = AtomicLong(0)
    private val lastImuReport = AtomicLong(0)

    // ========== 触摸事件 ==========

    /**
     * 上报触摸事件
     * @param x X坐标(归一化0-1)
     * @param y Y坐标(归一化0-1)
     * @param action 动作: 0=按下,1=移动,2=抬起,3=长按,4=双击
     * @param pointerCount 触点数量
     */
    fun reportTouchEvent(
        x: Float, y: Float,
        action: Int,
        pointerCount: Int = 1,
        durationMs: Long = 0
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTouchReport.get() < TOUCH_THROTTLE_MS) return
        lastTouchReport.set(now)

        try {
            val data = codec.Writer().apply {
                // TouchEvent field 20
                writeMessage(20) {
                    writeFloat(2, x)
                    writeFloat(3, y)
                    writeEnum(4, action)
                    writeUint64(5, durationMs)
                    writeUint32(6, pointerCount)
                }
            }.toByteArray()

            val msg = codec.encodePerceptionEvent(
                msgId = multiLinkManager.nextMsgId(),
                seq = multiLinkManager.nextSeq(),
                eventType = 1, // 触摸
                linkType = multiLinkManager.getActiveLinkType().value,
            ) {
                // 写入触摸事件payload
                writeBytes(20, data.sliceArray(2 until data.size)) // 去掉头直接写payload
            }

            // 触摸事件走UDP（高频低延迟）
            multiLinkManager.sendUdp(msg)
            Log.v(TAG, "Touch event reported: x=$x, y=$y, action=$action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report touch event", e)
        }
    }

    // ========== 人脸事件 ==========

    /**
     * 上报人脸检测事件
     * @param hasFace 是否检测到人脸
     * @param faceCount 人脸数量
     * @param centerX 主脸中心X偏移(-1~1)
     * @param centerY 主脸中心Y偏移(-1~1)
     * @param faceSize 人脸大小(0-1)
     * @param smileProb 微笑概率(0-1)
     */
    fun reportFaceEvent(
        hasFace: Boolean,
        faceCount: Int,
        centerX: Float = 0f,
        centerY: Float = 0f,
        faceSize: Float = 0f,
        smileProb: Float = 0f
    ) {
        val now = System.currentTimeMillis()
        if (now - lastFaceReport.get() < FACE_THROTTLE_MS) return
        lastFaceReport.set(now)

        try {
            val data = codec.Writer().apply {
                // FaceDetectEvent field 21
                writeMessage(21) {
                    writeBool(1, hasFace)
                    writeUint32(2, faceCount)
                    // FaceInfo
                    if (hasFace && faceCount > 0) {
                        val faceInfo = codec.Writer().apply {
                            writeFloat(1, centerX)
                            writeFloat(2, centerY)
                            writeFloat(3, faceSize)
                            writeFloat(4, smileProb)
                        }.toByteArray()
                        // repeated faces field 3
                        // 简化：只写第一个人脸
                        writeBytes(3, faceInfo)
                    }
                }
            }.toByteArray()

            val msg = codec.encodePerceptionEvent(
                msgId = multiLinkManager.nextMsgId(),
                seq = multiLinkManager.nextSeq(),
                eventType = 2, // 人脸
                linkType = multiLinkManager.getActiveLinkType().value,
            ) {
                writeBytes(21, data.sliceArray(2 until data.size))
            }

            multiLinkManager.sendUdp(msg)
            Log.v(TAG, "Face event reported: count=$faceCount, smile=$smileProb")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report face event", e)
        }
    }

    // ========== IMU姿态事件 ==========

    /**
     * 上报IMU姿态事件
     * @param pitch 俯仰角(度)
     * @param roll 横滚角(度)
     * @param yaw 偏航角(度)
     * @param acceleration 加速度G值
     * @param action 动作类型: 0=倾斜,1=摇晃,2=点头,3=摇头
     */
    fun reportImuEvent(
        pitch: Float, roll: Float, yaw: Float,
        acceleration: Float = 1f,
        action: Int = 0
    ) {
        val now = System.currentTimeMillis()
        if (now - lastImuReport.get() < IMU_THROTTLE_MS) return
        lastImuReport.set(now)

        try {
            // IMU事件复用GestureEvent field 22
            val data = codec.Writer().apply {
                writeMessage(22) {
                    writeEnum(1, action)
                    writeFloat(2, 0.9f) // confidence
                    writeFloat(3, yaw)
                    writeFloat(4, pitch)
                }
            }.toByteArray()

            val msg = codec.encodePerceptionEvent(
                msgId = multiLinkManager.nextMsgId(),
                seq = multiLinkManager.nextSeq(),
                eventType = 3, // IMU/手势
                linkType = multiLinkManager.getActiveLinkType().value,
            ) {
                writeBytes(22, data.sliceArray(2 until data.size))
            }

            multiLinkManager.sendUdp(msg)
            Log.v(TAG, "IMU event reported: pitch=$pitch, roll=$roll, action=$action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report IMU event", e)
        }
    }

    /**
     * 上报状态（周期性，通过UDP）
     */
    fun reportStatus(
        batteryLevel: Int, fps: Int,
        currentExpr: Int, uptimeMs: Long
    ) {
        try {
            val msg = codec.encodeStatusReport(
                msgId = multiLinkManager.nextMsgId(),
                seq = multiLinkManager.nextSeq(),
                batteryLevel = batteryLevel,
                fps = fps,
                currentExpr = currentExpr,
                linkType = multiLinkManager.getActiveLinkType().value,
                uptimeMs = uptimeMs
            )
            multiLinkManager.sendUdp(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report status", e)
        }
    }
}
