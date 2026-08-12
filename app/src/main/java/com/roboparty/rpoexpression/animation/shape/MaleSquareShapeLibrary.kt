package com.roboparty.rpoexpression.animation.shape

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.core.graphics.PathParser
import com.roboparty.rpoexpression.expression.ExpressionType

/**
 * 男性方形表情形状库
 * 存储从Figma导出的SVG路径数据，完全独立于参数化眼睛模型
 *
 * SVG原始坐标系：320 × 189
 * 左眼中心：(103, 92.5)
 * 右眼中心：(217, 92.5)
 */
object MaleSquareShapeLibrary {

    // ========== 原始SVG尺寸常量 ==========
    const val SVG_WIDTH = 320f
    const val SVG_HEIGHT = 189f
    const val LEFT_EYE_CENTER_X = 103f
    const val RIGHT_EYE_CENTER_X = 217f
    const val EYE_CENTER_Y = 92.5f

    // ========== 表情形状缓存 ==========
    private val shapeCache = mutableMapOf<ExpressionType, ExpressionShape>()

    /**
     * 获取指定表情的形状数据
     */
    fun getShape(type: ExpressionType): ExpressionShape {
        return shapeCache.getOrPut(type) {
            when (type) {
                ExpressionType.IDLE -> createIdleShape()
                ExpressionType.HAPPY -> createHappyShape()
                ExpressionType.SAD -> createSadShape()
                ExpressionType.SHY -> createShyShape()
                ExpressionType.ANGRY -> createAngryShape()
                ExpressionType.CONFUSED -> createConfusedShape()
                ExpressionType.THINKING -> createConfusedShape() // 思考复用疑惑
                else -> createIdleShape() // 默认用待机
            }
        }
    }

    /**
     * 获取疑惑表情的两帧形状（A帧和B帧）
     */
    fun getConfusedFrameA(): ExpressionShape = createConfusedShape()
    fun getConfusedFrameB(): ExpressionShape = createConfusedShapeB()

    // ========== 各表情形状创建 ==========

    private fun createIdleShape(): ExpressionShape {
        // 待机：圆角矩形眼睛 + 圆形瞳孔，瞳孔偏下
        val leftEye = PathParser.createPathFromPathData(
            "M133 120C133 122.761 130.761 125 128 125H78C75.2386 125 73 122.761 73 120V70C73 67.2386 75.2386 65 78 65H128C130.761 65 133 67.2386 133 70V120Z"
        ).asComposePath()

        val rightEye = PathParser.createPathFromPathData(
            "M247 120C247 122.761 244.761 125 242 125H192C189.239 125 187 122.761 187 120V70C187 67.2386 189.239 65 192 65H242C244.761 65 247 67.2386 247 70V120Z"
        ).asComposePath()

        // 瞳孔：圆形，半径9，默认在眼白中心
        return ExpressionShape(
            leftEyePath = leftEye,
            rightEyePath = rightEye,
            pupilShape = PupilShape.Circle(9f),
            pupilOffsetX = 0f,
            pupilOffsetY = 0f
        )
    }

    private fun createHappyShape(): ExpressionShape {
        // 开心：底部弧形眼睛 + 星形瞳孔，瞳孔偏上
        val leftEye = PathParser.createPathFromPathData(
            "M71 70C71 67.2386 73.2386 65 76 65H128C130.761 65 133 67.2386 133 70V117.597C133 121.603 128.451 124.101 124.924 122.202C118.445 118.714 109.265 114.6 102 114.6C94.7354 114.6 85.5553 118.714 79.0762 122.202C75.5494 124.101 71 121.603 71 117.597V70Z"
        ).asComposePath()

        val rightEye = PathParser.createPathFromPathData(
            "M187 70C187 67.2386 189.239 65 192 65H244C246.761 65 249 67.2386 249 70V117.597C249 121.603 244.451 124.101 240.924 122.202C234.445 118.714 225.265 114.6 218 114.6C210.735 114.6 201.555 118.714 195.076 122.202C191.549 124.101 187 121.603 187 117.597V70Z"
        ).asComposePath()

        // 瞳孔：四角星形，中心约(102, 89.5)和(218, 89.5)
        // 相对眼睛中心：偏移(-1, -3) → 偏上一点
        val starPath = PathParser.createPathFromPathData(
            "M101.203 80.5504C101.603 80.0228 102.397 80.0228 102.797 80.5504L106.23 85.0772C106.285 85.1498 106.35 85.2146 106.423 85.2696L110.95 88.7033C111.477 89.1034 111.477 89.8966 110.95 90.2967L106.423 93.7304C106.35 93.7854 106.285 93.8502 106.23 93.9228L102.797 98.4496C102.397 98.9772 101.603 98.9772 101.203 98.4496L97.7696 93.9228C97.7146 93.8502 97.6498 93.7854 97.5772 93.7304L93.0504 90.2967C92.5228 89.8966 92.5228 89.1034 93.0504 88.7033L97.5772 85.2696C97.6498 85.2146 97.7146 85.1498 97.7696 85.0772L101.203 80.5504Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEye,
            rightEyePath = rightEye,
            pupilShape = PupilShape.PathShape(starPath),
            pupilOffsetX = -1f,
            pupilOffsetY = -3f
        )
    }

    private fun createSadShape(): ExpressionShape {
        // 悲伤：八字眉眼睛 + 水滴形瞳孔，瞳孔偏下
        val leftEye = PathParser.createPathFromPathData(
            "M125.912 68.4627C129.224 66.9404 133 69.3603 133 73.0056V120.205C133 120.384 132.99 120.562 132.972 120.736C132.99 120.89 133 121.046 133 121.205C133 123.414 131.209 125.205 129 125.205H56C53.791 125.205 52.0002 123.414 52 121.205C52 118.996 53.7909 117.205 56 117.205H73V95.99C73 94.037 74.1376 92.2628 75.9121 91.4471L125.912 68.4627Z"
        ).asComposePath()

        val rightEye = PathParser.createPathFromPathData(
            "M194.088 68.4627C190.776 66.9404 187 69.3603 187 73.0056V120.205C187 120.384 187.01 120.562 187.028 120.736C187.01 120.89 187 121.046 187 121.205C187 123.414 188.791 125.205 191 125.205H264C266.209 125.205 268 123.414 268 121.205C268 118.996 266.209 117.205 264 117.205H247V95.99C247 94.037 245.862 92.2628 244.088 91.4471L194.088 68.4627Z"
        ).asComposePath()

        // 瞳孔：水滴/椭圆形
        // 左瞳孔中心约(123.5, 117.8)，右瞳孔中心约(196.5, 117.8)
        // 相对眼睛中心：偏移(+20.5, +25.3)和(-20.5, +25.3) → 偏下偏外
        val pupilPath = PathParser.createPathFromPathData(
            "M204.045 121.109C201.437 125.34 195.892 126.655 191.661 124.046C187.43 121.437 186.115 115.893 188.723 111.662C191.332 107.431 195.056 111.435 199.287 114.044C203.518 116.652 206.654 116.878 204.045 121.109Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEye,
            rightEyePath = rightEye,
            pupilShape = PupilShape.PathShape(pupilPath),
            pupilOffsetX = 20.5f,
            pupilOffsetY = 25.3f
        )
    }

    private fun createShyShape(): ExpressionShape {
        // 害羞：闭着笑的眼睛 + 倒V形瞳孔 + 腮红
        val leftEye = PathParser.createPathFromPathData(
            "M73 70.2531C73 66.2476 77.5494 63.7488 81.0762 65.6476C87.5553 69.1362 96.7354 73.2501 104 73.2501C111.265 73.2502 120.445 69.1362 126.924 65.6476C130.451 63.7488 135 66.2476 135 70.2531V117.85C135 120.611 132.761 122.85 130 122.85H78C75.2386 122.85 73 120.611 73 117.85V70.2531Z"
        ).asComposePath()

        val rightEye = PathParser.createPathFromPathData(
            "M247 71.3537C247 67.3482 242.451 64.8494 238.924 66.7482C232.445 70.2368 223.265 74.3508 216 74.3508C208.735 74.3508 199.555 70.2368 193.076 66.7482C189.549 64.8494 185 67.3482 185 71.3537V118.95C185 121.712 187.239 123.95 190 123.95H242C244.761 123.95 247 121.712 247 118.95V71.3537Z"
        ).asComposePath()

        // 腮红：左右各一个圆角矩形
        val leftBlush = PathParser.createPathFromPathData(
            "M36 109.629C38.7614 109.629 41 111.868 41 114.629V128.629C40.9998 131.39 38.7613 133.629 36 133.629H22C19.2387 133.629 17.0002 131.39 17 128.629V114.629C17 111.868 19.2386 109.629 22 109.629H36Z"
        ).asComposePath()

        val rightBlush = PathParser.createPathFromPathData(
            "M284 108.528C281.239 108.528 279 110.767 279 113.528V127.528C279 130.29 281.239 132.528 284 132.528H298C300.761 132.528 303 130.29 303 127.528V113.528C303 110.767 300.761 108.528 298 108.528H284Z"
        ).asComposePath()

        // 瞳孔：倒V形（两条线组成的闭眼形状）
        // 左瞳孔中心约(103, 116)，右瞳孔中心约(217, 116)
        // 相对眼睛中心：偏移(0, +23.5) → 偏下
        val pupilPath = PathParser.createPathFromPathData(
            "M213 113.803L217.95 118.753L223.253 113.45"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEye,
            rightEyePath = rightEye,
            pupilShape = PupilShape.PathShape(pupilPath),
            pupilOffsetX = 0f,
            pupilOffsetY = 23.5f,
            hasBlush = true,
            leftBlushPath = leftBlush,
            rightBlushPath = rightBlush
        )
    }

    private fun createAngryShape(): ExpressionShape {
        // 生气：吊梢眼 + 三角形瞳孔，瞳孔偏上
        val leftEye = PathParser.createPathFromPathData(
            "M60 71.0891C60 67.6331 63.4224 65.2192 66.678 66.379L136.678 91.3165C138.67 92.0261 140 93.912 140 96.0266V121C140 123.761 137.761 126 135 126H65C62.2386 126 60 123.761 60 121V71.0891Z"
        ).asComposePath()

        val rightEye = PathParser.createPathFromPathData(
            "M260 71.0891C260 67.6331 256.578 65.2192 253.322 66.379L183.322 91.3165C181.33 92.0261 180 93.912 180 96.0266V121C180 123.761 182.239 126 185 126H255C257.761 126 260 123.761 260 121V71.0891Z"
        ).asComposePath()

        // 瞳孔：三角形
        // 左瞳孔中心约(99, 82)，右瞳孔中心约(221, 82)
        // 相对眼睛中心：偏移(-4, -10.5)和(+4, -10.5) → 偏上偏内
        val pupilPath = PathParser.createPathFromPathData(
            "M97.2966 88.9999C96.3573 89.3929 95.2727 89.0174 94.7774 88.1279L89.436 78.5356C88.5427 76.9313 90.1024 75.0719 91.8376 75.6726L107.308 81.0277C109.043 81.6284 109.119 84.054 107.425 84.7627L97.2966 88.9999Z"
        ).asComposePath()

        return ExpressionShape(
            leftEyePath = leftEye,
            rightEyePath = rightEye,
            pupilShape = PupilShape.PathShape(pupilPath),
            pupilOffsetX = -4f,
            pupilOffsetY = -10.5f
        )
    }

    private fun createConfusedShape(): ExpressionShape {
        // 疑惑A帧：左眼左下凸起 + 右眼左上凸起 + 方形瞳孔
        val leftEye = PathParser.createPathFromPathData(
            "M130.864 92.5922C134.06 91.6749 137.244 94.0735 137.244 97.3979V121.76C137.244 124.522 135.006 126.76 132.244 126.76H77.2444C74.4829 126.76 72.2444 124.522 72.2444 121.76V120.335L63.9807 122.666C61.1724 123.458 58.5247 121.764 58.0676 118.882C57.6108 116.001 59.5173 113.023 62.3254 112.231L75.4182 108.538C75.5633 108.482 75.7124 108.431 75.8645 108.387L130.864 92.5922Z"
        ).asComposePath()

        val rightEye = PathParser.createPathFromPathData(
            "M256.656 63.1943C259.464 62.4024 262.112 64.0967 262.569 66.9785C263.026 69.8602 261.119 72.8379 258.311 73.6298L248.244 76.4677V121.761C248.244 124.522 246.006 126.761 243.244 126.761H188.244C185.483 126.761 183.244 124.522 183.244 121.761V87.7734C183.244 85.5318 184.736 83.564 186.895 82.9589L188.398 82.5371C188.695 82.3914 189.006 82.2708 189.329 82.1796L256.656 63.1943Z"
        ).asComposePath()

        // 瞳孔：方形，15×15
        // 左瞳孔(116, 111.5)，右瞳孔(234, 102.5)
        // 相对眼睛中心：偏移(+13, +19)和(+17, +10) → 左低右高
        return ExpressionShape(
            leftEyePath = leftEye,
            rightEyePath = rightEye,
            pupilShape = PupilShape.Rect(15f, 15f),
            pupilOffsetX = 13f,
            pupilOffsetY = 19f
        )
    }

    private fun createConfusedShapeB(): ExpressionShape {
        // 疑惑B帧：右眼左下凸起 + 左眼左上凸起 + 方形瞳孔（与A帧对称）
        val leftEye = PathParser.createPathFromPathData(
            "M63.9807 63.1953C61.1725 62.4034 58.5249 64.0969 58.0676 66.9785C57.6105 69.8603 59.5172 72.839 62.3254 73.6309L72.3918 76.4688V121.761C72.3918 124.522 74.6304 126.761 77.3918 126.761H132.392C135.153 126.761 137.392 124.522 137.392 121.761V87.7734C137.392 85.5319 135.9 83.564 133.741 82.959L132.235 82.5361C131.939 82.3913 131.629 82.2705 131.307 82.1797L63.9807 63.1953Z"
        ).asComposePath()

        val rightEye = PathParser.createPathFromPathData(
            "M189.772 92.5932C186.576 91.6759 183.392 94.0744 183.392 97.3988V121.761C183.392 124.522 185.631 126.761 188.392 126.761H243.392C246.153 126.761 248.392 124.523 248.392 121.761V120.336L256.656 122.667C259.464 123.459 262.112 121.765 262.569 118.883C263.025 116.002 261.119 113.024 258.311 112.232L245.218 108.539C245.073 108.483 244.924 108.432 244.772 108.388L189.772 92.5932Z"
        ).asComposePath()

        // 瞳孔：方形，15×15
        // 左瞳孔(72, 104.5)，右瞳孔(183, 107.5)
        // 相对眼睛中心：偏移(-31, +12)和(-34, +15) → 与A帧相反
        return ExpressionShape(
            leftEyePath = leftEye,
            rightEyePath = rightEye,
            pupilShape = PupilShape.Rect(15f, 15f),
            pupilOffsetX = -31f,
            pupilOffsetY = 12f
        )
    }
}