package com.roboparty.rpoexpression.animation.shape

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.roboparty.rpoexpression.config.ConfigManager
import com.roboparty.rpoexpression.expression.ExpressionStyle
import com.roboparty.rpoexpression.expression.ExpressionType
import kotlin.math.min

/**
 * 形状表情渲染器
 * 支持男性方形和女性圆形两种风格，复用同一套渲染逻辑
 */
object ShapeExpressionRenderer {

    // ========== 绘制入口 ==========

    /**
     * 绘制形状表情
     *
     * @param drawScope 绘制上下文
     * @param style 表情风格（SHAPE_MALE / SHAPE_FEMALE）
     * @param type 当前表情类型
     * @param size 画布尺寸
     * @param centerX 画布中心X
     * @param centerY 画布中心Y
     * @param pupilOffsetX 瞳孔X偏移（-1~1，相对眼睛宽度）
     * @param pupilOffsetY 瞳孔Y偏移（-1~1，相对眼睛高度）
     * @param transitionProgress 表情过渡进度（0~1，用于淡入淡出）
     * @param time 当前时间（秒，用于疑惑两帧动画）
     */
    fun render(
        drawScope: DrawScope,
        style: ExpressionStyle,
        type: ExpressionType,
        size: Size,
        centerX: Float,
        centerY: Float,
        pupilOffsetX: Float = 0f,
        pupilOffsetY: Float = 0f,
        transitionProgress: Float = 1f,
        time: Float = 0f
    ) {
        with(drawScope) {
            if (size.width <= 0f || size.height <= 0f) {
                return
            }

            // 获取当前风格的常量
            val config = getStyleConfig(style)

            // 计算缩放比例：适配画布，保持比例
            val scaleFactor = min(
                size.width / config.svgWidth,
                size.height / config.svgHeight
            ) * config.scaleRatio

            if (scaleFactor <= 0f || !scaleFactor.isFinite()) {
                return
            }

            // 计算偏移：让SVG居中显示
            val offsetX = centerX - scaleFactor * config.svgWidth / 2f
            val offsetY = centerY - scaleFactor * config.svgHeight / 2f

            // 显式变换：先平移后缩放，缩放基点为 SVG 坐标原点
            withTransform({
                translate(left = offsetX, top = offsetY)
                // 关键：围绕 SVG 坐标原点缩放，不能用默认的画布中心
                scale(
                    scaleX = scaleFactor,
                    scaleY = scaleFactor,
                    pivot = Offset.Zero
                )
            }) {
                // 获取当前表情形状
                val shape = getCurrentShape(style, type, time)

                // 计算透明度（过渡用）
                val alpha = transitionProgress.coerceIn(0f, 1f)

                // 绘制腮红（如果有）
                if (shape.hasBlush && shape.leftBlushPath != null && shape.rightBlushPath != null) {
                    drawBlush(shape.leftBlushPath, shape.rightBlushPath, config.blushColor, alpha)
                }

                // 绘制眼睛
                drawEyes(shape.leftEyePath, shape.rightEyePath, alpha)

                // 绘制瞳孔（女性悲伤表情瞳孔已镂空在眼睛里，跳过）
                val skipPupil = (style == ExpressionStyle.SHAPE_FEMALE && type == ExpressionType.SAD)
                if (!skipPupil) {
                    drawPupils(shape, config, pupilOffsetX, pupilOffsetY, alpha)
                }
            }
        }
    }

    // ========== 风格配置 ==========

    private data class StyleConfig(
        val svgWidth: Float,
        val svgHeight: Float,
        val leftEyeCenterX: Float,
        val rightEyeCenterX: Float,
        val eyeCenterY: Float,
        val eyeWidth: Float,
        val eyeHeight: Float,
        val scaleRatio: Float,
        val blushColor: Color
    )

    private fun getStyleConfig(style: ExpressionStyle): StyleConfig {
        return when (style) {
            ExpressionStyle.SHAPE_MALE -> StyleConfig(
                svgWidth = MaleSquareShapeLibrary.SVG_WIDTH,
                svgHeight = MaleSquareShapeLibrary.SVG_HEIGHT,
                leftEyeCenterX = MaleSquareShapeLibrary.LEFT_EYE_CENTER_X,
                rightEyeCenterX = MaleSquareShapeLibrary.RIGHT_EYE_CENTER_X,
                eyeCenterY = MaleSquareShapeLibrary.EYE_CENTER_Y,
                eyeWidth = 60f,
                eyeHeight = 55f,
                scaleRatio = 1.2f,
                blushColor = Color.LightGray
            )

            ExpressionStyle.SHAPE_FEMALE -> StyleConfig(
                svgWidth = FemaleCircleShapeLibrary.SVG_WIDTH,
                svgHeight = FemaleCircleShapeLibrary.SVG_HEIGHT,
                leftEyeCenterX = FemaleCircleShapeLibrary.LEFT_EYE_CENTER_X,
                rightEyeCenterX = FemaleCircleShapeLibrary.RIGHT_EYE_CENTER_X,
                eyeCenterY = FemaleCircleShapeLibrary.EYE_CENTER_Y,
                eyeWidth = 66f,
                eyeHeight = 66f,
                scaleRatio = 1.2f,
                blushColor = Color(0xFFFFB6C1) // 浅粉色
            )

            else -> StyleConfig(
                svgWidth = MaleSquareShapeLibrary.SVG_WIDTH,
                svgHeight = MaleSquareShapeLibrary.SVG_HEIGHT,
                leftEyeCenterX = MaleSquareShapeLibrary.LEFT_EYE_CENTER_X,
                rightEyeCenterX = MaleSquareShapeLibrary.RIGHT_EYE_CENTER_X,
                eyeCenterY = MaleSquareShapeLibrary.EYE_CENTER_Y,
                eyeWidth = 60f,
                eyeHeight = 55f,
                scaleRatio = 1.2f,
                blushColor = Color.LightGray
            )
        }
    }

    // ========== 内部绘制方法 ==========

    /**
     * 获取当前表情形状（处理疑惑的两帧动画）
     */
    private fun getCurrentShape(
        style: ExpressionStyle,
        type: ExpressionType,
        time: Float
    ): ExpressionShape {
        val isMale = (style == ExpressionStyle.SHAPE_MALE)

        return when (type) {
            ExpressionType.CONFUSED, ExpressionType.THINKING -> {
                // 疑惑：每300ms切换一帧
                val frame = (time / 0.3f).toInt() % 2
                if (frame == 0) {
                    if (isMale) MaleSquareShapeLibrary.getConfusedFrameA()
                    else FemaleCircleShapeLibrary.getConfusedFrameA()
                } else {
                    if (isMale) MaleSquareShapeLibrary.getConfusedFrameB()
                    else FemaleCircleShapeLibrary.getConfusedFrameB()
                }
            }

            else -> {
                if (isMale) MaleSquareShapeLibrary.getShape(type)
                else FemaleCircleShapeLibrary.getShape(type)
            }
        }
    }

    /**
     * 绘制眼睛
     */
    private fun DrawScope.drawEyes(leftPath: Path, rightPath: Path, alpha: Float) {
        drawPath(
            path = leftPath,
            color = Color.White.copy(alpha = alpha)
        )
        drawPath(
            path = rightPath,
            color = Color.White.copy(alpha = alpha)
        )
    }

    /**
     * 绘制腮红
     */
    private fun DrawScope.drawBlush(
        leftPath: Path,
        rightPath: Path,
        color: Color,
        alpha: Float
    ) {
        drawPath(
            path = leftPath,
            color = color.copy(alpha = alpha * 0.6f)
        )
        drawPath(
            path = rightPath,
            color = color.copy(alpha = alpha * 0.6f)
        )
    }

    /**
     * 绘制瞳孔
     */
    private fun DrawScope.drawPupils(
        shape: ExpressionShape,
        config: StyleConfig,
        pupilOffsetX: Float,
        pupilOffsetY: Float,
        alpha: Float
    ) {
        val pupilColor = Color.Black.copy(alpha = alpha)

        // 计算实际瞳孔偏移（像素）
        // pupilOffsetX/Y 是 -1~1 的归一化值，乘以眼睛宽度/高度的30%
        // 再乘以跟随系数，与传统风格保持一致（传统风格历史值为0.8）
        val followFactor = ConfigManager.getInstance().config.value.faceDetection.pupilFollowFactor
        val actualOffsetX = pupilOffsetX * config.eyeWidth * 0.3f * followFactor
        val actualOffsetY = pupilOffsetY * config.eyeHeight * 0.3f * followFactor

        // 左眼瞳孔位置
        val leftPupilX = config.leftEyeCenterX + shape.pupilOffsetX + actualOffsetX
        val leftPupilY = config.eyeCenterY + shape.pupilOffsetY + actualOffsetY

        // 右眼瞳孔位置
        val rightPupilX = config.rightEyeCenterX + shape.pupilOffsetX + actualOffsetX
        val rightPupilY = config.eyeCenterY + shape.pupilOffsetY + actualOffsetY

        // 根据瞳孔形状类型绘制
        when (val pupilShape = shape.pupilShape) {
            is PupilShape.Circle -> {
                // 圆形瞳孔
                drawCircle(
                    color = pupilColor,
                    radius = pupilShape.radius,
                    center = Offset(leftPupilX, leftPupilY)
                )
                drawCircle(
                    color = pupilColor,
                    radius = pupilShape.radius,
                    center = Offset(rightPupilX, rightPupilY)
                )
            }

            is PupilShape.Rect -> {
                // 方形瞳孔
                drawRect(
                    color = pupilColor,
                    topLeft = Offset(
                        leftPupilX - pupilShape.width / 2,
                        leftPupilY - pupilShape.height / 2
                    ),
                    size = Size(pupilShape.width, pupilShape.height)
                )
                drawRect(
                    color = pupilColor,
                    topLeft = Offset(
                        rightPupilX - pupilShape.width / 2,
                        rightPupilY - pupilShape.height / 2
                    ),
                    size = Size(pupilShape.width, pupilShape.height)
                )
            }

            is PupilShape.PathShape -> {
                // 路径形状瞳孔（星形、三角形等）
                // 用路径自身的 bounds 中心来定位，确保各种形状都能正确居中
                val sourcePath = pupilShape.path
                val sourceBounds = sourcePath.getBounds()
                val sourceCenter = sourceBounds.center

                // 左眼瞳孔
                val leftPath = Path().apply {
                    addPath(sourcePath)
                    translate(
                        Offset(
                            x = leftPupilX - sourceCenter.x,
                            y = leftPupilY - sourceCenter.y
                        )
                    )
                }
                drawPath(path = leftPath, color = pupilColor)

                // 右眼瞳孔
                val rightPath = Path().apply {
                    addPath(sourcePath)
                    translate(
                        Offset(
                            x = rightPupilX - sourceCenter.x,
                            y = rightPupilY - sourceCenter.y
                        )
                    )
                }
                drawPath(path = rightPath, color = pupilColor)
            }
        }
    }
}
