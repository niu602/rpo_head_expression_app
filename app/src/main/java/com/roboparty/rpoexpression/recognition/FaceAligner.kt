/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 人脸对齐器
 *
 * 使用ML Kit检测到的5个关键点（双眼、鼻尖、双嘴角），
 * 通过相似变换（缩放+旋转+平移）将人脸对齐到ArcFace标准模板坐标（112x112）。
 *
 * 对齐的重要性：
 * - 未对齐的人脸特征提取准确率会下降10-30%
 * - 标准模板保证所有人脸的眼睛/鼻子/嘴巴在相同位置
 * - 这是MobileFaceNet/ArcFace等模型的标准预处理步骤
 *
 * 纯Kotlin实现，不依赖OpenCV，使用Android原生Matrix做仿射变换。
 */
object FaceAligner {

    private const val TAG = "FaceAligner"

    /** 对齐后的目标尺寸（ArcFace标准） */
    const val ALIGNED_SIZE = 112

    /**
     * ArcFace标准5点模板坐标（112x112图像空间）
     *
     * 这是InsightFace/MobileFaceNet训练时使用的标准对齐模板：
     * - 双眼水平，y坐标约51
     * - 鼻尖在中间偏下(56, 72)
     * - 嘴角在下方(41, 92)和(71, 92)
     */
    private val TEMPLATE_LEFT_EYE = Pair(38.2946f, 51.6963f)
    private val TEMPLATE_RIGHT_EYE = Pair(73.5318f, 51.5014f)
    private val TEMPLATE_NOSE = Pair(56.0252f, 71.7366f)
    private val TEMPLATE_LEFT_MOUTH = Pair(41.5493f, 92.3655f)
    private val TEMPLATE_RIGHT_MOUTH = Pair(70.7299f, 92.2041f)

    /**
     * 人脸质量检查结果
     */
    data class FaceQuality(
        val isAligned: Boolean,       // 是否成功对齐（5个关键点都存在）
        val yawAngle: Float,          // 左右偏转角（度）
        val rollAngle: Float,         // 旋转角（度）
        val pitchAngle: Float,        // 俯仰角（度）
        val faceWidth: Int,           // 人脸宽度（像素）
        val faceHeight: Int,          // 人脸高度（像素）
        val isGoodQuality: Boolean    // 是否满足注册/识别质量要求
    )

    /**
     * 对齐人脸到112x112标准模板
     *
     * @param originalBitmap 原始帧Bitmap（完整摄像头帧，已处理旋转和镜像）
     * @param face ML Kit检测到的Face对象（包含关键点）
     * @param outBitmap 可选：传入已有112x112 Bitmap 复用，减少内存分配
     * @return 对齐后的112x112 ARGB_8888 Bitmap；如果关键点缺失返回null
     */
    fun alignFace(
        originalBitmap: Bitmap,
        face: Face,
        srcWidth: Int = originalBitmap.width,
        srcHeight: Int = originalBitmap.height,
        rotationDegrees: Int = 0,
        mirrorX: Boolean = false,
        outBitmap: Bitmap? = null
    ): Bitmap? {
        return try {
            // 检查Bitmap有效性
            if (originalBitmap.isRecycled || originalBitmap.width == 0 || originalBitmap.height == 0) {
                Log.w(TAG, "Invalid bitmap for alignment")
                return null
            }

            // 1. 提取5个关键点
            val leftEyeOrig = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEyeOrig = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val noseOrig = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val leftMouthOrig = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val rightMouthOrig = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

            // 关键点不全，无法对齐
            if (leftEyeOrig == null || rightEyeOrig == null || noseOrig == null ||
                leftMouthOrig == null || rightMouthOrig == null) {
                Log.w(TAG, "Missing landmarks, cannot align face")
                return null
            }

            // 坐标转换：ML Kit返回的坐标已经是旋转后自然方向的坐标，
            // 只需要处理我们自己做的水平镜像（前置摄像头）即可，不需要再做旋转！
            fun transformPoint(x: Float, y: Float): Pair<Float, Float> {
                var px = x
                var py = y
                // 前置摄像头水平镜像：x = 位图宽度 - x
                if (mirrorX) {
                    px = originalBitmap.width - px
                }
                return Pair(px, py)
            }

            val (lex, ley) = transformPoint(leftEyeOrig.x, leftEyeOrig.y)
            val (rex, rey) = transformPoint(rightEyeOrig.x, rightEyeOrig.y)
            val (nx, ny) = transformPoint(noseOrig.x, noseOrig.y)
            val (lmx, lmy) = transformPoint(leftMouthOrig.x, leftMouthOrig.y)
            val (rmx, rmy) = transformPoint(rightMouthOrig.x, rightMouthOrig.y)

            // 2. 源点（转换后，和Bitmap坐标系一致）
            // 前置镜像后x坐标翻转，点顺序自然和非镜像模板一致，不需要交换
            val srcPoints = floatArrayOf(
                lex, ley,
                rex, rey,
                nx, ny,
                lmx, lmy,
                rmx, rmy
            )

        // 3. 目标点（标准模板坐标）
        val dstPoints = floatArrayOf(
            TEMPLATE_LEFT_EYE.first, TEMPLATE_LEFT_EYE.second,
            TEMPLATE_RIGHT_EYE.first, TEMPLATE_RIGHT_EYE.second,
            TEMPLATE_NOSE.first, TEMPLATE_NOSE.second,
            TEMPLATE_LEFT_MOUTH.first, TEMPLATE_LEFT_MOUTH.second,
            TEMPLATE_RIGHT_MOUTH.first, TEMPLATE_RIGHT_MOUTH.second
        )

        // 4. 计算相似变换矩阵（5点最小二乘估计）
        val transform = estimateSimilarityTransform(srcPoints, dstPoints) ?: return null

        // 5. 创建或复用112x112输出Bitmap
        val alignedBitmap = if (outBitmap != null && !outBitmap.isRecycled &&
            outBitmap.width == ALIGNED_SIZE && outBitmap.height == ALIGNED_SIZE &&
            outBitmap.config == Bitmap.Config.ARGB_8888) {
            // 复用传入的 Bitmap，先清空
            outBitmap.eraseColor(0)
            outBitmap
        } else {
            Bitmap.createBitmap(ALIGNED_SIZE, ALIGNED_SIZE, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(alignedBitmap)
        canvas.drawBitmap(originalBitmap, transform, null)

        alignedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Face alignment failed", e)
            null
        }
    }

    /**
     * 评估人脸质量（注册时用于筛选好帧）
     *
     * @param face ML Kit Face对象
     * @param imageWidth 图像宽度
     * @param imageHeight 图像高度
     * @return 人脸质量评估结果
     */
    fun evaluateFaceQuality(face: Face, imageWidth: Int, imageHeight: Int): FaceQuality {
        val box = face.boundingBox
        val faceWidth = box.width()
        val faceHeight = box.height()

        // ML Kit提供的头部姿态角
        val yaw = face.headEulerAngleY  // 左右偏转
        val roll = face.headEulerAngleZ // 旋转（歪头）
        val pitch = face.headEulerAngleX // 俯仰（抬头低头）

        // 检查关键点完整性
        val hasAllLandmarks = face.getLandmark(FaceLandmark.LEFT_EYE) != null &&
                face.getLandmark(FaceLandmark.RIGHT_EYE) != null &&
                face.getLandmark(FaceLandmark.NOSE_BASE) != null &&
                face.getLandmark(FaceLandmark.MOUTH_LEFT) != null &&
                face.getLandmark(FaceLandmark.MOUTH_RIGHT) != null

        // 质量判断标准（参考方案文档）：
        // - 偏转角yaw < ±20°
        // - 旋转角roll < ±15°
        // - 俯仰角pitch < ±20°
        // - 人脸占屏比 > 10%（宽度）
        // - 5个关键点都存在
        val faceRatio = faceWidth.toFloat() / imageWidth.toFloat()
        val isGood = hasAllLandmarks &&
                abs(yaw) < 20f &&
                abs(roll) < 15f &&
                abs(pitch) < 20f &&
                faceRatio > 0.10f

        return FaceQuality(
            isAligned = hasAllLandmarks,
            yawAngle = yaw,
            rollAngle = roll,
            pitchAngle = pitch,
            faceWidth = faceWidth,
            faceHeight = faceHeight,
            isGoodQuality = isGood
        )
    }

    /**
     * 估计相似变换矩阵（Similarity Transform）
     *
     * 相似变换包含：缩放(s)、旋转(θ)、平移(tx, ty)，共4个自由度。
     * 使用最小二乘法从N对点估计最优变换。
     *
     * 数学模型：
     *   [x']   [s*cosθ  -s*sinθ  tx] [x]
     *   [y'] = [s*sinθ   s*cosθ  ty] [y]
     *   [1 ]   [0        0       1 ] [1]
     *
     * 令 a = s*cosθ, b = s*sinθ，则：
     *   x' = a*x - b*y + tx
     *   y' = b*x + a*y + ty
     *
     * 通过最小二乘法求解a, b, tx, ty四个参数。
     *
     * @param src 源点坐标 [x0,y0, x1,y1, ...]
     * @param dst 目标点坐标 [x0',y0', x1',y1', ...]
     * @return Android Matrix对象，可直接用于Canvas.drawBitmap
     */
    private fun estimateSimilarityTransform(src: FloatArray, dst: FloatArray): Matrix? {
        val n = src.size / 2
        if (n < 2) return null

        // 计算最小二乘解
        // 构建方程组求解 a, b, tx, ty
        var sumSrcX2 = 0f
        var sumSrcY2 = 0f
        var sumSrcX = 0f
        var sumSrcY = 0f
        var sumSrcXY = 0f
        var sumDstX = 0f
        var sumDstY = 0f
        var sumSrcXDstX = 0f
        var sumSrcYDstX = 0f
        var sumSrcXDstY = 0f
        var sumSrcYDstY = 0f

        for (i in 0 until n) {
            val sx = src[i * 2]
            val sy = src[i * 2 + 1]
            val dx = dst[i * 2]
            val dy = dst[i * 2 + 1]

            sumSrcX2 += sx * sx
            sumSrcY2 += sy * sy
            sumSrcX += sx
            sumSrcY += sy
            sumSrcXY += sx * sy
            sumDstX += dx
            sumDstY += dy
            sumSrcXDstX += sx * dx
            sumSrcYDstX += sy * dx
            sumSrcXDstY += sx * dy
            sumSrcYDstY += sy * dy
        }

        // 构建4x4正规方程组并求解（使用Cramer法则或直接求逆）
        // 简化：使用UMEYAMA算法的2D版本（经典点集配准算法）
        val meanSrcX = sumSrcX / n
        val meanSrcY = sumSrcY / n
        val meanDstX = sumDstX / n
        val meanDstY = sumDstY / n

        // 计算协方差矩阵相关项
        var sigmaSrc2 = 0f
        var covXY = 0f
        var covXX = 0f
        var covYY = 0f
        var covYX = 0f

        for (i in 0 until n) {
            val sx = src[i * 2] - meanSrcX
            val sy = src[i * 2 + 1] - meanSrcY
            val dx = dst[i * 2] - meanDstX
            val dy = dst[i * 2 + 1] - meanDstY

            sigmaSrc2 += sx * sx + sy * sy
            covXX += sx * dx
            covXY += sx * dy
            covYX += sy * dx
            covYY += sy * dy
        }
        sigmaSrc2 /= n

        // 构建2x2协方差矩阵 [[covXX, covXY], [covYX, covYY]] / n
        val sxx = covXX / n
        val sxy = covXY / n
        val syx = covYX / n
        val syy = covYY / n

        // 计算旋转角度和缩放

        // a = s*cosθ, b = s*sinθ
        val a = (sxx + syy) / sigmaSrc2
        val b = (syx - sxy) / sigmaSrc2

        // 缩放因子
        val scale = sqrt(a * a + b * b)
        val cosTheta = a / scale
        val sinTheta = b / scale

        // 平移量
        val tx = meanDstX - scale * (cosTheta * meanSrcX - sinTheta * meanSrcY)
        val ty = meanDstY - scale * (sinTheta * meanSrcX + cosTheta * meanSrcY)

        // 构建Android Matrix
        // Matrix.setValues使用以下列优先格式：
        // [MSCALE_X, MSKEW_X,  MTRANS_X]
        // [MSKEW_Y,  MSCALE_Y, MTRANS_Y]
        // [MPERSP_0, MPERSP_1, MPERSP_2]
        val matrix = Matrix()
        val values = floatArrayOf(
            scale * cosTheta, -scale * sinTheta, tx,
            scale * sinTheta, scale * cosTheta, ty,
            0f, 0f, 1f
        )
        matrix.setValues(values)

        return matrix
    }

    /**
     * 简单对齐（当关键点不全时的降级方案）
     * 仅根据双眼中心做旋转+缩放对齐，保证眼睛水平
     *
     * @param outBitmap 可选：传入已有112x112 Bitmap 复用，减少内存分配
     */
    fun alignFaceByEyes(
        originalBitmap: Bitmap,
        face: Face,
        srcWidth: Int = originalBitmap.width,
        srcHeight: Int = originalBitmap.height,
        rotationDegrees: Int = 0,
        mirrorX: Boolean = false,
        outBitmap: Bitmap? = null
    ): Bitmap? {
        return try {
            if (originalBitmap.isRecycled || originalBitmap.width == 0 || originalBitmap.height == 0) {
                return null
            }

            val leftEyeOrig = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
            val rightEyeOrig = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null

            // 坐标转换：ML Kit返回的坐标已经是旋转后自然方向的坐标，
            // 只需要处理我们自己做的水平镜像（前置摄像头）即可，不需要再做旋转！
            fun transformPoint(x: Float, y: Float): Pair<Float, Float> {
                var px = x
                var py = y
                // 前置摄像头水平镜像：x = 位图宽度 - x
                if (mirrorX) {
                    px = originalBitmap.width - px
                }
                return Pair(px, py)
            }

            val (lex, ley) = transformPoint(leftEyeOrig.x, leftEyeOrig.y)
            val (rex, rey) = transformPoint(rightEyeOrig.x, rightEyeOrig.y)

            // 计算双眼中心和角度（镜像后x翻转，右眼在右左眼在左，dx为正，角度正确）
            val dx = rex - lex
            val dy = rey - ley
            val eyeDistance = sqrt(dx * dx + dy * dy)
            if (eyeDistance < 1f) return null // 距离太小，异常数据
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

            // 目标：双眼距离约35像素
            val targetEyeDistance = 35f
            val scale = targetEyeDistance / eyeDistance

            // 双眼中心
            val eyeCenterX = (lex + rex) / 2f
            val eyeCenterY = (ley + rey) / 2f

            val matrix = Matrix()
            // 先平移到原点
            matrix.postTranslate(-eyeCenterX, -eyeCenterY)
            // 旋转使双眼水平
            matrix.postRotate(-angle)
            // 缩放
            matrix.postScale(scale, scale)
            // 平移到目标位置（眼睛在y=51处，中心x=56）
            matrix.postTranslate(ALIGNED_SIZE / 2f, 51.7f)

            // 创建或复用输出Bitmap
            val alignedBitmap = if (outBitmap != null && !outBitmap.isRecycled &&
                outBitmap.width == ALIGNED_SIZE && outBitmap.height == ALIGNED_SIZE &&
                outBitmap.config == Bitmap.Config.ARGB_8888) {
                outBitmap.eraseColor(0)
                outBitmap
            } else {
                Bitmap.createBitmap(ALIGNED_SIZE, ALIGNED_SIZE, Bitmap.Config.ARGB_8888)
            }
            val canvas = Canvas(alignedBitmap)
            canvas.drawBitmap(originalBitmap, matrix, null)

            alignedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Align face by eyes failed", e)
            null
        }
    }
}


