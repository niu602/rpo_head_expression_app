/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 参数化 Blob 轮廓计算器
 *
 * 核心算法：
 * 1. 标准Lamé超椭圆参数方程：|x/a|^n + |y/b|^n = 1，n=2时为完美正圆/椭圆
 * 2. 叠加 3 个低频正弦波扰动，使边缘呈现有机不规则
 * 3. 倾斜变形（上扬/下垂）：通过 y 轴方向的非对称缩放实现
 * 4. 预计算正弦/余弦查找表（256 步），避免每帧调用 Math.sin/cos
 * 5. 三次贝塞尔曲线平滑连接所有点，完全无棱角，有机感
 *
 * 性能：每帧 48 点 × (3 次查表 + 基础运算) ≈ ~0.3ms/帧
 */
object BlobCalculator {

    /** 查找表大小 */
    private const val LUT_SIZE = 256
    private const val LUT_SIZE_F = LUT_SIZE.toFloat()

    /** 2π 常量 */
    private val TWO_PI = (2.0 * Math.PI).toFloat()

    /** 预计算正弦查找表：sinTab[0..255] = sin(2π·i/256)，范围 [-1, 1] */
    private val sinTab: FloatArray = FloatArray(LUT_SIZE) { i ->
        sin(2.0 * Math.PI * i / LUT_SIZE).toFloat()
    }

    /** 预计算余弦查找表：cosTab[0..255] = cos(2π·i/256)，范围 [-1, 1] */
    private val cosTab: FloatArray = FloatArray(LUT_SIZE) { i ->
        cos(2.0 * Math.PI * i / LUT_SIZE).toFloat()
    }

    /** 轮廓点数（固定，不随参数变化） */
    const val POINT_COUNT = 64 // 增加到64个点，更光滑

    /** 临时坐标缓存，避免每帧分配数组 */
    private val pointX = FloatArray(POINT_COUNT)
    private val pointY = FloatArray(POINT_COUNT)

    /**
     * 快速正弦查表，输入为弧度
     */
    private fun sinT(rad: Float): Float {
        val idx = ((rad / TWO_PI) * LUT_SIZE_F).toInt() and 0xFF
        return sinTab[idx]
    }

    /**
     * 快速余弦查表，输入为弧度
     */
    private fun cosT(rad: Float): Float {
        val idx = ((rad / TWO_PI) * LUT_SIZE_F).toInt() and 0xFF
        return cosTab[idx]
    }

    /**
     * 计算 Blob 轮廓路径并写入 [path]（零对象分配）
     *
     * @param path           复用的 Path 对象（调用前会被 reset）
     * @param centerX        中心 X 坐标
     * @param centerY        中心 Y 坐标
     * @param width          Blob 最大宽度（像素，总直径）
     * @param height         Blob 最大高度（像素，总直径）
     * @param exponent       超椭圆指数 n（2=正圆/椭圆，越大越方）
     * @param distortion     边缘扰动幅度（0~1）
     * @param distortionSpeed 扰动演化速度
     * @param tilt           倾斜（-1=下垂，0=水平，1=上扬）
     * @param time           当前时间（秒），驱动扰动动画
     * @return               写入后的 [path]
     */
    fun calculateBlobPath(
        path: Path,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        exponent: Float,
        distortion: Float,
        distortionSpeed: Float,
        tilt: Float,
        time: Float
    ): Path {
        // 限制超椭圆指数范围，最小2（正圆）
        val n = exponent.coerceIn(2f, 6f)

        // 半轴长度
        val a = width * 0.5f
        val b = height * 0.5f

        // 超椭圆指数预处理：2/n
        val n2 = 2f / n

        // 扰动相位（随时间变化）
        val phase1 = time * distortionSpeed
        val phase2 = time * distortionSpeed * 1.3f
        val phase3 = time * distortionSpeed * 0.7f

        // 计算所有轮廓点
        for (i in 0 until POINT_COUNT) {
            val angle = i * TWO_PI / POINT_COUNT
            val cosA = cosT(angle)
            val sinA = sinT(angle)

            // ===== 1. 标准Lamé超椭圆参数方程（n=2时为完美正圆）=====
            val dxBase = a * Math.copySign(
                Math.pow(Math.abs(cosA).toDouble(), n2.toDouble()),
                cosA.toDouble()
            ).toFloat()
            val dyBase = b * Math.copySign(
                Math.pow(Math.abs(sinA).toDouble(), n2.toDouble()),
                sinA.toDouble()
            ).toFloat()

            // ===== 2. 叠加边缘扰动 =====
            val distort = if (distortion > 0.001f) {
                distortion * (
                    0.5f * sinT(3f * angle + phase1) +
                    0.3f * sinT(5f * angle + phase2) +
                    0.2f * sinT(7f * angle + phase3)
                )
            } else {
                0f
            }
            val scale = 1f + distort
            var dx = dxBase * scale
            var dy = dyBase * scale

            // ===== 3. 倾斜变形 =====
            val tiltScale = when {
                sinA > 0f -> 1f - tilt * 0.2f
                else -> 1f + tilt * 0.2f
            }
            dy *= tiltScale

            // ===== 4. 写入缓存 =====
            pointX[i] = centerX + dx
            pointY[i] = centerY + dy
        }

        // ===== 构建Path：闭合三次贝塞尔样条，平滑有机，无棱角 =====
        // 使用 Catmull-Rom → Cubic Bezier 转换，控制点取相邻点切线方向
        path.reset()
        val step = 1f / 6f  // Catmull-Rom 张力系数
        path.moveTo(pointX[0], pointY[0])
        for (i in 0 until POINT_COUNT) {
            val p0Idx = (i - 1 + POINT_COUNT) % POINT_COUNT
            val p1Idx = i
            val p2Idx = (i + 1) % POINT_COUNT
            val p3Idx = (i + 2) % POINT_COUNT
            val cp1x = pointX[p1Idx] + (pointX[p2Idx] - pointX[p0Idx]) * step
            val cp1y = pointY[p1Idx] + (pointY[p2Idx] - pointY[p0Idx]) * step
            val cp2x = pointX[p2Idx] - (pointX[p3Idx] - pointX[p1Idx]) * step
            val cp2y = pointY[p2Idx] - (pointY[p3Idx] - pointY[p1Idx]) * step
            path.cubicTo(
                cp1x, cp1y,
                cp2x, cp2y,
                pointX[p2Idx], pointY[p2Idx]
            )
        }
        path.close()

        return path
    }

    /**
     * 获取指定索引的轮廓点坐标（供外部调用，如粒子定位）
     */
    fun getPoint(index: Int): Offset {
        val i = index % POINT_COUNT
        return Offset(pointX[i], pointY[i])
    }
}
