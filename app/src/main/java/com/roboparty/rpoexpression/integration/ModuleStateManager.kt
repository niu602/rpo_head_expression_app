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

    // ==================== ASR语音识别模块对接 ====================
    /**
     * ASR开始监听（等待用户说话）
     * 自动切换到识别中表情
     */
    fun onAsrStartListening() {
        Log.d(TAG, "ASR start listening, switch to RECOGNIZING")
        expressionManager.requestExpression(ExpressionType.RECOGNIZING)
    }

    /**
     * ASR识别中（检测到用户说话，正在处理语音）
     * 保持识别中表情
     */
    fun onAsrProcessing() {
        Log.d(TAG, "ASR processing, switch to RECOGNIZING")
        expressionManager.requestExpression(ExpressionType.RECOGNIZING)
    }

    /**
     * ASR识别结束
     * 释放识别中表情，回落到待机，让后续思考/说话表情可以正常抢占
     */
    fun onAsrFinish() {
        Log.d(TAG, "ASR finish, release RECOGNIZING")
        expressionManager.requestExpression(ExpressionType.IDLE)
    }

    /**
     * ASR识别错误
     * 强制回到待机状态，避免卡在识别中
     */
    fun onAsrError() {
        Log.d(TAG, "ASR error, fallback to IDLE")
        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
    }

    // ==================== LLM大模型模块对接 ====================
    /**
     * LLM开始思考生成回复
     * 自动切换到思考表情（强制切换，确保能从识别中状态切过来）
     */
    fun onLlmStartThinking() {
        Log.d(TAG, "LLM start thinking, switch to THINKING")
        expressionManager.requestExpression(ExpressionType.THINKING, force = true)
    }

    /**
     * LLM生成结束
     * 不需要自动切状态，TTS开始会自动抢占为说话表情
     */
    fun onLlmFinish() {
        Log.d(TAG, "LLM finish")
    }

    /**
     * LLM请求错误
     * 强制回到待机状态，避免卡在思考/识别中
     */
    fun onLlmError() {
        Log.d(TAG, "LLM error, fallback to IDLE")
        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
    }

    // ==================== TTS语音播报模块对接 ====================
    /**
     * TTS开始播报
     * 自动切换到说话表情
     */
    fun onTtsStartSpeaking() {
        Log.d(TAG, "TTS start speaking, switch to SPEAKING")
        expressionManager.requestExpression(ExpressionType.SPEAKING)
    }

    /**
     * TTS播报结束
     * 对话结束，自动回到待机表情
     */
    fun onTtsFinishSpeaking() {
        Log.d(TAG, "TTS finish speaking, back to IDLE")
        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
    }

    /**
     * TTS播报错误
     * 强制回到待机状态
     */
    fun onTtsError() {
        Log.d(TAG, "TTS error, fallback to IDLE")
        expressionManager.requestExpression(ExpressionType.IDLE, force = true)
    }

    // ==================== 人脸识别模块对接 ====================
    /**
     * 人脸识别开始
     * 静默检测模式：不触发识别中表情，仅后台运行
     */
    fun onFaceDetectStart() {
        Log.d(TAG, "Face detect start (silent mode, no expression)")
        // 静默人脸检测不显示识别中表情，避免频繁切换
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
        expressionManager.requestExpression(ExpressionType.IDLE,force = true)
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
