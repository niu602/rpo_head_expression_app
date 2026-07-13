/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.animation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.roboparty.rpoexpression.expression.ExpressionType
import java.io.IOException

/**
 * 表情素材加载器
 * 负责加载正式表情图片资源，带内存缓存和采样优化，避免内存溢出与卡顿
 *
 * 使用方法：
 * 1. 将正式表情素材放到app/src/main/assets/expressions/目录下
 * 2. 命名规范：idle.png/blink.png/speaking.png/thinking.png/recognizing.png/response.png
 * 3. 自动加载对应素材，替换矢量绘制
 */
@SuppressLint("StaticFieldLeak") // 单例持有Application Context，不会造成内存泄漏
class ExpressionAssetLoader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AssetLoader"
        private const val ASSET_PATH = "expressions/"
        private const val CACHE_SIZE = 6 * 1024 * 1024 // 6MB内存缓存，足够存6张表情图

        @Volatile
        private var instance: ExpressionAssetLoader? = null

        fun getInstance(context: Context): ExpressionAssetLoader {
            return instance ?: synchronized(this) {
                instance ?: ExpressionAssetLoader(context.applicationContext).also { instance = it }
            }
        }
    }

    // 内存缓存，避免重复加载图片
    private val memoryCache: LruCache<String, ImageBitmap> = object : LruCache<String, ImageBitmap>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return value.width * value.height * 4 // ARGB_8888 每个像素4字节
        }
    }

    // 是否使用图片素材（默认关闭，使用参数化矢量绘制；素材导入完成后可手动开启）
    var useImageAssets = false
        private set

    /**
     * 检查素材是否存在，初始化素材加载器
     * 默认使用参数化矢量绘制，符合纯眼部参数化要求
     */
    fun init() {
        try {
            val assets = context.assets.list(ASSET_PATH)
            val hasAssets = assets?.any { it.endsWith(".png") } == true
            Log.d(TAG, "Expression assets found: $hasAssets, count: ${assets?.size}, using parameterized rendering by default")
            // 默认使用参数化绘制，不自动启用图片素材
            useImageAssets = false
        } catch (e: IOException) {
            Log.w(TAG, "No expression assets found, using parameterized rendering", e)
            useImageAssets = false
        }
    }

    /**
     * 手动设置是否使用图片素材
     * @param enable true=使用图片素材，false=使用参数化矢量绘制
     */
    fun setUseImageAssets(enable: Boolean) {
        useImageAssets = enable
        Log.d(TAG, "useImageAssets set to: $enable")
    }

    /**
     * 加载指定表情的图片素材
     * @param type 表情类型枚举
     * @param targetWidth 目标宽度，自动采样缩放避免OOM
     * @param targetHeight 目标高度
     */
    fun loadExpressionBitmap(
        type: ExpressionType,
        targetWidth: Int,
        targetHeight: Int
    ): ImageBitmap? {
        // 默认使用参数化矢量绘制，图片素材仅作为可选覆盖
        // 所有表情都从assets加载对应命名的png，没有素材则返回null（使用参数化绘制）
        val expressionName = when(type) {
            ExpressionType.IDLE -> "idle"
            ExpressionType.BLINK -> "blink"
            ExpressionType.SPEAKING -> "speaking"
            ExpressionType.THINKING -> "thinking"
            ExpressionType.RECOGNIZING -> "recognizing"
            ExpressionType.RESPONSE -> "response"
            ExpressionType.HAPPY -> "happy"
            ExpressionType.CONFUSED -> "confused"
            ExpressionType.SURPRISED -> "surprised"
            ExpressionType.SAD -> "sad"
            ExpressionType.ANGRY -> "angry"
            ExpressionType.SLEEPY -> "sleepy"
            ExpressionType.SHY -> "shy"
            ExpressionType.LOW_BATTERY -> "low_battery"
            ExpressionType.CHARGING -> "charging"
            ExpressionType.ALERT -> "alert"
            ExpressionType.SLEEPING -> "sleeping"
            ExpressionType.UPDATING -> "updating"
        }
        return loadExpressionBitmap(expressionName, targetWidth, targetHeight)
    }

    /**
     * 加载指定表情的图片素材
     * @param expressionName 表情名称：idle/speaking/thinking/recognizing/response/confused/surprised/apologetic/happy
     * @param targetWidth 目标宽度，自动采样缩放避免OOM
     * @param targetHeight 目标高度
     */
    fun loadExpressionBitmap(
        expressionName: String,
        targetWidth: Int,
        targetHeight: Int
    ): ImageBitmap? {
        if (!useImageAssets) return null

        val cacheKey = "${expressionName}_${targetWidth}x${targetHeight}"
        // 先从缓存取
        memoryCache.get(cacheKey)?.let {
            return it
        }

        return try {
            val fileName = "$ASSET_PATH$expressionName.png"
            val inputStream = context.assets.open(fileName)

            // 先获取图片尺寸，不加载进内存
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // 计算采样率，按目标尺寸缩放
            options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 表情不需要透明通道，省内存

            // 加载缩放后的图片
            val scaledStream = context.assets.open(fileName)
            val bitmap = BitmapFactory.decodeStream(scaledStream, null, options)
            scaledStream.close()

            bitmap?.let {
                val imageBitmap = it.asImageBitmap()
                // 加入缓存
                memoryCache.put(cacheKey, imageBitmap)
                Log.d(TAG, "Loaded asset: $fileName, sampleSize: ${options.inSampleSize}")
                imageBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load asset: $expressionName", e)
            null
        }
    }

    /**
     * 计算图片采样率，避免加载过大图片
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 清空缓存，释放内存
     */
    @Suppress("unused")
    fun clearCache() {
        memoryCache.evictAll()
        Log.d(TAG, "Asset cache cleared")
    }
}
