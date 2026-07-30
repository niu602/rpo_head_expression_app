/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.face.Face
import kotlin.random.Random

/**
 * 占位特征提取器（Dummy Implementation）
 *
 * 用途：
 * - 在真实模型集成前，提供一个可运行的实现，用于测试整个识别框架流程
 * - 基于人脸bounding box位置生成"伪特征"，保证同一位置人脸特征相似
 * - 不具备真实人脸识别能力，仅用于框架联调
 *
 * 替换为真实模型时：
 * 1. 新建 MobileFaceNetExtractor 实现 FaceFeatureExtractor 接口
 * 2. 在 FaceIdentifier 中替换 DummyFeatureExtractor 为真实实现
 * 3. 将模型文件放入 assets/recognition/ 目录
 */
class DummyFeatureExtractor : FaceFeatureExtractor {

    companion object {
        private const val TAG = "DummyFeatureExtractor"
    }

    override val featureDimension: Int = FaceProfile.FEATURE_DIMENSION

    @Volatile
    private var ready = false

    // 用固定seed的Random保证同一人脸位置生成相似特征
    private val random = Random(42)

    override fun isReady(): Boolean = ready

    override fun initialize(context: Context) {
        // 占位：真实模型在这里加载onnx/tflite文件
        ready = true
        Log.i(TAG, "DummyFeatureExtractor initialized (placeholder, not real recognition)")
    }

    override fun extractFeature(
        faceBitmap: Bitmap,
        face: Face,
        srcWidth: Int,
        srcHeight: Int,
        rotation: Int,
        mirrorX: Boolean
    ): FloatArray? {
        if (!ready) return null

        // 占位实现：基于人脸bounding box生成伪特征
        // 真实模型应该在这里：
        // 1. 根据face.landmark做人脸对齐（5点关键点：双眼、鼻尖、嘴角）
        // 2. 将对齐后的人脸resize到模型输入尺寸（如112x112）
        // 3. 归一化像素值（-1~1或0~1）
        // 4. 前向推理得到embedding
        // 5. L2归一化

        val box = face.boundingBox
        // 用人脸大小的粗粒度分桶作为伪特征seed，而非精确位置坐标。
        // 这样同一人脸即使有轻微移动，只要大小相近就会产生高度相似的特征，
        // 能够通过帧间一致性检查（MIN_FRAME_SIMILARITY=0.6）。
        val centerX = box.centerX()
        val centerY = box.centerY()
        val width = box.width()
        val height = box.height()

        // 分桶：每20px一个桶，大幅降低位置微小变化带来的特征漂移
        val bucketX = (centerX / 20).toInt()
        val bucketY = (centerY / 20).toInt()
        val bucketW = (width / 20).toInt()
        val bucketH = (height / 20).toInt()

        val seed = (bucketX.toLong() * 1000000) + (bucketY.toLong() * 1000) + (bucketW * 10) + bucketH
        val featureRandom = Random(seed)

        // 生成伪特征向量：80%由分桶seed决定（同一桶高度相似），20%随机扰动
        val feature = FloatArray(featureDimension)
        for (i in feature.indices) {
            feature[i] = featureRandom.nextFloat() * 0.8f + random.nextFloat() * 0.2f
        }

        // L2归一化
        var norm = 0f
        for (v in feature) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-6f) {
            for (i in feature.indices) feature[i] /= norm
        }

        return feature
    }

    override fun release() {
        ready = false
        Log.d(TAG, "DummyFeatureExtractor released")
    }
}
