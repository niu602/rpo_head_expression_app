# TFLite 模型放置说明

## 需要放置的模型文件

将 `mobile_face_net.tflite` 文件放在此目录下：
```
app/src/main/assets/models/mobile_face_net.tflite
```

## 模型规格要求

- **Backbone**: MobileFaceNet (Depthwise Separable CNN)
- **输入尺寸**: 1 x 112 x 112 x 3 (RGB float32)
- **像素归一化**: pixel / 255.0 * 2.0 - 1.0 → [-1, 1]
- **输出维度**: 1 x 192 (float32 嵌入向量)
- **量化方式**: Full INT8 量化（推荐，~1.8MB）或 float32（~4.2MB）
- **训练损失**: ArcFace Loss (margin=0.5, scale=64)
- **LFW 准确率**: ≥99.3%

## 推荐模型来源

### 方案1：从开源项目下载（推荐）

1. **peteryuX/face-recognition-using-mobilefacenet**
   - GitHub: https://github.com/peteryuX/face-recognition-using-mobilefacenet
   - 模型路径: `output_kface/tflite_model/model.tflite`
   - 重命名为 `mobile_face_net.tflite` 放到此目录

2. **sirius-ai/MobileFaceNet_TF**
   - GitHub: https://github.com/sirius-ai/MobileFaceNet_TF
   - 预训练模型: `pretrained_models/MobileFaceNet_TFLite/MobileFaceNet.tflite`

3. **InsightFace 预训练模型转换**
   - 使用 InsightFace 的 MobileFaceNet 预训练权重
   - PyTorch → ONNX → TFLite (INT8 量化)

### 方案2：自己训练转换

如果需要针对特定场景优化，可以自己训练：
1. 使用 InsightFace 框架训练 MobileFaceNet
2. 导出为 ONNX 格式
3. 使用 TensorFlow Lite Converter 转换为 TFLite
4. 进行 INT8 量化（推荐使用 representative dataset）

## 降级策略

**如果此目录没有模型文件，APP会自动降级到DummyFeatureExtractor（基于位置的伪特征）**，
框架可以正常运行但**没有真实人脸识别能力**。请务必放置正确的模型文件。

## 验证模型是否正确加载

APP启动后查看logcat，过滤标签 `TFLiteFaceEmbedder`：
- ✅ 成功: `MobileFaceNet model loaded successfully, outputDim=192, threads=4`
- ⚠️ 降级: `Model file not found, falling back to DummyFeatureExtractor`
