/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.integration

import android.util.Log
import com.roboparty.rpoexpression.expression.ExpressionManager
import com.roboparty.rpoexpression.expression.ExpressionType
import kotlinx.coroutines.launch
/**
 * 多模块状态联动管理器
 * 对接ASR、TTS、人脸识别等模块的状态，自动切换对应表情
 *
 * 其他模块只需要调用对应的状态方法即可，不需要关心表情切换逻辑
 */
object ModuleStateManager {
    private const val TAG = "ModuleStateManager"
    private val expressionManager = ExpressionManager.getInstance()

    // 记录TTS开始前的表情，播报结束后恢复
    private var expressionBeforeSpeaking: ExpressionType = ExpressionType.IDLE

    // ==================== ASR语音识别模块对接 ====================
    /**
     * ASR开始识别（用户开始说话）
     * 自动切换到思考表情
     */
    fun onAsrStartListening() {
        Log.d(TAG, "ASR start listening, switch to THINKING")
        expressionManager.requestExpression(ExpressionType.THINKING)
    }

    /**
     * ASR识别中（正在处理语音）
     * 自动切换到识别中表情
     */
    fun onAsrProcessing() {
        Log.d(TAG, "ASR processing, switch to RECOGNIZING")
        expressionManager.requestExpression(ExpressionType.RECOGNIZING)
    }

    /**
     * ASR识别结束
     * 1秒后自动回落到待机，等待TTS播报
     */
    fun onAsrFinish() {
        Log.d(TAG, "ASR finish")
        // 1秒后自动回IDLE，如果期间TTS开始会自动抢占
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(1000)
            // 只有当前还是识别相关表情才回IDLE，TTS开始后会被说话表情抢占
            if (expressionManager.expressionState.value.type in listOf(ExpressionType.THINKING, ExpressionType.RECOGNIZING)) {
                expressionManager.requestExpression(ExpressionType.IDLE)
            }
        }
    }

    /**
     * ASR识别错误
     * 显示应答错误表情（暂时用RESPONSE，后续可扩展错误表情）
     */
    fun onAsrError() {
        Log.d(TAG, "ASR error")
        expressionManager.requestExpression(ExpressionType.RESPONSE, duration = 1000)
    }

    // ==================== TTS语音播报模块对接 ====================
    /**
     * TTS开始播报
     * 自动切换到说话表情，记录当前表情
     */
    fun onTtsStartSpeaking() {
        Log.d(TAG, "TTS start speaking, switch to SPEAKING")
        expressionBeforeSpeaking = expressionManager.expressionState.value.type
        expressionManager.requestExpression(ExpressionType.SPEAKING)
    }

    /**
     * TTS播报结束
     * 自动回落到播报前的表情
     */
    fun onTtsFinishSpeaking() {
        Log.d(TAG, "TTS finish speaking, back to $expressionBeforeSpeaking")
        expressionManager.requestExpression(expressionBeforeSpeaking, force = true)
    }

    /**
     * TTS播报错误
     */
    fun onTtsError() {
        Log.d(TAG, "TTS error")
        expressionManager.requestExpression(ExpressionType.RESPONSE, duration = 1000)
    }

    // ==================== 人脸识别模块对接 ====================
    /**
     * 人脸识别开始
     * 自动切换到识别中表情
     */
    fun onFaceDetectStart() {
        Log.d(TAG, "Face detect start, switch to RECOGNIZING")
        expressionManager.requestExpression(ExpressionType.RECOGNIZING)
    }

    /**
     * 人脸识别成功
     * 显示应答反馈表情，1秒后回落
     */
    fun onFaceDetectSuccess() {
        Log.d(TAG, "Face detect success, switch to RESPONSE")
        expressionManager.requestExpression(ExpressionType.RESPONSE, duration = 1000)
    }

    /**
     * 人脸识别失败/超时
     */
    fun onFaceDetectFailed() {
        Log.d(TAG, "Face detect failed")
        expressionManager.requestExpression(ExpressionType.IDLE)
    }

    // ==================== 头部电机模块对接（预留） ====================
    /**
     * 头部动作开始，预留表情同步接口
     * @param actionType 动作类型：shake(摇头)/nod(点头)/look(转头)
     */
    fun onHeadActionStart(actionType: String) {
        Log.d(TAG, "Head action start: $actionType")
        // TODO: 后续根据动作类型切换对应表情，比如摇头时眼睛跟着转
    }

    /**
     * 头部动作结束
     */
    fun onHeadActionFinish() {
        Log.d(TAG, "Head action finish")
    }
}
