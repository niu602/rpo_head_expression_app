/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face

/**
 * 人脸特征提取器接口
 *
 * 设计为模型无关的抽象接口，具体实现可替换：
 * - 当前：占位实现（返回随机/零向量，用于测试框架流程）
 * - 后续：MobileFaceNet / ArcFace / InsightFace 等任意模型
 *
 * 接口契约：
 * 1. 输入：对齐后的人脸Bitmap + ML Kit Face检测结果（含关键点）
 * 2. 输出：固定维度的归一化特征向量（L2归一化，余弦相似度计算）
 * 3. 线程安全：可在后台线程并发调用
 *
 * 相似度计算约定：
 * - 使用余弦相似度：dot(a, b) / (||a|| * ||b||)
 * - 特征向量已做L2归一化时，直接点积即可
 * - 阈值建议：>0.6 认为是同一人，<0.4 认为是陌生人，中间为不确定
 */
interface FaceFeatureExtractor {

    /**
     * 特征向量维度
     */
    val featureDimension: Int

    /**
     * 模型是否已加载就绪
     */
    fun isReady(): Boolean

    /**
     * 初始化/加载模型（在后台线程调用）
     * @param context Android Context，用于加载assets模型文件
     */
    fun initialize(context: android.content.Context)

    /**
     * 从人脸图像提取特征向量
     *
     * @param faceBitmap 裁剪并对齐后的人脸Bitmap（建议112x112或160x160）
     * @param face ML Kit检测到的Face对象（含landmark关键点，用于对齐）
     * @return 归一化后的特征向量，维度=featureDimension；提取失败返回null
     */
    fun extractFeature(
        faceBitmap: Bitmap,
        face: Face,
        srcWidth: Int = faceBitmap.width,
        srcHeight: Int = faceBitmap.height,
        rotation: Int = 0,
        mirrorX: Boolean = false
    ): FloatArray?

    /**
     * 计算两个特征向量的余弦相似度
     *
     * @param feature1 特征向量1（已归一化）
     * @param feature2 特征向量2（已归一化）
     * @return 相似度 [-1, 1]，1=完全相同，0=无关，-1=完全相反
     */
    fun cosineSimilarity(feature1: FloatArray, feature2: FloatArray): Float {
        if (feature1.size != feature2.size) return -1f
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in feature1.indices) {
            dot += feature1[i] * feature2[i]
            norm1 += feature1[i] * feature1[i]
            norm2 += feature2[i] * feature2[i]
        }
        val denom = kotlin.math.sqrt(norm1 * norm2)
        return if (denom > 1e-6f) dot / denom else 0f
    }

    /**
     * 多帧特征融合（取平均后归一化）
     * 注册时采集N帧，取平均特征提高鲁棒性
     *
     * @param features 多帧特征向量列表
     * @return 融合后的特征向量
     */
    fun averageFeatures(features: List<FloatArray>): FloatArray? {
        if (features.isEmpty()) return null
        val dim = features[0].size
        if (features.any { it.size != dim }) return null

        val avg = FloatArray(dim)
        for (f in features) {
            for (i in 0 until dim) {
                avg[i] += f[i]
            }
        }
        // 平均
        for (i in 0 until dim) {
            avg[i] /= features.size
        }
        // L2归一化
        var norm = 0f
        for (v in avg) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-6f) {
            for (i in 0 until dim) avg[i] /= norm
        }
        return avg
    }

    /**
     * 释放模型资源
     */
    fun release()
}
