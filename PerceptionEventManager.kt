package com.eyebot.communication

import com.eyebot.protocol.EyeBotProto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 感知事件上报管理器
 *
 * 负责采集并回传以下感知事件给主控:
 *   - 触摸事件 (TouchEvent)
 *   - 人脸检测事件 (FaceDetectEvent)
 *   - 手势识别事件 (GestureEvent)
 *   - 声源定位事件 (AudioSourceEvent)
 *
 * 所有事件通过当前活跃通信链路上行发送，形成双向通信闭环
 */
class PerceptionEventManager(
    private val commManager: CommunicationManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val msgIdGenerator = java.util.concurrent.atomic.AtomicInteger(0)

    // 事件节流控制
    private var lastTouchTime = 0L
    private var lastFaceTime = 0L
    private var lastGestureTime = 0L
    private var lastAudioTime = 0L

    // 节流阈值 (ms)
    var touchThrottleMs = 50L      // 触摸事件最高20fps
    var faceThrottleMs = 200L      // 人脸检测最高5fps
    var gestureThrottleMs = 300L   // 手势识别最高~3fps
    var audioThrottleMs = 100L     // 声源定位最高10fps

    // ============================================================
    //  触摸事件上报
    // ============================================================

    /**
     * 上报触摸事件
     */
    fun reportTouch(
        x: Float,
        y: Float,
        action: EyeBotProto.TouchEvent.TouchAction,
        touchId: Int = 0,
        pointerCount: Int = 1,
        durationMs: Long = 0
    ) {
        val now = System.currentTimeMillis()
        if (action == EyeBotProto.TouchEvent.TouchAction.TOUCH_MOVE
            && now - lastTouchTime < touchThrottleMs
        ) {
            return // MOVE事件节流
        }
        lastTouchTime = now

        val event = EyeBotProto.TouchEvent.newBuilder()
            .setTouchId(touchId)
            .setX(x)
            .setY(y)
            .setAction(action)
            .setDurationMs(durationMs)
            .setPointerCount(pointerCount)
            .build()

        val message = buildEventMessage(EyeBotProto.CmdType.EVT_TOUCH).toBuilder()
            .setTouchEvent(event)
            .build()

        commManager.sendPerceptionEvent(message)
    }

    /**
     * 快捷上报点击事件
     */
    fun reportTap(x: Float, y: Float) {
        reportTouch(x, y, EyeBotProto.TouchEvent.TouchAction.TOUCH_UP)
    }

    /**
     * 快捷上报长按事件
     */
    fun reportLongPress(x: Float, y: Float, durationMs: Long) {
        reportTouch(x, y, EyeBotProto.TouchEvent.TouchAction.TOUCH_LONG_PRESS, durationMs = durationMs)
    }

    /**
     * 快捷上报双击事件
     */
    fun reportDoubleTap(x: Float, y: Float) {
        reportTouch(x, y, EyeBotProto.TouchEvent.TouchAction.TOUCH_DOUBLE_TAP)
    }

    // ============================================================
    //  人脸检测事件上报
    // ============================================================

    /**
     * 上报人脸检测结果
     */
    fun reportFaceDetect(
        hasFace: Boolean,
        faceCount: Int = 0,
        faces: List<FaceInfo> = emptyList()
    ) {
        val now = System.currentTimeMillis()
        if (now - lastFaceTime < faceThrottleMs) return
        lastFaceTime = now

        val builder = EyeBotProto.FaceDetectEvent.newBuilder()
            .setHasFace(hasFace)
            .setFaceCount(faceCount)

        faces.forEach { face ->
            builder.addFaces(
                EyeBotProto.FaceDetectEvent.FaceInfo.newBuilder()
                    .setCenterX(face.centerX)
                    .setCenterY(face.centerY)
                    .setWidthRatio(face.widthRatio)
                    .setConfidence(face.confidence)
            )
        }

        val message = buildEventMessage(EyeBotProto.CmdType.EVT_FACE_DETECT).toBuilder()
            .setFaceDetectEvent(builder.build())
            .build()

        commManager.sendPerceptionEvent(message)
    }

    data class FaceInfo(
        val centerX: Float,
        val centerY: Float,
        val widthRatio: Float,
        val confidence: Float
    )

    // ============================================================
    //  手势识别事件上报
    // ============================================================

    /**
     * 上报手势识别结果
     */
    fun reportGesture(
        gesture: EyeBotProto.GestureEvent.GestureType,
        confidence: Float,
        dirX: Float = 0f,
        dirY: Float = 0f
    ) {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < gestureThrottleMs) return
        lastGestureTime = now

        val event = EyeBotProto.GestureEvent.newBuilder()
            .setGesture(gesture)
            .setConfidence(confidence)
            .setDirectionX(dirX)
            .setDirectionY(dirY)
            .build()

        val message = buildEventMessage(EyeBotProto.CmdType.EVT_GESTURE).toBuilder()
            .setGestureEvent(event)
            .build()

        commManager.sendPerceptionEvent(message)
    }

    // ============================================================
    //  声源定位事件上报
    // ============================================================

    /**
     * 上报声源定位结果
     */
    fun reportAudioSource(
        hasSound: Boolean,
        angle: Float = 0f,
        energy: Float = 0f,
        durationMs: Int = 0
    ) {
        val now = System.currentTimeMillis()
        if (now - lastAudioTime < audioThrottleMs) return
        lastAudioTime = now

        val event = EyeBotProto.AudioSourceEvent.newBuilder()
            .setHasSound(hasSound)
            .setAngle(angle)
            .setEnergy(energy)
            .setDurationMs(durationMs)
            .build()

        val message = buildEventMessage(EyeBotProto.CmdType.EVT_AUDIO_SOURCE).toBuilder()
            .setAudioSourceEvent(event)
            .build()

        commManager.sendPerceptionEvent(message)
    }

    // ============================================================
    //  状态周期上报 (UDP高频)
    // ============================================================

    /**
     * 构建状态上报数据，由外部周期性调用
     */
    fun buildStatusReport(
        currentMode: EyeBotProto.WorkMode,
        eyelidOpen: Float,
        pupilSize: Float,
        pupilOffsetX: Float,
        pupilOffsetY: Float,
        eyeShape: EyeBotProto.EyeShape,
        batteryLevel: Int,
        cpuUsage: Float,
        memoryKb: Int,
        fps: Int,
        uptimeMs: Long
    ): EyeBotProto.StatusReport {
        return EyeBotProto.StatusReport.newBuilder()
            .setCurrentMode(currentMode)
            .setEyelidOpen(eyelidOpen)
            .setPupilSize(pupilSize)
            .setPupilOffsetX(pupilOffsetX)
            .setPupilOffsetY(pupilOffsetY)
            .setEyeShape(eyeShape)
            .setBatteryLevel(batteryLevel)
            .setCpuUsage(cpuUsage)
            .setMemoryUsageKb(memoryKb)
            .setFps(fps)
            .setActiveLink(commManager.activeLink.value.toProtoLinkType())
            .setUptimeMs(uptimeMs)
            .build()
    }

    // ============================================================
    //  内部工具
    // ============================================================

    private fun buildEventMessage(cmdType: EyeBotProto.CmdType): EyeBotProto.EyeBotMessage {
        return EyeBotProto.EyeBotMessage.newBuilder()
            .setHeader(
                EyeBotProto.MessageHeader.newBuilder()
                    .setMsgId(msgIdGenerator.incrementAndGet())
                    .setCmdType(cmdType)
                    .setTimestamp(System.currentTimeMillis())
                    .setLinkType(commManager.activeLink.value.toProtoLinkType())
            )
            .build()
    }

    private fun LinkType.toProtoLinkType(): EyeBotProto.LinkType {
        return when (this) {
            LinkType.LINK_TCP -> EyeBotProto.LinkType.LINK_TCP
            LinkType.LINK_UDP -> EyeBotProto.LinkType.LINK_UDP
            LinkType.LINK_USB_SERIAL -> EyeBotProto.LinkType.LINK_USB_SERIAL
            LinkType.LINK_BLE -> EyeBotProto.LinkType.LINK_BLE
            else -> EyeBotProto.LinkType.LINK_UNKNOWN
        }
    }

    fun release() {
        scope.cancel()
    }
}
