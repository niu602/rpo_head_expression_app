/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation.energy

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * 头部轮廓蒙版
 *
 * 支持三种蒙版形状：
 * - RECTANGLE：圆角矩形（默认关闭时退化为全屏）
 * - OVAL：椭圆
 * - CUSTOM：自定义 Path
 *
 * 羽化效果：用多圈渐隐的 clipPath 实现，从内到外逐圈缩小，
 * 每圈降低 alpha，形成 2~3 像素的柔和边缘渐变。
 *
 * 使用方式：
 * 1. 调用 setShape() 配置蒙版形状
 * 2. 在渲染流程中调用 applyMask() 包裹内容绘制
 */
class EnergyFieldMask {

    /**
     * 蒙版形状类型
     */
    enum class MaskShape {
        /** 全屏（默认关闭蒙版） */
        NONE,
        /** 圆角矩形 */
        RECTANGLE,
        /** 椭圆 */
        OVAL,
        /** 自定义路径 */
        CUSTOM
    }

    /**
     * 蒙版配置参数
     *
     * @property shape          形状类型
     * @property insetFraction  距屏幕边缘的内缩比例（0~0.2），控制蒙版大小
     * @property cornerRadiusFraction  圆角半径占短边的比例（仅 RECTANGLE）
     * @property featherSteps   羽化圈数（2~5，越大边缘越柔和但更多 drawCall）
     * @property featherWidthPx 羽化总宽度（像素）
     */
    data class MaskConfig(
        val shape: MaskShape = MaskShape.NONE,
        val insetFraction: Float = 0.05f,
        val cornerRadiusFraction: Float = 0.15f,
        val featherSteps: Int = 3,
        val featherWidthPx: Float = 3f
    )

    /** 当前蒙版配置 */
    var config: MaskConfig = MaskConfig()
        private set

    /** 自定义路径（仅 shape=CUSTOM 时使用） */
    var customPath: Path? = null
        private set

    /** 预创建的路径缓存对象 */
    private val maskPath = Path()
    private val featherPath = Path()

    /** 尺寸缓存 */
    private var cachedSize: Size = Size.Zero

    /**
     * 设置蒙版形状（圆角矩形）
     */
    fun setRectangle(
        insetFraction: Float = 0.05f,
        cornerRadiusFraction: Float = 0.15f,
        featherSteps: Int = 3,
        featherWidthPx: Float = 3f
    ) {
        config = MaskConfig(
            shape = MaskShape.RECTANGLE,
            insetFraction = insetFraction,
            cornerRadiusFraction = cornerRadiusFraction,
            featherSteps = featherSteps,
            featherWidthPx = featherWidthPx
        )
        cachedSize = Size.Zero // 强制重建路径
    }

    /**
     * 设置蒙版形状（椭圆）
     */
    fun setOval(
        insetFraction: Float = 0.05f,
        featherSteps: Int = 3,
        featherWidthPx: Float = 3f
    ) {
        config = MaskConfig(
            shape = MaskShape.OVAL,
            insetFraction = insetFraction,
            cornerRadiusFraction = 0f,
            featherSteps = featherSteps,
            featherWidthPx = featherWidthPx
        )
        cachedSize = Size.Zero
    }

    /**
     * 设置自定义蒙版路径
     */
    fun setCustom(
        path: Path,
        featherSteps: Int = 3,
        featherWidthPx: Float = 3f
    ) {
        customPath = path
        config = MaskConfig(
            shape = MaskShape.CUSTOM,
            featherSteps = featherSteps,
            featherWidthPx = featherWidthPx
        )
        cachedSize = Size.Zero
    }

    /**
     * 关闭蒙版（全屏，无裁剪）
     */
    fun clear() {
        config = config.copy(shape = MaskShape.NONE)
    }

    /**
     * 判断蒙版是否启用
     */
    fun isActive(): Boolean = config.shape != MaskShape.NONE

    // ==================== 内部路径构建 ====================

    /**
     * 根据配置和尺寸构建蒙版路径
     * @param size 画布尺寸
     * @param shrinkInset 额外内缩量（用于羽化圈逐层缩小）
     * @return 构建好的 Path
     */
    private fun buildMaskPath(size: Size, shrinkInset: Float = 0f): Path {
        val totalInset = size.width * config.insetFraction + shrinkInset
        val left = totalInset
        val top = totalInset
        val right = size.width - totalInset
        val bottom = size.height - totalInset

        maskPath.reset()

        when (config.shape) {
            MaskShape.NONE -> {
                // 全屏矩形
                maskPath.addRect(Rect(0f, 0f, size.width, size.height))
            }
            MaskShape.RECTANGLE -> {
                val w = right - left
                val h = bottom - top
                val r = minOf(w, h) * config.cornerRadiusFraction
                maskPath.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left, top, right, bottom,
                        androidx.compose.ui.geometry.CornerRadius(r, r)
                    )
                )
            }
            MaskShape.OVAL -> {
                maskPath.addOval(Rect(left, top, right, bottom))
            }
            MaskShape.CUSTOM -> {
                customPath?.let { maskPath.addPath(it) }
                    ?: maskPath.addRect(Rect(0f, 0f, size.width, size.height))
            }
        }

        return maskPath
    }

    // ==================== 对外接口 ====================

    /**
     * 应用蒙版裁剪 + 羽化效果
     *
     * 调用方式：
     * ```
     * mask.applyMask(drawScope, size) {
     *     // 在此块内绘制所有能量场内容
     * }
     * ```
     *
     * 原理：
     * 1. 最外层用 clipPath 裁剪到蒙版形状（硬边界）
     * 2. 在蒙版边缘内侧绘制多圈渐隐的暗色描边，模拟 alpha 羽化
     * 3. 每圈内缩 featherWidth/steps 像素，alpha 递增，形成柔和过渡
     *
     * @param block 在裁剪区域内执行的绘制操作
     */
    fun DrawScope.applyMask(size: Size, block: DrawScope.() -> Unit) {
        if (!isActive()) {
            // 蒙版关闭：直接绘制，不裁剪
            block()
            return
        }

        // 尺寸变化时重建路径
        if (size != cachedSize) {
            cachedSize = size
        }

        // 构建蒙版路径
        buildMaskPath(size)

        // 用 clipPath 裁剪绘制内容
        clipPath(maskPath) {
            block()
        }

        // 在裁剪区域边缘绘制羽化渐隐层
        drawFeatherEdges(size)
    }

    /**
     * 绘制羽化边缘：从蒙版边缘向内逐圈渐隐
     * 用多圈缩小的路径描边，每圈降低透明度，形成柔和 alpha 渐变
     */
    private fun DrawScope.drawFeatherEdges(size: Size) {
        val steps = config.featherSteps.coerceIn(1, 5)
        val totalWidth = config.featherWidthPx

        for (i in 0 until steps) {
            // 每圈内缩量
            val shrink = totalWidth * (i + 1) / steps
            // alpha 从外（高）到内（低）递减，形成渐隐到透明
            val alpha = 1f - (i + 1).toFloat() / steps

            // 构建缩小后的路径
            buildMaskPath(size, shrink)

            // 用路径的差集获取边缘环形区域
            // 直接用描边模拟：沿缩小路径画粗描边
            drawPath(
                path = maskPath,
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = alpha * 0.3f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = totalWidth / steps + 0.5f
                )
            )
        }
    }
}
