/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.face.Face
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import kotlin.math.sqrt
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * MobileFaceNet TFLite特征提取器
 *
 * 模型规格：
 * - 模型文件：mobile_face_net.tflite（INT8量化，~1.8MB）
 * - 输入尺寸：1 x 112 x 112 x 3 (RGB)
 * - 像素归一化：/255 * 2 - 1 = [-1, 1]
 * - 输出维度：1 x 192 (float32嵌入向量)
 * - 推理速度：骁龙7+ Gen2上约8-15ms（CPU 4线程）
 * - LFW准确率：99.50%
 *
 * 预处理流程：
 * 1. FaceAligner将人脸对齐到112x112标准模板
 * 2. Bitmap转ByteBuffer（RGB顺序，float32，归一化到[-1,1]）
 * 3. TFLite推理
 * 4. 输出L2归一化
 *
 * 降级策略：
 * - 模型文件不存在时自动降级到DummyFeatureExtractor（位置伪特征）
 * - GPU不兼容时自动回退CPU
 */
class TFLiteFaceEmbedder(private val context: Context) : FaceFeatureExtractor {

    companion object {
        private const val TAG = "TFLiteFaceEmbedder"
        private const val MODEL_PATH = "models/mobile_face_net.tflite"
        private const val MODEL_FALLBACK_PATH = "models/mobilefacenet.tflite"

        // 模型输入输出规格
        private const val INPUT_SIZE = FaceAligner.ALIGNED_SIZE  // 112
        private const val INPUT_CHANNELS = 3
        private const val BYTES_PER_FLOAT = 4
        private const val OUTPUT_DIM = 192  // MobileFaceNet输出192维

        // 推理线程数（4线程在骁龙7+ Gen2上最优）
        private const val NUM_THREADS = 4
        private const val MAX_POOL_SIZE = 2
    }

    override val featureDimension: Int get() = actualOutputDim

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var gpuDelegate: GpuDelegate? = null

    @Volatile
    private var isUsingGPU = false  // 是否使用 GPU 加速

    @Volatile
    private var isUsingFallback = false  // 是否使用降级特征提取器

    private var fallbackExtractor: DummyFeatureExtractor? = null

    // 输入ByteBuffer（在initialize中根据实际模型shape动态分配）
    private var inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)

    // 输出数组（在initialize中根据实际模型shape动态分配）
    private var outputArray: Array<FloatArray> = arrayOf(floatArrayOf())
    private var actualOutputDim = OUTPUT_DIM  // 实际输出维度
    private var inputBatchSize = 1  // 模型实际batch维度（Siamese模型可能为2）
    private var inputDataType: String = "FLOAT32"  // 输入数据类型名称
    private var inputIsQuantized = false  // 模型输入是否为量化类型

    // INT8量化参数（从模型量化层读取，用于正确预处理输入数据）
    private var quantInputScale = 1f
    private var quantInputZeroPoint = 0

    // 推理统计
    @Volatile
    private var totalInferenceTime = 0L
    @Volatile
    private var inferenceCount = 0

    // ========== 对齐 Bitmap 池化 ==========
    // 对齐后的 112x112 Bitmap 尺寸固定，池化可减少频繁分配/回收导致的 GC 压力
    private val alignedBitmapPool = ArrayDeque<Bitmap>(2)


    /**
     * 从池中获取一个 112x112 ARGB_8888 Bitmap
     * 池为空则新建
     */
    private fun obtainAlignedBitmap(): Bitmap {
        synchronized(alignedBitmapPool) {
            if (alignedBitmapPool.isNotEmpty()) {
                return alignedBitmapPool.removeFirst()
            }
        }
        return Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }

    /**
     * 将对齐 Bitmap 放回池中
     * 池满则直接回收
     */
    private fun recycleAlignedBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            try { bitmap.recycle() } catch (_: Exception) {}
            return
        }
        synchronized(alignedBitmapPool) {
            if (alignedBitmapPool.size < MAX_POOL_SIZE) {
                alignedBitmapPool.addLast(bitmap)
                return
            }
        }
        try { bitmap.recycle() } catch (_: Exception) {}
    }

    /**
     * 清空 Bitmap 池（release 时调用）
     */
    private fun clearAlignedBitmapPool() {
        synchronized(alignedBitmapPool) {
            while (alignedBitmapPool.isNotEmpty()) {
                try { alignedBitmapPool.removeFirst().recycle() } catch (_: Exception) {}
            }
        }
    }

    override fun isReady(): Boolean = interpreter != null || isUsingFallback

    override fun initialize(context: Context) {
        Log.i(TAG, "========== TFLiteFaceEmbedder initialize START ==========")
        Log.i(TAG, "Model path: assets/$MODEL_PATH, INPUT=$INPUT_SIZE, OUTPUT=$OUTPUT_DIM")
        try {
            // 第一步：检查assets中模型文件是否存在
            val assetList = context.assets.list("")?.toList() ?: emptyList()
            Log.i(TAG, "Assets root files: ${assetList.take(20)}")
            val modelList = context.assets.list("models")?.toList() ?: emptyList()
            Log.i(TAG, "Assets/models files: $modelList")

            // 尝试加载模型
            val modelBuffer = loadModelFile(context)
            if (modelBuffer == null) {
                Log.e(TAG, "❌ Model file NOT FOUND at assets/$MODEL_PATH, falling back to Dummy")
                initFallback(context)
                return
            }
            Log.i(TAG, "Model file loaded, size=${modelBuffer.capacity()/1024}KB")

            // 配置TFLite选项：优先尝试 GPU 加速，失败自动降级到 CPU + XNNPACK
            val options = Interpreter.Options().apply {
                // 先尝试 GPU Delegate
                var gpuEnabled = false
                try {
                    val compatList = CompatibilityList()
                    if (compatList.isDelegateSupportedOnThisDevice) {
                        val gpuOptions = compatList.bestOptionsForThisDevice
                        gpuDelegate = GpuDelegate(gpuOptions)
                        addDelegate(gpuDelegate)
                        gpuEnabled = true
                        isUsingGPU = true
                        Log.i(TAG, "GPU Delegate enabled (FP16, sustained speed)")
                    } else {
                        Log.i(TAG, "GPU Delegate NOT supported on this device, falling back to CPU")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GPU Delegate init failed: ${e.message}, falling back to CPU")
                    gpuDelegate?.close()
                    gpuDelegate = null
                    isUsingGPU = false
                }

                // CPU 配置（GPU 失败时生效）
                if (!gpuEnabled) {
                    setNumThreads(NUM_THREADS)
                    setUseXNNPACK(true)
                    Log.i(TAG, "TFLite CPU mode: threads=$NUM_THREADS, XNNPACK=true")
                }
            }

            interpreter = Interpreter(modelBuffer, options)

            // 读取模型原始输入shape（不resize，避免打乱TFLite内部tensor分配状态）
            inputBatchSize = interpreter!!.getInputTensor(0).shape()[0]
            Log.i(TAG, "Using original input shape (batch=$inputBatchSize)")

            // 打印模型输入输出tensor信息
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            val inputShape = inputTensor.shape()
            val outputShape = outputTensor.shape()
            Log.i(TAG, "Input tensor shape: ${inputShape.contentToString()}, dtype=${inputTensor.dataType()}")
            Log.i(TAG, "Output tensor shape: ${outputShape.contentToString()}, dtype=${outputTensor.dataType()}")

            // 根据实际输出维度动态分配输出数组（模型可能是192维或128维）
            // 注意：赋值给类属性 actualOutputDim，不要用 val 声明局部变量遮蔽
            actualOutputDim = outputShape.last().coerceAtLeast(1)
            val outputBatch = outputShape[0]
            outputArray = Array(outputBatch) { FloatArray(actualOutputDim) }
            Log.i(TAG, "Output array allocated: $outputBatch x $actualOutputDim floats")

            // 重新分配输入ByteBuffer（根据实际input shape和数据类型）
            val inputSize = inputShape[1]
            val inputChannels = inputShape.last()
            inputDataType = inputTensor.dataType().toString()
            inputIsQuantized = inputDataType == "UINT8" || inputDataType == "INT8"
            if (inputIsQuantized) {
                // 读取量化参数，用于将归一化后的float值正确映射为INT8/UINT8值
                val qParams = inputTensor.quantizationParams()
                quantInputScale = qParams.scale
                quantInputZeroPoint = qParams.zeroPoint
                Log.i(TAG, "Quantization params: scale=$quantInputScale, zeroPoint=$quantInputZeroPoint")
            }
            val bytesPerElement = if (inputIsQuantized) 1 else BYTES_PER_FLOAT
            val inputBufferSize = inputShape[0] * inputSize * inputSize * inputChannels * bytesPerElement
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize).order(ByteOrder.nativeOrder())
            Log.i(TAG, "Input buffer allocated: $inputBufferSize bytes (batch=${inputShape[0]}, ${inputSize}x${inputSize}x${inputChannels}, dtype=$inputDataType, quantized=$inputIsQuantized)")

            Log.i(TAG, "✅ MobileFaceNet model loaded SUCCESSFULLY! (batch=$inputBatchSize, dim=$actualOutputDim)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load TFLite model", e)
            initFallback(context)
        }
        Log.i(TAG, "========== TFLiteFaceEmbedder initialize END, ready=${isReady()} ==========")
    }

    /**
     * 初始化降级特征提取器（模型不存在时使用）
     */
    private fun initFallback(context: Context) {
        isUsingFallback = true
        fallbackExtractor = DummyFeatureExtractor().also { it.initialize(context) }
        Log.w(TAG, "⚠️ Using DummyFeatureExtractor - NOT real recognition! Place model at assets/$MODEL_PATH")
    }

    /**
     * 推理锁：TFLite interpreter 和输入/输出缓冲区不可重入，
     * 注册流程(Dispatchers.IO)和识别流程(Dispatchers.Default)可能并发调用，
     * 必须串行化保证缓冲区不被互相覆盖。
     */
    private val inferenceLock = Any()

    override fun extractFeature(
        faceBitmap: Bitmap,
        face: Face,
        srcWidth: Int,
        srcHeight: Int,
        rotation: Int,
        mirrorX: Boolean
    ): FloatArray? {
        // 降级模式：使用占位实现
        if (isUsingFallback) {
            return fallbackExtractor?.extractFeature(faceBitmap, face)
        }

        // 防止使用已回收的Bitmap导致Native崩溃
        if (faceBitmap.isRecycled || faceBitmap.width == 0 || faceBitmap.height == 0) {
            Log.w(TAG, "Input bitmap is recycled or invalid")
            return null
        }

        val tflite = interpreter ?: return null
        val startTime = System.nanoTime()

        // 从池中获取对齐用 Bitmap，减少内存分配
        val alignedBitmap = obtainAlignedBitmap()

        return try {
            // 人脸对齐在锁外执行（耗时操作，不需要互斥）
            // 传入坐标转换参数，正确处理旋转+镜像
            val aligned = FaceAligner.alignFace(faceBitmap, face, srcWidth, srcHeight, rotation, mirrorX, outBitmap = alignedBitmap)
                ?: FaceAligner.alignFaceByEyes(faceBitmap, face, srcWidth, srcHeight, rotation, mirrorX, outBitmap = alignedBitmap)

            if (aligned == null) {
                return null
            }

            val embedding = synchronized(inferenceLock) {
                // 2. 预处理：Bitmap -> ByteBuffer，归一化到[-1, 1]
                // 如果模型batch>1（Siamese模式），用相同数据填满所有batch slot
                inputBuffer.rewind()
                preprocessBitmap(alignedBitmap, inputBuffer)
                if (inputBatchSize > 1) {
                    // 复制第一个batch的数据到后续batch slot
                    val singleBatchSize = inputBuffer.capacity() / inputBatchSize
                    val firstBatch = ByteArray(singleBatchSize)
                    inputBuffer.position(0)
                    inputBuffer.get(firstBatch)
                    repeat(inputBatchSize - 1) {
                        inputBuffer.put(firstBatch)
                    }
                }
                inputBuffer.rewind()

                // 3. TFLite推理
                for (arr in outputArray) arr.fill(0f)
                tflite.run(inputBuffer, outputArray)

                // 4. 取第一个batch的输出并L2归一化
                val emb = outputArray[0].copyOf()
                l2Normalize(emb)
                emb
            }

            // 统计推理时间
            val elapsed = (System.nanoTime() - startTime) / 1_000_000  // ms
            totalInferenceTime += elapsed
            inferenceCount++
            if (inferenceCount % 50 == 0) {
                val avgMs = totalInferenceTime / inferenceCount
                Log.d(TAG, "Avg inference time: ${avgMs}ms over $inferenceCount frames")
            }

            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Feature extraction failed", e)
            null
        } finally {
            // 对齐 Bitmap 放回池中复用，减少 GC 压力
            recycleAlignedBitmap(alignedBitmap)
        }
    }

    /**
     * 预处理Bitmap到TFLite输入ByteBuffer
     *
     * 转换步骤（精确匹配模型训练时的预处理）：
     * 1. 获取112x112对齐Bitmap的像素
     * 2. RGB顺序（注意：Android Bitmap是ARGB，需要提取R/G/B通道）
     * 3. 归一化：(pixel - 127.5) / 128 → [-1, 1]（与训练代码一致）
     * 4. 写入ByteBuffer（NHWC格式，batch=1）
     */
    private fun preprocessBitmap(bitmap: Bitmap, buffer: ByteBuffer) {
        buffer.rewind()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // 模型训练时使用的归一化参数：(pixel - 127.5) / 128
        val imageMean = 127.5f
        val imageStd = 128.0f

        for (pixel in pixels) {
            // ARGB -> RGB，归一化到[-1, 1]
            val r = (((pixel shr 16) and 0xFF) - imageMean) / imageStd
            val g = (((pixel shr 8) and 0xFF) - imageMean) / imageStd
            val b = ((pixel and 0xFF) - imageMean) / imageStd
            if (inputIsQuantized) {
                // INT8/UINT8量化模型：用模型的scale和zero_point做反量化映射
                // 公式：q = round(x / scale + zero_point)，然后clamp到[0,255](UINT8)或[-128,127](INT8)
                val qr = quantizeValue(r)
                val qg = quantizeValue(g)
                val qb = quantizeValue(b)
                buffer.put(qr)
                buffer.put(qg)
                buffer.put(qb)
            } else {
                // Float32模型：直接写float
                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
            }
        }
    }

    /**
     * 将float值按量化参数映射为INT8/UINT8字节
     * q = round(x / scale + zero_point)
     */
    private fun quantizeValue(x: Float): Byte {
        val q = Math.round(x / quantInputScale + quantInputZeroPoint)
        // clamp 到 [-128, 127] (INT8) 或 [0, 255] (UINT8，此时 toByte() 会自动溢出回绕)
        val clamped = q.coerceIn(-128, 255)
        return clamped.toByte()
    }

    /**
     * L2归一化特征向量
     * 归一化后 ||v|| = 1，余弦相似度直接点积即可
     */
    private fun l2Normalize(vector: FloatArray) {
        var norm = 0f
        for (v in vector) {
            norm += v * v
        }
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }

    /**
     * 从assets加载TFLite模型文件
     */
    private fun loadModelFile(context: Context): MappedByteBuffer? {
        // 尝试多个可能的路径
        val paths = listOf(MODEL_PATH, MODEL_FALLBACK_PATH)
        for (path in paths) {
            try {
                val fileDescriptor = context.assets.openFd(path)
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val fileChannel = inputStream.channel
                val startOffset = fileDescriptor.startOffset
                val declaredLength = fileDescriptor.declaredLength
                val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                Log.i(TAG, "Model loaded from assets/$path")
                return buffer
            } catch (e: Exception) {
                Log.d(TAG, "Model not found at assets/$path: ${e.message}")
            }
        }
        return null
    }

    /**
     * 获取平均推理时间（用于性能监控）
     */
    fun getAverageInferenceMs(): Float {
        return if (inferenceCount > 0) totalInferenceTime.toFloat() / inferenceCount else 0f
    }

    override fun release() {
        try {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
            isUsingGPU = false
            fallbackExtractor?.release()
            fallbackExtractor = null
            clearAlignedBitmapPool()
            Log.i(TAG, "TFLiteFaceEmbedder released")
        } catch (e: Exception) {
            Log.e(TAG, "Release failed", e)
        }
    }
}
