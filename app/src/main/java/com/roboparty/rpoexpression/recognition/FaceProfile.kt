/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

/**
 * 人脸档案数据类
 *
 * 存储一个已注册用户的完整信息，包括：
 * - 基本身份信息（ID、名字、注册时间）
 * - 人脸特征向量（占位，具体模型后续填充）
 * - 用户偏好设置
 *
 * 序列化说明：
 * - 使用 Gson 序列化到 SharedPreferences（轻量，人脸数量<100时足够）
 * - feature 字段为 FloatArray?，特征向量维度由具体模型决定（通常128/256/512维）
 *
 * @param id 唯一标识，UUID生成
 * @param name 用户名字（注册时通过语音"我叫XXX"获取）
 * @param registerTime 注册时间戳（毫秒）
 * @param feature 人脸特征向量（占位，具体模型提取后填充）
 * @param sampleCount 已采集的样本帧数（注册时采集N帧取平均）
 * @param lastRecognizeTime 上次识别到的时间戳
 * @param recognizeCount 累计识别次数
 * @param preferences 用户偏好设置（语音风格、称呼等，预留扩展）
 */
data class FaceProfile(
    val id: String,
    var name: String,
    val registerTime: Long,
    var feature: FloatArray? = null,
    var sampleCount: Int = 0,
    var lastRecognizeTime: Long = 0L,
    var recognizeCount: Int = 0,
    var preferences: MutableMap<String, String> = mutableMapOf()
) {
    /**
     * 特征向量是否已就绪（注册完成且特征有效）
     */
    fun isFeatureReady(): Boolean = feature != null && sampleCount >= REQUIRED_SAMPLE_COUNT

    /**
     * 更新识别记录
     */
    fun markRecognized() {
        lastRecognizeTime = System.currentTimeMillis()
        recognizeCount++
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceProfile) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String {
        return "FaceProfile(id='$id', name='$name', samples=$sampleCount, " +
                "featureReady=${isFeatureReady()}, recognized=$recognizeCount times)"
    }

    companion object {
        /** 注册需要采集的最少样本帧数 */
        const val REQUIRED_SAMPLE_COUNT = 5
        /** 特征向量维度：MobileFaceNet输出192维 */
        const val FEATURE_DIMENSION = 192
    }
}
