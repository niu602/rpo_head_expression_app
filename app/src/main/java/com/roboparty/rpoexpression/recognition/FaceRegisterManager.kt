/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 人脸注册管理器
 *
 * 职责：
 * 1. 管理已注册人脸档案的增删改查
 * 2. 注册流程状态机（空闲→采集中→处理中→成功/失败）
 * 3. 多帧质量筛选（角度/关键点/帧间一致性）
 * 4. 特征融合（Top-5质量帧平均+L2归一化）
 * 5. SharedPreferences持久化（Gson序列化）
 *
 * 注册流程（质量筛选版）：
 * IDLE → 用户说"我叫XXX" → startRegistration(name) → COLLECTING
 *   → 每200ms一帧，检查质量（角度<20°、5点齐全、占屏>10%）
 *   → 收集最多15帧候选（3秒内）
 *   → 按质量排序取Top-5
 *   → 检查5帧两两相似度>0.6（确保是同一个人）
 *   → 特征平均融合+L2归一化
 *   → 保存 → SUCCESS
 *   → 质量不达标 → FAILED
 *
 * 持久化方案：
 * - SharedPreferences + Gson（轻量，人脸数量<100时性能足够）
 * - 后续人脸数量多了可迁移到Room数据库
 */
class FaceRegisterManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FaceRegisterManager"
        private const val PREFS_NAME = "rpo_face_profiles_encrypted"  // 加密存储
        private const val LEGACY_PREFS_NAME = "rpo_face_profiles"     // 旧版明文存储（用于迁移）
        private const val KEY_PROFILES = "profiles_json"

        // 注册参数
        private const val REGISTER_SAMPLE_COUNT = 8           // 最终融合需要8 帧
        private const val REGISTER_MAX_CANDIDATES = 15        // 最多收集15帧候选
        private const val REGISTER_TIMEOUT_MS = 10000L        // 注册超时10秒
        private const val SAMPLE_INTERVAL_MS = 200L           // 采样间隔200ms
        private const val MIN_FRAME_SIMILARITY = 0.6f         // 帧间最小相似度（确保是同一人）
        private const val OVERRIDE_SIMILARITY_THRESHOLD = 0.35f // 同名覆盖相似度阈值（低于则视为不同人，自动加编号）

        @Volatile
        private var instance: FaceRegisterManager? = null

        fun getInstance(context: Context): FaceRegisterManager {
            return instance ?: synchronized(this) {
                instance ?: FaceRegisterManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ========== 注册状态 ==========
    enum class RegisterState {
        IDLE,           // 空闲，未在注册
        COLLECTING,     // 正在采集样本
        PROCESSING,     // 处理中（融合特征）
        SUCCESS,        // 注册成功
        FAILED          // 注册失败
    }

    /**
     * 带质量评分的候选帧
     */
    private data class CandidateFrame(
        val feature: FloatArray,
        val qualityScore: Float,  // 质量评分（越高越好）
        val timestamp: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CandidateFrame) return false
            return timestamp == other.timestamp
        }
        override fun hashCode(): Int = timestamp.hashCode()
    }

    // ========== 注册回调 ==========
    interface RegisterCallback {
        /** 注册开始（进入采集状态） */
        fun onRegisterStart(name: String) {}
        /** 采集进度 @param collected 已采集有效帧数 @param total 需要总帧数 */
        fun onSampleCollected(collected: Int, total: Int) {}
        /** 注册成功 */
        fun onRegisterSuccess(profile: FaceProfile) {}
        /** 注册失败 */
        fun onRegisterFailed(reason: String) {}
        /** 注册超时 */
        fun onRegisterTimeout() {}
    }

    // ========== 依赖 ==========
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(FloatArray::class.java, FloatArrayTypeAdapter())
        .create()

    // 加密 SharedPreferences：生物特征数据加密存储
    // 初始化失败时自动降级到普通 SharedPreferences
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also {
                Log.i(TAG, "EncryptedSharedPreferences initialized (AES256_GCM)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to plain SharedPreferences", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // 特征提取器在 setFeatureExtractor() 中由外部注入（共享FaceIdentifier的TFLite实例）
    // 延迟初始化，避免构造函数中创建独立TFLite实例导致原生内存泄漏
    private var featureExtractor: FaceFeatureExtractor = DummyFeatureExtractor()

    // ========== 已注册人脸库（ConcurrentHashMap线程安全） ==========
    private val profiles = ConcurrentHashMap<String, FaceProfile>()

    // ========== 当前注册会话状态 ==========
    @Volatile private var registerState = RegisterState.IDLE
    private var currentRegisterName: String = ""
    private var currentRegisterProfile: FaceProfile? = null
    private var pendingOverrideProfile: FaceProfile? = null  // 待覆盖的同名档案（用于相似度比对）
    private val candidateFrames = Collections.synchronizedList(mutableListOf<CandidateFrame>())
    private var lastSampleTime = 0L
    private var registerCallback: RegisterCallback? = null
    private var timeoutJob: Job? = null

    /**
     * 初始化：加载已持久化的人脸档案，初始化特征提取器
     */
    fun initialize() {
        // 数据迁移：从旧版明文 SharedPreferences 迁移到加密版
        migrateFromLegacyPrefs()

        loadProfiles()
        // 注意：featureExtractor的initialize在FaceIdentifier中统一调用，避免重复加载模型
        Log.i(TAG, "FaceRegisterManager initialized, ${profiles.size} profiles loaded")
    }

    /**
     * 从旧版明文 SharedPreferences 迁移到加密版
     * 只在第一次启动且旧版有数据时执行
     */
    private fun migrateFromLegacyPrefs() {
        try {
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val legacyJson = legacyPrefs.getString(KEY_PROFILES, null)
            if (!legacyJson.isNullOrEmpty()) {
                Log.i(TAG, "Found legacy plaintext profiles, migrating to encrypted storage...")

                // 读取旧数据
                val type = object : TypeToken<List<FaceProfile>>() {}.type
                val list: List<FaceProfile> = gson.fromJson(legacyJson, type) ?: emptyList()

                // 写入新的加密 SharedPreferences
                prefs.edit().putString(KEY_PROFILES, legacyJson).apply()

                // 验证：读取新数据确认迁移成功
                val newJson = prefs.getString(KEY_PROFILES, null)
                if (!newJson.isNullOrEmpty() && newJson.length == legacyJson.length) {
                    // 迁移成功，删除旧数据
                    legacyPrefs.edit().remove(KEY_PROFILES).apply()
                    Log.i(TAG, "✅ Migration successful: ${list.size} profiles migrated, legacy data deleted")
                } else {
                    Log.w(TAG, "Migration verification failed, legacy data kept as backup")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration from legacy prefs failed", e)
            // 迁移失败不影响正常使用，旧数据保留
        }
    }

    /**
     * 设置特征提取器（和FaceIdentifier共享同一个实例，避免重复加载TFLite模型）
     */
    fun setFeatureExtractor(extractor: FaceFeatureExtractor) {
        // 释放旧的占位提取器资源（如果是 DummyFeatureExtractor 则为空操作）
        this.featureExtractor.release()
        this.featureExtractor = extractor
    }

    /**
     * 开始注册流程
     *
     * @param name 用户名字（语音"我叫XXX"解析得到）
     * @param callback 注册状态回调
     * @return 是否成功开始注册
     */
    fun startRegistration(name: String, callback: RegisterCallback? = null): Boolean {
        if (registerState != RegisterState.IDLE) {
            Log.w(TAG, "Already registering, current state=$registerState")
            return false
        }
        if (name.isBlank()) {
            Log.w(TAG, "Name is blank, cannot register")
            return false
        }

        // 检查是否已注册同名用户
        val existing = getProfileByName(name)
        if (existing != null && existing.feature != null) {
            Log.i(TAG, "User '$name' already registered, will check similarity before override")
            // 已注册同名用户：先标记为"待确认覆盖"
            // 采集到特征后比对相似度，是同一人则覆盖，不是同一人则自动加编号
            pendingOverrideProfile = existing
            currentRegisterProfile = FaceProfile(
                id = UUID.randomUUID().toString().take(8),
                name = name.trim(),
                registerTime = System.currentTimeMillis()
            )
        } else if (existing != null) {
            // 同名但没有特征（异常状态），直接覆盖
            Log.i(TAG, "User '$name' exists but no feature, overriding")
            currentRegisterProfile = existing
        } else {
            currentRegisterProfile = FaceProfile(
                id = UUID.randomUUID().toString().take(8),
                name = name.trim(),
                registerTime = System.currentTimeMillis()
            )
        }

        currentRegisterName = name.trim()
        registerCallback = callback
        candidateFrames.clear()
        lastSampleTime = 0L
        registerState = RegisterState.COLLECTING

        Log.i(TAG, "Start registration for '$name', collecting up to $REGISTER_MAX_CANDIDATES frames")

        // 超时计时器
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(REGISTER_TIMEOUT_MS)
            withContext(Dispatchers.Main) {
                if (registerState == RegisterState.COLLECTING) {
                    // 超时但有足够帧，尝试完成注册
                    if (candidateFrames.size >= REGISTER_SAMPLE_COUNT) {
                        Log.w(TAG, "Registration timeout but have enough frames, finishing")
                        finishRegistration()
                    } else {
                        Log.w(TAG, "Registration timeout for '$name'")
                        registerState = RegisterState.FAILED
                        registerCallback?.onRegisterTimeout()
                        registerCallback?.onRegisterFailed("注册超时，请正对镜头再试一次")
                        resetRegistration()
                    }
                }
            }
        }

        callback?.onRegisterStart(currentRegisterName)
        return true
    }

    /**
     * 添加一帧样本（在VisionManager检测到人脸时调用）
     *
     * 质量筛选：
     * 1. 采样间隔控制（200ms）
     * 2. 人脸质量检查（角度<20°、5点齐全、占屏>10%）
     * 3. 特征提取（内部处理对齐+预处理+推理）
     * 4. 加入候选列表
     * 5. 收集够候选帧后自动完成注册
     */
    fun addSample(fullImage: Bitmap, face: Face, srcWidth: Int = fullImage.width, srcHeight: Int = fullImage.height, rotation: Int = 0) {
        if (registerState != RegisterState.COLLECTING) {
            safeRecycle(fullImage)
            return
        }
        // 特征提取器未就绪时跳过（模型可能在异步加载中）
        if (!featureExtractor.isReady()) {
            Log.v(TAG, "Feature extractor not ready, skip sample")
            safeRecycle(fullImage)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSampleTime < SAMPLE_INTERVAL_MS) {
            safeRecycle(fullImage)
            return
        }
        lastSampleTime = now

        scope.launch {
            try {
                // 防止使用已回收的Bitmap
                if (fullImage.isRecycled || fullImage.width == 0 || fullImage.height == 0) {
                    return@launch
                }
                // 1. 质量检查
                val quality = FaceAligner.evaluateFaceQuality(face, fullImage.width, fullImage.height)
                if (!quality.isGoodQuality) {
                    Log.v(TAG, "Skip low quality frame: yaw=%.1f, roll=%.1f".format(
                        quality.yawAngle, quality.rollAngle))
                    return@launch
                }

                // 2. 提取特征（传入完整帧，内部处理裁剪和对齐）
                val feature = featureExtractor.extractFeature(fullImage, face, srcWidth, srcHeight, rotation, mirrorX = true)

                if (feature != null) {
                    // 3. 计算质量评分（角度越小分越高，脸越大分越高）
                    val anglePenalty = (kotlin.math.abs(quality.yawAngle) + kotlin.math.abs(quality.rollAngle)) / 40f
                    val sizeScore = quality.faceWidth.toFloat() / fullImage.width
                    val qualityScore = (1f - anglePenalty) * 0.6f + sizeScore * 0.4f

                    candidateFrames.add(CandidateFrame(feature, qualityScore, now))

                    val collected = candidateFrames.size
                    withContext(Dispatchers.Main) {
                        registerCallback?.onSampleCollected(
                            minOf(collected, REGISTER_SAMPLE_COUNT),
                            REGISTER_SAMPLE_COUNT
                        )
                    }
                    Log.d(TAG, "Sample collected: $collected (quality=%.2f)".format(qualityScore))

                    // 4. 收集够候选帧后完成注册
                    if (collected >= REGISTER_MAX_CANDIDATES) {
                        finishRegistration()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Add sample failed", e)
            } finally {
                // 采样完成，回收传入的Bitmap（VisionManager移交了所有权）
                if (!fullImage.isRecycled) {
                    try { fullImage.recycle() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * 完成注册：质量筛选→特征融合→保存
     */
    private suspend fun finishRegistration() {
        withContext(Dispatchers.Main) {
            registerState = RegisterState.PROCESSING
        }

        try {
            if (candidateFrames.size < REGISTER_SAMPLE_COUNT) {
                withContext(Dispatchers.Main) {
                    registerState = RegisterState.FAILED
                    registerCallback?.onRegisterFailed("样本不足，请正对镜头再试一次")
                    resetRegistration()
                }
                return
            }

            // 1. 按质量评分排序，取Top-K
            // synchronizedList.iterator() 返回的迭代器自带同步，
            // 但 asSequence 会情展创建快照后能安全使用
            val topFrames = synchronized(candidateFrames) {
                candidateFrames
                    .sortedByDescending { it.qualityScore }
                    .take(REGISTER_SAMPLE_COUNT)
            }

            // 2. 帧间一致性检查：两两相似度>0.6，确保是同一个人
            val features = topFrames.map { it.feature }
            var minPairSim = 1f
            for (i in features.indices) {
                for (j in i + 1 until features.size) {
                    val sim = featureExtractor.cosineSimilarity(features[i], features[j])
                    if (sim < minPairSim) minPairSim = sim
                }
            }
            if (minPairSim < MIN_FRAME_SIMILARITY) {
                Log.w(TAG, "Frame consistency check failed: minPairSim=%.3f".format(minPairSim))
                withContext(Dispatchers.Main) {
                    registerState = RegisterState.FAILED
                    registerCallback?.onRegisterFailed("检测到多张人脸或人脸移动，请保持正对镜头")
                    resetRegistration()
                }
                return
            }

            // 3. 特征融合（平均+L2归一化）
            val finalFeature = featureExtractor.averageFeatures(features)
            if (finalFeature == null) {
                withContext(Dispatchers.Main) {
                    registerState = RegisterState.FAILED
                    registerCallback?.onRegisterFailed("特征融合失败，请再试一次")
                    resetRegistration()
                }
                return
            }

            val profile = currentRegisterProfile!!
            profile.feature = finalFeature
            profile.sampleCount = topFrames.size

            // 4. 同名覆盖检查：与待覆盖的同名档案比对相似度
            val overrideProfile = pendingOverrideProfile
            if (overrideProfile != null && overrideProfile.feature != null) {
                val sim = featureExtractor.cosineSimilarity(finalFeature, overrideProfile.feature!!)
                Log.i(TAG, "Same name check: similarity=%.3f, threshold=%.2f".format(sim, OVERRIDE_SIMILARITY_THRESHOLD))

                if (sim >= OVERRIDE_SIMILARITY_THRESHOLD) {
                    // 是同一个人 → 覆盖原档案（保留原 ID 和名字）
                    Log.i(TAG, "Same person, updating existing profile: ${overrideProfile.name}")
                    overrideProfile.feature = finalFeature
                    overrideProfile.sampleCount = topFrames.size
                    profiles[overrideProfile.id] = overrideProfile
                    // 清除临时档案
                    currentRegisterProfile = overrideProfile
                } else {
                    // 不是同一个人 → 自动加编号，创建新档案
                    val uniqueName = generateUniqueName(currentRegisterName)
                    profile.name = uniqueName
                    profiles[profile.id] = profile
                    Log.i(TAG, "Different person with same name, auto-renamed to: $uniqueName")
                }
            } else {
                // 没有同名用户 → 直接保存
                profiles[profile.id] = profile
            }

            // 5. 持久化
            saveProfiles()

            timeoutJob?.cancel()

            withContext(Dispatchers.Main) {
                registerState = RegisterState.SUCCESS
                Log.i(TAG, "✅ Registration success: ${profile.name}, id=${profile.id}, " +
                        "frames=${topFrames.size}, minPairSim=%.3f".format(minPairSim))
                registerCallback?.onRegisterSuccess(profile)
                resetRegistration()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Finish registration failed", e)
            withContext(Dispatchers.Main) {
                registerState = RegisterState.FAILED
                registerCallback?.onRegisterFailed("注册失败：${e.message}")
                resetRegistration()
            }
        }
    }

    /**
     * 取消注册
     */
    fun cancelRegistration(reason: String = "用户取消") {
        if (registerState == RegisterState.IDLE) return
        Log.i(TAG, "Registration cancelled: $reason")
        timeoutJob?.cancel()
        registerState = RegisterState.FAILED
        registerCallback?.onRegisterFailed(reason)
        resetRegistration()
    }

    /**
     * 人脸丢失时调用（注册过程中人脸离开）
     */
    fun onFaceLost() {
        if (registerState == RegisterState.COLLECTING && candidateFrames.size < REGISTER_SAMPLE_COUNT / 2) {
            cancelRegistration("人脸离开，注册失败")
        }
    }

    private fun resetRegistration() {
        registerState = RegisterState.IDLE
        currentRegisterName = ""
        currentRegisterProfile = null
        pendingOverrideProfile = null
        candidateFrames.clear()
        lastSampleTime = 0L
        timeoutJob?.cancel()
        timeoutJob = null
        registerCallback = null
    }

    // ========== CRUD 操作 ==========

    fun getAllProfiles(): List<FaceProfile> = profiles.values.toList()

    fun getProfileById(id: String): FaceProfile? = profiles[id]

    fun getProfileByName(name: String): FaceProfile? =
        profiles.values.find { it.name.equals(name, ignoreCase = true) }

    /**
     * 生成唯一的名字（同名时自动加编号）
     * 例如：小明 → 小明2 → 小明3 → ...
     */
    private fun generateUniqueName(baseName: String): String {
        var count = 2
        var name = "$baseName$count"
        while (getProfileByName(name) != null) {
            count++
            name = "$baseName$count"
        }
        return name
    }

    fun updateProfile(profile: FaceProfile) {
        profiles[profile.id] = profile
        saveProfiles()
    }

    fun deleteProfile(id: String): Boolean {
        val removed = profiles.remove(id) != null
        if (removed) saveProfiles()
        return removed
    }

    fun deleteAllProfiles() {
        profiles.clear()
        saveProfiles()
        Log.i(TAG, "All profiles deleted")
    }

    fun getRegisteredCount(): Int = profiles.size

    fun isRegistering(): Boolean = registerState == RegisterState.COLLECTING || registerState == RegisterState.PROCESSING

    fun getRegisterState(): RegisterState = registerState

    // ========== 持久化 ==========

    private fun loadProfiles() {
        try {
            val json = prefs.getString(KEY_PROFILES, null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<List<FaceProfile>>() {}.type
                val list: List<FaceProfile> = gson.fromJson(json, type) ?: emptyList()
                profiles.clear()
                list.forEach { profiles[it.id] = it }
                Log.i(TAG, "Loaded ${profiles.size} profiles from SharedPreferences")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load profiles failed", e)
        }
    }

    private fun saveProfiles() {
        scope.launch {
            try {
                val list = profiles.values.toList()
                val json = gson.toJson(list)
                prefs.edit().putString(KEY_PROFILES, json).apply()
                Log.d(TAG, "Saved ${profiles.size} profiles")
            } catch (e: Exception) {
                Log.e(TAG, "Save profiles failed", e)
            }
        }
    }

    /**
     * 安全回收Bitmap（忽略异常）
     */
    private fun safeRecycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        timeoutJob?.cancel()
        resetRegistration()
    }
}

/**
 * FloatArray 的 Gson TypeAdapter
 *
 * Gson 默认把 FloatArray 序列化成 ["a":0.1,"b":0.2,...] 这种带 "a"/"b" key 的对象，
 * 反序列化时会变成 ArrayList<Double> 或 LinkedTreeMap，导致特征向量丢失。
 * 此 Adapter 把 FloatArray 序列化为 JSON 数组 [0.1, 0.2, ...]，反序列化时正确恢复。
 */
private class FloatArrayTypeAdapter : com.google.gson.JsonSerializer<FloatArray>, com.google.gson.JsonDeserializer<FloatArray> {
    override fun serialize(
        src: FloatArray,
        typeOfSrc: java.lang.reflect.Type,
        context: com.google.gson.JsonSerializationContext
    ): JsonElement {
        val arr = JsonArray(src.size)
        for (v in src) {
            arr.add(JsonPrimitive(v))
        }
        return arr
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: java.lang.reflect.Type,
        context: com.google.gson.JsonDeserializationContext
    ): FloatArray {
        if (json.isJsonArray) {
            val arr = json.asJsonArray
            val size = arr.size()
            val result = FloatArray(size)
            for (i in 0 until size) {
                result[i] = arr[i].asFloat
            }
            return result
        }
        return FloatArray(0)
    }
}
