package com.roboparty.rpoexpression.animation.shape

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import com.roboparty.rpoexpression.expression.ExpressionType
import androidx.core.graphics.PathParser
import androidx.compose.ui.graphics.asComposePath
/**
 * 女性圆形表情形状库
 * 完全独立于参数化眼睛模型，存储所有女性圆形表情的SVG路径数据
 */
object FemaleCircleShapeLibrary {

    // 原始SVG尺寸常量
    const val SVG_WIDTH = 320f
    const val SVG_HEIGHT = 189f
    const val LEFT_EYE_CENTER_X = 76f
    const val RIGHT_EYE_CENTER_X = 244f
    const val EYE_CENTER_Y = 92.5f

    // 形状缓存
    private val shapeCache = mutableMapOf<ExpressionType, ExpressionShape>()

    /**
     * 获取指定表情的形状数据
     */
    fun getShape(type: ExpressionType): ExpressionShape {
        return shapeCache.getOrPut(type) {
            createShape(type)
        }
    }

    /**
     * 获取疑惑表情的A帧
     */
    fun getConfusedFrameA(): ExpressionShape {
        return shapeCache.getOrPut(ExpressionType.CONFUSED) {
            createConfusedShapeA()
        }
    }

    /**
     * 获取疑惑表情的B帧
     */
    fun getConfusedFrameB(): ExpressionShape {
        return shapeCache.getOrPut(ExpressionType.THINKING) {
            createConfusedShapeB()
        }
    }

    // ========== 形状创建方法 ==========

    private fun createShape(type: ExpressionType): ExpressionShape {
        return when (type) {
            ExpressionType.IDLE -> createIdleShape()
            ExpressionType.HAPPY -> createHappyShape()
            ExpressionType.SAD -> createSadShape()
            ExpressionType.SHY -> createShyShape()
            ExpressionType.ANGRY -> createAngryShape()
            ExpressionType.CONFUSED, ExpressionType.THINKING -> createConfusedShapeA()
            else -> createIdleShape() // 默认用待机形状
        }
    }

    /**
     * 创建待机表情
     */
    private fun createIdleShape(): ExpressionShape {
        val leftEyePath = PathParser.createPathFromPathData(
            "M109 92.5C109 110.784 94.2843 125.5 76 125.5C57.7157 125.5 43 110.784 43 92.5C43 74.2157 57.7157 59.5 76 59.5C94.2843 59.5 109 74.2157 109 92.5Z"
        ).asComposePath()

        val rightEyePath = PathParser.createPathFromPathData(
            "M277 92.5C277 110.784 262.284 125.5 244 125.5C225.716 125.5 211 110.784 211 92.5C211 74.2157 225.716 59.5 244 59.5C262.284 59.5 277 74.2157 277 92.5Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEyePath,
            rightEyePath = rightEyePath,
            pupilShape = PupilShape.Circle(radius = 13f),
            pupilOffsetX = 0f,
            pupilOffsetY = 0f
        )
    }

    /**
     * 创建开心表情
     */
    private fun createHappyShape(): ExpressionShape {
        val leftEyePath = PathParser.createPathFromPathData(
            "M43 92.5C43 74.2157 57.7157 59.5 76 59.5C94.2843 59.5 109 74.2157 109 92.5C109 96.4281 105.784 101.768 100.428 105.479C92.7189 110.806 82.9194 114.6 76 114.6C69.0806 114.6 59.2811 110.806 51.5717 105.479C46.2162 101.768 43 96.4281 43 92.5Z"
        ).asComposePath()

        val rightEyePath = PathParser.createPathFromPathData(
            "M211 92.5C211 74.2157 225.716 59.5 244 59.5C262.284 59.5 277 74.2157 277 92.5C277 96.4281 273.784 101.768 268.428 105.479C260.719 110.806 250.919 114.6 244 114.6C237.081 114.6 227.281 110.806 219.572 105.479C214.216 101.768 211 96.4281 211 92.5Z"
        ).asComposePath()

        val pupilPath = PathParser.createPathFromPathData(
            "M76 70.1L79.2678 79.9322L89.5 79.9322L81.1161 85.9356L84.3839 95.7678L76 89.7644L67.6161 95.7678L70.8839 85.9356L62.5 79.9322L72.7322 79.9322L76 70.1Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEyePath,
            rightEyePath = rightEyePath,
            pupilShape = PupilShape.PathShape(path = pupilPath),
            pupilOffsetX = 0f,
            pupilOffsetY = 0f
        )
    }

    /**
     * 创建悲伤表情
     * 注意：使用evenodd填充，外圆环为眼白（白色），中间菱形为瞳孔（镂空显示背景黑色）
     */
    private fun createSadShape(): ExpressionShape {
        val leftEyePath = PathParser.createPathFromPathData(
            "M43 92.5C43 74.2157 57.7157 59.5 76 59.5C94.2843 59.5 109 74.2157 109 92.5C109 110.784 94.2843 125.5 76 125.5C57.7157 125.5 43 110.784 43 92.5ZM76 67.8L64.8427 78.9573L53.6854 67.8L42.5281 78.9573L53.6854 90.1146L42.5281 101.272L53.6854 112.429L64.8427 101.272L76 112.429L87.1573 101.272L98.3146 112.429L109.472 101.272L98.3146 90.1146L109.472 78.9573L98.3146 67.8L87.1573 78.9573L76 67.8Z"
        ).asComposePath().apply {
            fillType = PathFillType.EvenOdd
        }

        val rightEyePath = PathParser.createPathFromPathData(
            "M211 92.5C211 74.2157 225.716 59.5 244 59.5C262.284 59.5 277 74.2157 277 92.5C277 110.784 262.284 125.5 244 125.5C225.716 125.5 211 110.784 211 92.5ZM244 67.8L232.843 78.9573L221.685 67.8L210.528 78.9573L221.685 90.1146L210.528 101.272L221.685 112.429L232.843 101.272L244 112.429L255.157 101.272L266.315 112.429L277.472 101.272L266.315 90.1146L277.472 78.9573L266.315 67.8L255.157 78.9573L244 67.8Z"
        ).asComposePath().apply {
            fillType = PathFillType.EvenOdd
        }

        return ExpressionShape(
            leftEyePath = leftEyePath,
            rightEyePath = rightEyePath,
            pupilShape = PupilShape.Circle(radius = 0f), // 瞳孔已镂空在眼睛路径里
            pupilOffsetX = 0f,
            pupilOffsetY = 0f
        )
    }

    /**
     * 创建害羞表情
     */
    private fun createShyShape(): ExpressionShape {
        val leftEyePath = PathParser.createPathFromPathData(
            "M109 92.5C109 110.784 94.2843 125.5 76 125.5C57.7157 125.5 43 110.784 43 92.5C43 74.2157 57.7157 59.5 76 59.5C94.2843 59.5 109 74.2157 109 92.5Z"
        ).asComposePath()

        val rightEyePath = PathParser.createPathFromPathData(
            "M277 92.5C277 110.784 262.284 125.5 244 125.5C225.716 125.5 211 110.784 211 92.5C211 74.2157 225.716 59.5 244 59.5C262.284 59.5 277 74.2157 277 92.5Z"
        ).asComposePath()

        val pupilPath = PathParser.createPathFromPathData(
            "M61 95.5L76 110.5L91 95.5"
        ).asComposePath()

        // 腮红路径（圆形）
        val leftBlushPath = PathParser.createPathFromPathData(
            "M34.5 110C34.5 102.82 40.3203 97 47.5 97C54.6797 97 60.5 102.82 60.5 110C60.5 117.18 54.6797 123 47.5 123C40.3203 123 34.5 117.18 34.5 110Z"
        ).asComposePath()

        val rightBlushPath = PathParser.createPathFromPathData(
            "M259.5 110C259.5 102.82 265.32 97 272.5 97C279.68 97 285.5 102.82 285.5 110C285.5 117.18 279.68 123 272.5 123C265.32 123 259.5 117.18 259.5 110Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEyePath,
            rightEyePath = rightEyePath,
            pupilShape = PupilShape.PathShape(path = pupilPath),
            pupilOffsetX = 0f,
            pupilOffsetY = 0f,
            hasBlush = true,
            leftBlushPath = leftBlushPath,
            rightBlushPath = rightBlushPath
        )
    }

    /**
     * 创建生气表情
     */
    private fun createAngryShape(): ExpressionShape {
        val leftEyePath = PathParser.createPathFromPathData(
            "M109 94.5C109 112.784 94.2843 127.5 76 127.5C57.7157 127.5 43 112.784 43 94.5C43 76.2157 57.7157 61.5 76 61.5C94.2843 61.5 109 76.2157 109 94.5Z"
        ).asComposePath()

        val rightEyePath = PathParser.createPathFromPathData(
            "M277 94.5C277 112.784 262.284 127.5 244 127.5C225.716 127.5 211 112.784 211 94.5C211 76.2157 225.716 61.5 244 61.5C262.284 61.5 277 76.2157 277 94.5Z"
        ).asComposePath()

        val pupilPath = PathParser.createPathFromPathData(
            "M76 69L95.8579 106.5H56.1421L76 69Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEyePath,
            rightEyePath = rightEyePath,
            pupilShape = PupilShape.PathShape(path = pupilPath),
            pupilOffsetX = 0f,
            pupilOffsetY = 0f
        )
    }

    /**
     * 创建疑惑表情A帧
     */
    private fun createConfusedShapeA(): ExpressionShape {
        val leftEyePath = PathParser.createPathFromPathData(
            "M109 92.5C109 110.784 94.2843 125.5 76 125.5C57.7157 125.5 43 110.784 43 92.5C43 74.2157 57.7157 59.5 76 59.5C94.2843 59.5 109 74.2157 109 92.5Z"
        ).asComposePath()

        val rightEyePath = PathParser.createPathFromPathData(
            "M277 92.5C277 110.784 262.284 125.5 244 125.5C225.716 125.5 211 110.784 211 92.5C211 74.2157 225.716 59.5 244 59.5C262.284 59.5 277 74.2157 277 92.5Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEyePath,
            rightEyePath = rightEyePath,
            pupilShape = PupilShape.Rect(width = 18f, height = 18f),
            pupilOffsetX = -9f, // 左瞳孔偏左
            pupilOffsetY = -7f  // 左瞳孔偏上
        )
    }

    /**
     * 创建疑惑表情B帧
     */
    private fun createConfusedShapeB(): ExpressionShape {
        val leftEyePath = PathParser.createPathFromPathData(
            "M109 92.5C109 110.784 94.2843 125.5 76 125.5C57.7157 125.5 43 110.784 43 92.5C43 74.2157 57.7157 59.5 76 59.5C94.2843 59.5 109 74.2157 109 92.5Z"
        ).asComposePath()

        val rightEyePath = PathParser.createPathFromPathData(
            "M277 92.5C277 110.784 262.284 125.5 244 125.5C225.716 125.5 211 110.784 211 92.5C211 74.2157 225.716 59.5 244 59.5C262.284 59.5 277 74.2157 277 92.5Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEyePath,
            rightEyePath = rightEyePath,
            pupilShape = PupilShape.Rect(width = 18f, height = 18f),
            pupilOffsetX = 9f,  // 左瞳孔偏右
            pupilOffsetY = 7f   // 左瞳孔偏下
        )
    }
}
