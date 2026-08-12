package com.roboparty.rpoexpression.animation.shape

import androidx.compose.ui.graphics.Path

/**
 * 形状表情公共数据类型
 * 男性方形和女性圆形风格共用
 */

// 表情形状数据类
data class ExpressionShape(
    val leftEyePath: Path,
    val rightEyePath: Path,
    val pupilShape: PupilShape,
    val pupilOffsetX: Float = 0f,
    val pupilOffsetY: Float = 0f,
    val hasBlush: Boolean = false,
    val leftBlushPath: Path? = null,
    val rightBlushPath: Path? = null
)

// 瞳孔形状类型（密封类）
sealed class PupilShape {
    data class Circle(val radius: Float) : PupilShape()
    data class Rect(val width: Float, val height: Float) : PupilShape()
    data class PathShape(val path: Path) : PupilShape()
}
