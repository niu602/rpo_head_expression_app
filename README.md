<div align="center">
  <a href="./README.md">English</a> | 
  <a href="./README.zh-CN.md">中文</a>
</div>

-----
# RPO Robot Expression Display Module
> Facial expression display APP for Roboparty Camp roboto_origin robot, optimized for Redmi Note 12 Turbo

## 📋 Overview
This project is the facial expression display module for RPO robot, running on Redmi Note 12 Turbo. It communicates with the main controller via TCP protocol, supports multi-module state linkage including ASR, TTS and face recognition, and synchronizes expressions with head motor actions to provide natural and smooth human-robot interaction experience.

## ✨ Features
- 🎭 **6 Basic Expressions**: Idle, Blink, Speaking, Thinking, Recognizing, Response with priority scheduling
- 🎬 **Smooth Animation**: 60fps hardware-accelerated rendering, 100ms smooth transition, automatic blinking micro-movements
- 📡 **TCP Communication**: Auto-reconnect, heartbeat keep-alive, JSON format command support
- 💡 **Auto Brightness**: Ambient light sensor adjusts screen brightness in real-time for different lighting conditions
- 🎮 **Gamepad Support**: Bluetooth/OTG gamepad triggers demo flow, synchronized with motor actions
- 🖼️ **Asset Extensible**: Custom png expression assets supported, auto-replaces default vector rendering
- 🔌 **Multi-module Integration**: Native support for ASR, TTS and face recognition state auto-switching
- ⚡ **Low Latency**: Command response <100ms, zero-delay synchronization between expressions and actions

## 🛠️ Deployment
### Requirements
- Android Studio Hedgehog | 2023.1.1 or higher
- JDK 17
- Gradle 8.5
- Min SDK: Android 13 (API 33)
- Target device: Redmi Note 12 Turbo (compatible with other Android 13+ devices)

### Build Instructions
1. Clone the repository
2. Open the project with Android Studio, wait for Gradle sync to complete
3. Connect Redmi Note 12 Turbo with USB debugging enabled
4. Click Run button to install APP to device
5. (Optional) Generate Release APK: `Build > Generate Signed Bundle/APK` with your signing key

### Configuration
- Master IP/Port: Modify `app/src/main/java/com/roboparty/rpoexpression/util/Constants.kt`
- Demo sequence duration: Modify wait time for each expression in `DemoSequenceManager.kt`
- Brightness threshold: Modify dark/bright threshold in `BrightnessManager.kt`
- Debug mode: Set `showDebug = true` in DebugPanel of `MainActivity.kt` to show test buttons

## 📖 Usage
### Basic Usage
1. Mount the phone to robot head, connect power to keep screen on
2. Launch APP, it will enter fullscreen expression mode and connect to master automatically
3. Auto blinks randomly in idle state, adjusts brightness automatically with ambient light
4. Press X/A button on connected gamepad to trigger full demo flow, B button to stop

### Replace Expression Assets
Put 6 expression png files into `app/src/main/assets/expressions/` directory following naming convention, rebuild to replace default vector rendering:
- `idle.png`: Idle expression
- `blink.png`: Blink expression
- `speaking.png`: Speaking expression
- `thinking.png`: Thinking expression
- `recognizing.png`: Recognizing expression
- `response.png`: Response/success expression

See [Expression Asset Design Specification.md](./表情素材设计规范.md) for asset design rules.

### Module Integration
Other modules only need to call static methods of `ModuleStateManager` to switch expressions automatically, no need to care about state machine logic:
```kotlin
// ASR Integration
ModuleStateManager.onAsrStartListening()  // Start listening
ModuleStateManager.onAsrProcessing()      // Processing
ModuleStateManager.onAsrFinish()          // Finish
ModuleStateManager.onAsrError()           // Error

// TTS Integration
ModuleStateManager.onTtsStartSpeaking()   // Start speaking
ModuleStateManager.onTtsFinishSpeaking()  // Finish speaking

// Face Recognition Integration
ModuleStateManager.onFaceDetectStart()    // Start detection
ModuleStateManager.onFaceDetectSuccess()  // Detection success
ModuleStateManager.onFaceDetectFailed()   // Detection failed
```

### Motor Action Synchronization
Register demo action listener to synchronize expressions with head motor actions:
```kotlin
demoSequenceManager.setListener(object : DemoSequenceManager.DemoActionListener {
    override fun onDemoStart() { /* Motor reset */ }
    override fun onThinkingAction() { /* Motor to thinking position */ }
    override fun onRecognizingAction() { /* Motor to scanning position */ }
    override fun onResponseAction() { /* Motor nod for response */ }
    override fun onSpeakingAction() { /* Motor speaking action */ }
    override fun onDemoFinish() { /* Motor back to idle position */ }
})
```

### TCP Command Control
Main controller can send JSON commands via TCP connection to control expressions. See [Communication Interface Specification.md](./roboto_origin表情模块通信接口规范.md) for command format.

## 🧪 Testing
- Unit tests: `app/src/test/` directory, includes state machine logic tests
- Instrumented tests: `app/src/androidTest/` directory, includes multi-module integration, demo and sensor tests
- Manual test: Enable debug mode to use test buttons at bottom of screen, or refer to [Deployment and Debug Manual.md](./APP部署与调试手册.md)

## 📄 Documentation
- [Expression Asset Design Specification.md](./表情素材设计规范.md)
- [Communication Interface Specification.md](./roboto_origin表情模块通信接口规范.md)
- [Deployment and Debug Manual.md](./APP部署与调试手册.md)
- [Project Delivery Note.md](./项目交付说明.md)

## 📄 License
GPL-3.0-only
