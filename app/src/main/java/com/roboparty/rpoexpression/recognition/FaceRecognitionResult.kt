/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

/**
 * 人脸识别结果
 */
sealed class FaceRecognitionResult {
    /**
     * 识别成功：匹配到已注册用户
     * @param profile 匹配到的人脸档案
     * @param similarity 相似度 [0, 1]
     */
    data class Recognized(val profile: FaceProfile, val similarity: Float) : FaceRecognitionResult()

    /**
     * 陌生人：没有匹配到任何已注册用户
     * @param maxSimilarity 和最相似用户的相似度（低于阈值）
     */
    data class Stranger(val maxSimilarity: Float) : FaceRecognitionResult()

    /**
     * 不确定：相似度在灰色地带，需要更多帧确认
     * @param bestMatch 最相似的候选（可能为null）
     * @param similarity 相似度
     */
    data class Uncertain(val bestMatch: FaceProfile?, val similarity: Float) : FaceRecognitionResult()

    /**
     * 识别失败：特征提取失败或无人脸
     */
    data class Failed(val reason: String) : FaceRecognitionResult()
}
