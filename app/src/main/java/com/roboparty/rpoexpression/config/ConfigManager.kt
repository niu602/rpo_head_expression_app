/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * 配置管理器
 * 负责配置加载、热更新、持久化
 *
 * 配置加载优先级：
 * 1. 本地用户配置 > 2. assets默认配置
 *
 * 支持热更新：通过TCP下发JSON片段，实时生效无需重启
 */
class ConfigManager private constructor() {

    companion object {
        private const val TAG = "ConfigManager"
        private const val DEFAULT_CONFIG_ASSET = "default_config.json"
        private const val USER_CONFIG_FILE = "user_config.json"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager().also { instance = it }
            }
        }
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var context: Context

    // 当前配置
    private val _config = MutableStateFlow(ExpressionConfig())
    val config: StateFlow<ExpressionConfig> = _config.asStateFlow()

    // 配置变更监听器
    private val configChangeListeners = mutableListOf<(ExpressionConfig) -> Unit>()

    /**
     * 初始化配置管理器
     * @param context 应用上下文
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        loadConfig()
        Log.d(TAG, "ConfigManager initialized, version=${_config.value.version}")
    }

    /**
     * 加载配置
     * 优先加载用户配置，不存在则加载assets默认配置
     */
    private fun loadConfig() {
        var loaded = false

        // 1. 尝试加载用户保存的配置
        val userConfigFile = File(context.filesDir, USER_CONFIG_FILE)
        if (userConfigFile.exists()) {
            try {
                val json = userConfigFile.readText()
                val userConfig = gson.fromJson(json, ExpressionConfig::class.java)
                if (userConfig != null) {
                    _config.value = userConfig
                    loaded = true
                    Log.i(TAG, "Loaded user config from ${userConfigFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load user config, falling back to default", e)
            }
        }

        // 2. 加载assets默认配置
        if (!loaded) {
            try {
                context.assets.open(DEFAULT_CONFIG_ASSET).use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        val defaultConfig = gson.fromJson(reader, ExpressionConfig::class.java)
                        if (defaultConfig != null) {
                            _config.value = defaultConfig
                            loaded = true
                            Log.i(TAG, "Loaded default config from assets")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load default config, using built-in defaults", e)
                _config.value = ExpressionConfig()
            }
        }

        // 通知监听器
        notifyConfigChanged()
    }

    /**
     * 从JSON字符串更新配置（支持部分更新）
     * @param jsonConfig JSON配置字符串，可以是完整配置或部分字段
     * @param persist 是否持久化到本地
     * @return 是否更新成功
     */
    fun updateFromJson(jsonConfig: String, persist: Boolean = true): Boolean {
        return try {
            // 解析为Map进行增量合并
            val currentMap = gson.fromJson(gson.toJson(_config.value), Map::class.java) as MutableMap<String, Any>
            val newMap = gson.fromJson(jsonConfig, Map::class.java) as Map<String, Any>

            // 深度合并
            val mergedMap = deepMerge(currentMap, newMap)
            val updatedConfig = gson.fromJson(gson.toJson(mergedMap), ExpressionConfig::class.java)

            _config.value = updatedConfig
            Log.i(TAG, "Config updated successfully: $jsonConfig")

            if (persist) {
                saveConfig()
            }

            notifyConfigChanged()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update config from json", e)
            false
        }
    }

    /**
     * 从本地文件导入配置
     * @param file 配置文件
     * @return 是否导入成功
     */
    fun importFromFile(file: File): Boolean {
        return try {
            val json = file.readText()
            val result = updateFromJson(json, persist = true)
            if (result) {
                Log.i(TAG, "Config imported from ${file.absolutePath}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import config from file", e)
            false
        }
    }

    /**
     * 重置为默认配置
     */
    fun resetToDefault() {
        val userConfigFile = File(context.filesDir, USER_CONFIG_FILE)
        if (userConfigFile.exists()) {
            userConfigFile.delete()
        }
        loadConfig()
        Log.i(TAG, "Config reset to default")
    }

    /**
     * 导出当前配置为JSON字符串
     */
    fun exportToJson(): String {
        return gson.toJson(_config.value)
    }

    /**
     * 导出配置到文件
     */
    fun exportToFile(file: File): Boolean {
        return try {
            file.writeText(exportToJson())
            Log.i(TAG, "Config exported to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export config", e)
            false
        }
    }

    /**
     * 保存当前配置到本地
     */
    private fun saveConfig() {
        try {
            val userConfigFile = File(context.filesDir, USER_CONFIG_FILE)
            FileOutputStream(userConfigFile).use { fos ->
                fos.write(gson.toJson(_config.value).toByteArray())
            }
            Log.d(TAG, "Config saved to ${userConfigFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }

    /**
     * 添加配置变更监听器
     */
    fun addConfigChangeListener(listener: (ExpressionConfig) -> Unit) {
        configChangeListeners.add(listener)
        // 立即回调一次当前配置
        listener(_config.value)
    }

    /**
     * 移除配置变更监听器
     */
    fun removeConfigChangeListener(listener: (ExpressionConfig) -> Unit) {
        configChangeListeners.remove(listener)
    }

    /**
     * 通知所有监听器配置变更
     */
    private fun notifyConfigChanged() {
        configChangeListeners.forEach { listener ->
            try {
                listener(_config.value)
            } catch (e: Exception) {
                Log.e(TAG, "Error in config change listener", e)
            }
        }
    }

    /**
     * 深度合并两个Map（增量更新）
     */
    @Suppress("UNCHECKED_CAST")
    private fun deepMerge(target: MutableMap<String, Any>, source: Map<String, Any>): MutableMap<String, Any> {
        for ((key, value) in source) {
            if (value is Map<*, *> && target[key] is Map<*, *>) {
                // 嵌套对象递归合并
                val targetNested = target[key] as MutableMap<String, Any>
                val sourceNested = value as Map<String, Any>
                target[key] = deepMerge(targetNested, sourceNested)
            } else {
                // 基本类型直接覆盖
                target[key] = value
            }
        }
        return target
    }
}
