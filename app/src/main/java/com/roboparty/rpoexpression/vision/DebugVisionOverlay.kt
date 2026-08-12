/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.roboparty.rpoexpression.recognition.FaceAligner

/**
 * 机器人视角调试窗口
 *
 * 半透明浮动小窗口，显示：
 * 1. 摄像头实际画面（旋转+镜像后的完整帧）
 * 2. 人脸检测框 + 5点关键点
 * 3. 识别结果 / 相似度 / 质量提示
 * 4. 注册进度（采集中时）
 *
 * 默认隐藏，通过 DebugVisionState.toggleVisible() 开关
 *
 * @param modifier 外部布局修饰符
 */
@Composable
fun DebugVisionOverlay(
    modifier: Modifier = Modifier
) {
    val visible by DebugVisionState.visible.collectAsState()
    if (!visible) return

    val frame by DebugVisionState.frame.collectAsState()
    val faceInfo by DebugVisionState.faceInfo.collectAsState()
    val recognition by DebugVisionState.recognition.collectAsState()
    val register by DebugVisionState.register.collectAsState()
    val pupilCal by DebugVisionState.pupilCalibration.collectAsState()

    Box(
        modifier = modifier
            .size(width = 180.dp, height = 280.dp) // 足够显示画面+信息
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xDD000000))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ========== 摄像头画面 + 人脸框叠加 ==========
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
                    .background(Color.Black)
            ) {
                // 画面
                frame.bitmap?.let { bmp ->
                    val mirrorX = frame.mirrorX

                    Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 居中等比缩放绘制摄像头画面
                        val bmpW = bmp.width.toFloat()
                        val bmpH = bmp.height.toFloat()
                        val scale = minOf(size.width / bmpW, size.height / bmpH)
                        val drawW = bmpW * scale
                        val drawH = bmpH * scale
                        val offsetX = (size.width - drawW) / 2f
                        val offsetY = (size.height - drawH) / 2f

                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt())
                        )

                        // 在画面上叠加人脸检测信息
                        // ML Kit返回的坐标已经是旋转后自然方向的，只需要镜像+缩放偏移
                        val sx = drawW / bmpW
                        val sy = drawH / bmpH
                        for (face in faceInfo.faces) {
                            drawFaceDetection(face, offsetX, offsetY, sx, sy, bmpW, mirrorX)
                        }
                    }
                } ?: Text(
                    text = "无画面",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // ========== 信息面板 ==========
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                // 人脸数
                val fc = frame.faceCount
                Text(
                    text = "人脸: ${fc} ${if (fc == 0) "⚠" else "✓"}",
                    color = if (fc > 0) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 11.sp
                )

                Spacer(modifier = Modifier.height(1.dp))

                // 质量信息（如果有检测到人脸）
                if (faceInfo.faces.isNotEmpty() && faceInfo.imgWidth > 0) {
                    val face = faceInfo.faces[0]
                    val q = FaceAligner.evaluateFaceQuality(face, faceInfo.imgWidth.toInt(), faceInfo.imgHeight.toInt())
                    val color = if (q.isGoodQuality) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    Text(
                        text = "偏转: ${"%.0f".format(q.yawAngle)}° 旋转: ${"%.0f".format(q.rollAngle)}°",
                        color = color,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 9.sp
                    )
                    Text(
                        text = "占屏: ${"%.0f".format(q.faceWidth.toFloat() / faceInfo.imgWidth * 100)}% 关键点: ${if (q.isAligned) "✓" else "✗"}",
                        color = color,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 9.sp
                    )
                    Text(
                        text = if (q.isGoodQuality) "质量✓" else "质量差",
                        color = color,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 9.sp
                    )
                }

                Spacer(modifier = Modifier.height(1.dp))

                // 识别结果
                if (recognition.resultText.isNotEmpty()) {
                    Text(
                        text = recognition.resultText,
                        color = when {
                            recognition.resultText.startsWith("✅") -> Color(0xFF4CAF50)
                            recognition.resultText.startsWith("❓") -> Color(0xFFFF9800)
                            recognition.resultText.startsWith("⚠") -> Color(0xFFE57373)
                            else -> Color.White
                        },
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 10.sp
                    )
                    if (recognition.qualityText.isNotEmpty()) {
                        Text(
                            text = recognition.qualityText,
                            color = Color(0xAAFFFFFF),
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 9.sp
                        )
                    }
                }

                // 注册进度
                if (register.isRegistering) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "注册: ${register.name} ${register.collected}/${register.total}",
                        color = Color(0xFF2196F3),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 10.sp
                    )
                    // 进度条
                    if (register.total > 0) {
                        val progress = register.collected.toFloat() / register.total
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .padding(top = 2.dp)
                        ) {
                            drawRect(Color(0x33FFFFFF))
                            drawRect(Color(0xFF2196F3), size = androidx.compose.ui.geometry.Size(size.width * progress, size.height))
                        }
                    }
                }

                // 瞳孔偏移校准信息
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "── 瞳孔校准 ──",
                    color = Color(0xFFFFC107),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 9.sp
                )
                Text(
                    text = "原始偏移: X=${"%.2f".format(pupilCal.rawOffsetX)} Y=${"%.2f".format(pupilCal.rawOffsetY)}",
                    color = Color(0xAAFFFFFF),
                    fontSize = 6.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 8.sp
                )
                Text(
                    text = "校正后: X=${"%.2f".format(pupilCal.correctedOffsetX)} Y=${"%.2f".format(pupilCal.correctedOffsetY)}",
                    color = Color(0xFF4CAF50),
                    fontSize = 6.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 8.sp
                )
                Text(
                    text = "X校正: ${"%.2f".format(pupilCal.cameraOffsetCorrectionX)}  Y校正: ${"%.2f".format(pupilCal.cameraOffsetCorrectionY)}",
                    color = Color(0xAAFFFFFF),
                    fontSize = 6.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 8.sp
                )
                Text(
                    text = "跟随系数: ${"%.1f".format(pupilCal.pupilFollowFactor)}",
                    color = Color(0xAAFFFFFF),
                    fontSize = 6.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 8.sp
                )
                Text(
                    text = "校准方法: 正视机器人眼睛，",
                    color = Color(0xFFFFC107),
                    fontSize = 6.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 8.sp
                )
                Text(
                    text = "调整Y校正值使校正后Y≈0",
                    color = Color(0xFFFFC107),
                    fontSize = 6.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 8.sp
                )
            }
        }
    }
}

/**
 * 在Canvas上绘制单个人脸的检测框和关键点
 *
 * 坐标转换流程：
 * 原始帧坐标 → 旋转（对应rotationDegrees）→ 水平镜像（前置摄像头）→ 缩放到绘制区域 → 加偏移
 */
private fun DrawScope.drawFaceDetection(
    face: Face,
    offsetX: Float,
    offsetY: Float,
    sx: Float,
    sy: Float,
    bmpW: Float,
    mirrorX: Boolean
) {
    // 辅助函数：转换单个点坐标
    // ML Kit返回的坐标已经是旋转后自然方向的坐标，只需要处理我们自己加的水平镜像
    fun transformPoint(x: Float, y: Float): Pair<Float, Float> {
        var px = x
        var py = y

        // 1. 水平镜像（前置摄像头）：x = Bitmap宽度 - x
        if (mirrorX) {
            px = bmpW - px
        }

        // 2. 缩放+偏移到绘制区域
        return Pair(offsetX + px * sx, offsetY + py * sy)
    }

    // 转换人脸框四个角
    val box = face.boundingBox
    val (l, t) = transformPoint(box.left.toFloat(), box.top.toFloat())
    val (r, b) = transformPoint(box.right.toFloat(), box.bottom.toFloat())
    val left = minOf(l, r)
    val top = minOf(t, b)
    val right = maxOf(l, r)
    val bottom = maxOf(t, b)

    // 人脸框（绿色=检测到）
    drawRect(
        color = Color(0xFF00E676),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
        style = Stroke(width = 2f)
    )

    // 5点关键点（黄色）
    val landmarkColor = Color(0xFFFFEB3B)
    val landmarks = listOf(
        FaceLandmark.LEFT_EYE,
        FaceLandmark.RIGHT_EYE,
        FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT
    )
    for (lm in landmarks) {
        face.getLandmark(lm)?.position?.let { pos ->
            val (cx, cy) = transformPoint(pos.x, pos.y)
            drawCircle(
                color = landmarkColor,
                radius = 3f,
                center = Offset(cx, cy)
            )
        }
    }
}
