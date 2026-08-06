/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.roboparty.rpoexpression.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.roboparty.rpoexpression.llm.LlmManager

/**
 * 用户音色偏好存储
 * 基于 SharedPreferences，按人格分开存储用户选择的音色
 *
 * 设计原则：
 * - 按人格分存：用户给萝卜挑的女声、给阿冷挑的男声互不覆盖
 * - 来回切人格仍是各自选定的音色
 * - 读不到时返回 null，由调用方回落到默认音色
 *
 * Key 规则：voice_selected_{persona}
 * 例如：voice_selected_LUOBO, voice_selected_ALIN
 */
class VoicePreferenceStore(context: Context) {

    companion object {
        private const val TAG = "VoicePreferenceStore"
        private const val PREFS_NAME = "rpo_voice_prefs"
        private const val KEY_PREFIX = "voice_selected_"

        @Volatile
        private var instance: VoicePreferenceStore? = null

        fun getInstance(context: Context): VoicePreferenceStore {
            return instance ?: synchronized(this) {
                instance ?: VoicePreferenceStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 获取指定人格的用户选择音色
     *
     * @param persona 人格
     * @return 用户选择的 voiceId，未选择过返回 null
     */
    fun getSelectedVoice(persona: LlmManager.Persona): String? {
        val key = buildKey(persona)
        val voiceId = prefs.getString(key, null)
        if (voiceId != null) {
            Log.d(TAG, "Get selected voice for $persona: $voiceId")
        } else {
            Log.d(TAG, "No selected voice for $persona, will use default")
        }
        return voiceId
    }

    /**
     * 保存指定人格的用户选择音色
     * 保存前会校验性别是否匹配（三道关卡之一：写入层校验）
     *
     * @param persona 人格
     * @param voiceId 选择的音色 ID
     * @return 是否保存成功（性别不匹配会拒绝）
     */
    fun setSelectedVoice(persona: LlmManager.Persona, voiceId: String): Boolean {
        // 先从 PersonaRegistry 获取该人格对应的风格
        val config = PersonaRegistry.getConfig(persona)
        val expectedGender = config.expressionStyle

        // 性别校验：防止选到异性音色
        if (!VoiceLibrary.isVoiceMatchingGender(voiceId, expectedGender)) {
            Log.w(TAG, "Voice $voiceId doesn't match gender $expectedGender for persona $persona, reject")
            return false
        }

        val key = buildKey(persona)
        prefs.edit().putString(key, voiceId).apply()
        Log.i(TAG, "Saved selected voice for $persona: $voiceId")
        return true
    }

    /**
     * 清除指定人格的用户选择音色
     * 清除后会回落到默认音色
     *
     * @param persona 人格
     */
    fun clearSelectedVoice(persona: LlmManager.Persona) {
        val key = buildKey(persona)
        prefs.edit().remove(key).apply()
        Log.d(TAG, "Cleared selected voice for $persona")
    }

    /**
     * 清除所有人格的音色选择
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared all voice preferences")
    }

    /**
     * 解析用户选择的音色，带校验和回退
     * 三道关卡之三：解析层校验，处理脏数据
     *
     * 流程：
     * 1. 读取用户选择的 voiceId
     * 2. 用 VoiceLibrary.getById 校验是否存在
     * 3. 校验性别是否匹配目标人格
     * 4. 都通过则返回用户选择，否则回落到默认音色
     *
     * @param persona 目标人格
     * @return 最终使用的 VoiceOption
     */
    fun resolveVoiceForPersona(persona: LlmManager.Persona): VoiceOption {
        val config = PersonaRegistry.getConfig(persona)
        val expectedGender = config.expressionStyle

        // 1. 读取用户选择
        val selectedVoiceId = getSelectedVoice(persona)

        if (selectedVoiceId != null) {
            // 2. 校验是否存在于音色库中
            val voiceOption = VoiceLibrary.getById(selectedVoiceId)
            if (voiceOption != null) {
                // 3. 校验性别是否匹配
                if (voiceOption.gender == expectedGender) {
                    Log.d(TAG, "Resolved voice for $persona: user selected $selectedVoiceId")
                    return voiceOption
                } else {
                    Log.w(TAG, "Voice $selectedVoiceId gender mismatch for $persona, fallback to default")
                }
            } else {
                Log.w(TAG, "Voice $selectedVoiceId not found in library for $persona, fallback to default")
            }
        }

        // 4. 回落到默认音色
        val defaultVoice = VoiceLibrary.getDefault(expectedGender)
        Log.d(TAG, "Resolved voice for $persona: default ${defaultVoice.voiceId}")
        return defaultVoice
    }

    private fun buildKey(persona: LlmManager.Persona): String {
        return KEY_PREFIX + persona.name
    }
}
