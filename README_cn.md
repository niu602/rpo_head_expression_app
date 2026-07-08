# RPO 机器人表情显示模块
> Roboparty Camp 机器人表情显示APP，适配红米Note 12 Turbo，为roboto_origin机器人提供面部表情显示能力

## 📋 项目介绍
本项目是RPO机器人的面部表情显示模块，运行在红米Note 12 Turbo手机上，通过TCP协议与主控板通信，支持语音识别、语音播报、人脸识别等多模块状态联动，配合头部电机实现表情与动作同步，提供自然流畅的人机交互体验。

## ✨ 功能特性
- 🎭 **6类基础表情**：待机、眨眼、说话、思考、识别中、应答，支持优先级调度
- 🎬 **流畅动画**：60fps硬件加速渲染，100ms平滑过渡，自动眨眼微动
- 📡 **TCP通信**：自动重连、心跳保活，支持JSON格式指令控制
- 💡 **自动亮度**：光线传感器实时调节屏幕亮度，适应不同环境光
- 🎮 **手柄控制**：支持蓝牙/OTG手柄触发演示流程，配合电机动作同步
- 🖼️ **素材扩展**：支持自定义png表情素材，自动加载替换默认矢量绘制
- 🔌 **多模块联动**：原生支持ASR语音识别、TTS语音播报、人脸识别状态自动切换
- ⚡ **低延迟**：指令响应延迟<100ms，表情与动作零延迟同步

## 🛠️ 部署方法
### 环境要求
- Android Studio Hedgehog | 2023.1.1 或更高版本
- JDK 17
- Gradle 8.5
- 最小SDK版本：Android 13 (API 33)
- 目标设备：红米Note 12 Turbo（其他Android 13+设备可兼容）

### 编译步骤
1. 克隆项目到本地
2. 使用Android Studio打开项目，等待Gradle同步完成
3. 连接红米Note 12 Turbo，开启USB调试
4. 点击运行按钮，APP将自动安装到设备
5. （可选）生成Release APK：`Build > Generate Signed Bundle/APK`，使用签名密钥打包

### 配置修改
- 主控IP/端口：修改 `app/src/main/java/com/roboparty/rpoexpression/util/Constants.kt`
- 演示序列时长：修改 `DemoSequenceManager.kt` 中对应表情等待时间
- 亮度阈值：修改 `BrightnessManager.kt` 中暗光/亮光阈值
- 调试模式：将 `MainActivity.kt` 中DebugPanel的`showDebug`设为`true`，显示测试按钮

## 📖 使用说明
### 基础使用
1. 将手机固定到机器人头部，连接电源保持常亮
2. 启动APP，自动进入全屏表情显示，默认连接主控
3. 待机状态下自动随机眨眼，随环境光自动调节亮度
4. 连接手柄后按X/A键触发完整演示流程，B键停止

### 替换表情素材
将6张表情png按命名规范放入 `app/src/main/assets/expressions/` 目录，重新编译即可自动替换默认矢量绘制：
- `idle.png`：待机表情
- `blink.png`：眨眼表情
- `speaking.png`：说话表情
- `thinking.png`：思考表情
- `recognizing.png`：识别中表情
- `response.png`：应答/成功表情

素材设计规范请参考《表情素材设计规范.md》。

### 模块对接
其他模块只需要调用`ModuleStateManager`的静态方法即可自动切换表情，无需关心状态机逻辑：
```kotlin
// ASR语音识别对接
ModuleStateManager.onAsrStartListening()  // 开始识别
ModuleStateManager.onAsrProcessing()      // 识别中
ModuleStateManager.onAsrFinish()          // 识别完成
ModuleStateManager.onAsrError()           // 识别错误

// TTS语音播报对接
ModuleStateManager.onTtsStartSpeaking()   // 开始播报
ModuleStateManager.onTtsFinishSpeaking()  // 播报完成

// 人脸识别对接
ModuleStateManager.onFaceDetectStart()    // 开始识别
ModuleStateManager.onFaceDetectSuccess()  // 识别成功
ModuleStateManager.onFaceDetectFailed()   // 识别失败
```

### 电机动作同步
注册Demo动作监听器，实现表情与头部电机动作同步：
```kotlin
demoSequenceManager.setListener(object : DemoSequenceManager.DemoActionListener {
    override fun onDemoStart() { /* 电机复位 */ }
    override fun onThinkingAction() { /* 电机低头思考位 */ }
    override fun onRecognizingAction() { /* 电机抬头扫描位 */ }
    override fun onResponseAction() { /* 电机点头应答 */ }
    override fun onSpeakingAction() { /* 电机说话动作 */ }
    override fun onDemoFinish() { /* 电机回待机位 */ }
})
```

### TCP指令控制
主控通过TCP连接发送JSON指令即可控制表情，指令格式参考《roboto_origin表情模块通信接口规范.md》。

## 🧪 测试
- 单元测试：`app/src/test/` 目录，包含状态机逻辑测试
- 仪器化测试：`app/src/androidTest/` 目录，包含多模块联动、Demo、传感器测试
- 手动测试：开启调试模式后使用屏幕底部测试按钮，或参考《APP部署与调试手册.md》

## 📄 相关文档
- [表情素材设计规范.md](./表情素材设计规范.md)
- [roboto_origin表情模块通信接口规范.md](./roboto_origin表情模块通信接口规范.md)
- [APP部署与调试手册.md](./APP部署与调试手册.md)
- [项目交付说明.md](./项目交付说明.md)

## 📄 开源协议
GPL-3.0-only
