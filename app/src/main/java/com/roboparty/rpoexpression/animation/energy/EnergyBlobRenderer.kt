/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.energy

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.roboparty.rpoexpression.animation.BlobCalculator
import com.roboparty.rpoexpression.expression.EnergyFieldParameters
import com.roboparty.rpoexpression.animation.energy.EnergyCoreRenderer.Companion.hslToColor
import kotlin.math.sin

/**
 * L2 流体光场渲染器（中层）
 *
 * 绘制内容：
 * 1. Blob 轮廓：调用 BlobCalculator 计算参数化超椭圆路径
 * 2. 径向渐变填充：中心亮、边缘暗，模拟发光体
 * 3. 边缘外发光：用 widening path + 低 alpha 描边叠加
 * 4. 内部流动纹理：用预生成的 LUT Bitmap（噪声纹理），通过 Matrix 平移采样，低 alpha 叠加
 *
 * 性能优化：
 * - Path、Paint、Matrix、Shader 全部预创建为成员变量，零每帧分配
 * - LUT 纹理启动时预生成 128×128 Bitmap
 */
class EnergyBlobRenderer {

    // ========== 零分配缓存对象 ==========
    private val blobPath = Path()
    private val glowPath = Path()
    private val textureMatrix = Matrix()
    private var flowShader: BitmapShader? = null
    private var lutBitmap: Bitmap? = null
    private var lutInitialized = false

    // ========== 颜色缓存 ==========
    private var cachedHue = Float.MAX_VALUE
    private var cachedSat = -1f
    private var cachedLight = -1f
    private var primaryColor: Color = Color.White

    // ========== Paint 对象（用于纹理叠加） ==========
    private val texturePaint = Paint()

    /**
     * 初始化 LUT 噪声纹理
     * 必须在 RenderThread 或主线程首次调用前执行一次
     */
    fun init() {
        if (lutInitialized) return
        lutInitialized = true
        lutBitmap = generateLutNoiseBitmap()
        lutBitmap?.let { bmp ->
            flowShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
                texturePaint.shader = it
            }
        }
    }

    /**
     * 释放资源（可选调用）
     */
    fun release() {
        lutBitmap?.recycle()
        lutBitmap = null
        flowShader = null
        lutInitialized = false
    }

    /**
     * 渲染 L2 流体光场
     *
     * @param params   能量场参数
     * @param centerX  Blob 中心 X
     * @param centerY  Blob 中心 Y
     * @param size     画布尺寸
     * @param time     当前时间（秒），驱动扰动和流动
     */
    fun DrawScope.renderBlob(
        params: EnergyFieldParameters,
        centerX: Float,
        centerY: Float,
        size: Size,
        time: Float
    ) {
        if (!lutInitialized) init()
        updateColorCache(params)

        val baseDim = minOf(size.width, size.height)

        // 脉动效果：随时间周期性膨胀收缩
        val pulse = 1f + params.blobPulseAmp * sin(time * params.blobPulseFreq * 2f * PI_FLOAT)

        // Blob 尺寸
        val blobW = baseDim * params.blobWidthRatio * pulse
        val blobH = baseDim * params.blobHeightRatio * pulse

        // ===== 1. 计算 Blob 路径 =====
        BlobCalculator.calculateBlobPath(
            path = blobPath,
            centerX = centerX,
            centerY = centerY,
            width = blobW,
            height = blobH,
            exponent = params.blobExponent,
            distortion = params.blobDistortion,
            distortionSpeed = params.blobDistortionSpeed,
            tilt = params.blobTilt,
            time = time
        )

        // ===== 2. 径向渐变填充（中心亮 → 边缘暗） =====
        val blobRadius = maxOf(blobW, blobH) * 0.5f
        drawPath(
            path = blobPath,
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.85f),
                    primaryColor.copy(alpha = 0.4f),
                    primaryColor.copy(alpha = 0.05f)
                ),
                radius = blobRadius,
                center = Offset(centerX, centerY)
            ),
            style = Fill
        )

        // ===== 3. 内部流动纹理（LUT 采样 + 低 alpha 叠加） =====
        if (params.flowTextureOpacity > 0.01f && flowShader != null) {
            drawFlowTexture(params, blobW, blobH, time)
        }

        // ===== 4. 边缘外发光（widening path + 低 alpha 描边） =====
        BlobCalculator.calculateBlobPath(
            path = glowPath,
            centerX = centerX,
            centerY = centerY,
            width = blobW * 1.1f,
            height = blobH * 1.1f,
            exponent = params.blobExponent,
            distortion = params.blobDistortion,
            distortionSpeed = params.blobDistortionSpeed,
            tilt = params.blobTilt,
            time = time
        )
        drawPath(
            path = glowPath,
            color = primaryColor.copy(alpha = 0.15f),
            style = Stroke(width = baseDim * 0.02f)
        )
    }

    /**
     * 绘制内部流动纹理
     * 通过 Matrix 平移 BitmapShader 的采样坐标，产生流动感
     */
    private fun DrawScope.drawFlowTexture(
        params: EnergyFieldParameters,
        blobW: Float,
        blobH: Float,
        time: Float
    ) {
        // 纹理流动偏移：随 time 移动
        val offsetX = (time * params.flowTextureSpeed * LUT_SIZE * 0.5f) % LUT_SIZE
        val offsetY = (time * params.flowTextureSpeed * LUT_SIZE * 0.3f) % LUT_SIZE

        // 构建 Matrix：平移 + 缩放到 Blob 尺寸
        val scaleX = blobW / LUT_SIZE
        val scaleY = blobH / LUT_SIZE
        textureMatrix.reset()
        textureMatrix.setTranslate(-offsetX, -offsetY)
        textureMatrix.postScale(scaleX, scaleY)
        flowShader?.setLocalMatrix(textureMatrix)

        // 设置纹理透明度
        texturePaint.color = Color.White.copy(alpha = params.flowTextureOpacity)

        // 在 Blob 路径内裁剪绘制纹理
        drawPath(
            path = blobPath,
            brush = androidx.compose.ui.graphics.ShaderBrush(flowShader!!),
            alpha = params.flowTextureOpacity,
            style = Fill
        )
    }

    /**
     * 预生成 128×128 的噪声纹理 Bitmap（正弦叠加模拟柏林噪声）
     * 静态生成一次，运行时仅平移采样坐标
     */
    private fun generateLutNoiseBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(LUT_SIZE, LUT_SIZE, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(LUT_SIZE * LUT_SIZE)

        for (y in 0 until LUT_SIZE) {
            for (x in 0 until LUT_SIZE) {
                // 3 个正弦波叠加，模拟低频噪声
                val nx = x.toFloat() / LUT_SIZE
                val ny = y.toFloat() / LUT_SIZE
                val v = (
                    0.5f * sin(nx * 2f * PI_FLOAT) * sin(ny * 2f * PI_FLOAT) +
                    0.3f * sin(nx * 4f * PI_FLOAT + 1f) * sin(ny * 3f * PI_FLOAT) +
                    0.2f * sin(nx * 6f * PI_FLOAT + 2f) * sin(ny * 5f * PI_FLOAT + 0.5f)
                )
                // 归一化到 0~255
                val alpha = ((v * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
                pixels[y * LUT_SIZE + x] = alpha shl 24 // ALPHA_8 格式，仅 alpha 通道
            }
        }
        bmp.setPixels(pixels, 0, LUT_SIZE, 0, 0, LUT_SIZE, LUT_SIZE)
        return bmp
    }

    /**
     * 更新颜色缓存
     */
    private fun updateColorCache(params: EnergyFieldParameters) {
        if (params.primaryHue != cachedHue ||
            params.primarySaturation != cachedSat ||
            params.primaryLightness != cachedLight
        ) {
            cachedHue = params.primaryHue
            cachedSat = params.primarySaturation
            cachedLight = params.primaryLightness
            primaryColor = hslToColor(
                params.primaryHue,
                params.primarySaturation,
                params.primaryLightness
            )
        }
    }

    companion object {
        private val PI_FLOAT = Math.PI.toFloat()
        private const val LUT_SIZE = 128
    }
}
