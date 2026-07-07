# RPO 机器人头部表情显示 APP

Roboparty夏令营RPO机器人头部表情显示系统，运行在红米Note 12 Turbo上。

## 快速开始

### 环境要求

- Android Studio Hedgehog | 2023.1.1 或 IntelliJ IDEA 2023.3+
- JDK 17
- Android SDK 34
- Gradle 8.2
- Kotlin 1.9.20
- 测试设备：Redmi Note 12 Turbo (MIUI 14+, Android 13+)

### 导入项目

1. 使用Android Studio/IDEA打开本项目根目录
2. 等待Gradle同步完成
3. 连接红米Note 12 Turbo手机，开启USB调试
4. 点击Run按钮安装运行

## 测试方法

### 1. 基础功能测试（无需主控）

**开启调试面板**：
修改 `MainActivity.kt` 中 `showDebug = true`，即可显示测试按钮。

测试项：
- ✅ 启动后默认显示待机表情，有呼吸微动效果
- ✅ 待机状态下每3-8秒自动眨眼一次
- ✅ 点击"待机"按钮：回到待机状态
- ✅ 点击"说话"按钮：显示说话表情，嘴部张合动画
- ✅ 点击"思考"按钮：显示思考表情，省略号跳动
- ✅ 点击"识别中"按钮：显示扫描框动画
- ✅ 点击"应答"按钮：显示开心表情+对勾，1秒后自动回到待机
- ✅ 全屏显示，无状态栏导航栏
- ✅ 屏幕保持常亮

### 2. 通信功能测试

**使用网络调试助手测试**：

1. 手机和电脑连接同一WiFi
2. 电脑打开NetAssist等网络调试工具，创建TCP Server，端口8899
3. 修改 `Constants.kt` 中 `DEFAULT_MASTER_IP` 为电脑IP
4. 运行APP，观察连接状态
5. 发送以下JSON指令测试：

测试指令1（切换说话表情）：
```json
{"cmd":"expression","type":"speaking","force":true,"timestamp":0,"seq":1,"source":"test"}
```

测试指令2（播放Demo序列）：
```json
{"cmd":"sequence","timestamp":0,"seq":2,"source":"test","data":{"types":["recognizing","response","thinking","speaking","response"]}}
```

测试指令3（回到待机）：
```json
{"cmd":"clear","timestamp":0,"seq":3,"source":"test"}
```

预期结果：
- APP收到指令后100ms内切换表情
- 每2秒收到APP的ping心跳
- 可收到APP的状态上报

### 3. 亮度自适应测试

1. 用手遮挡手机顶部光线传感器
2. 观察屏幕亮度逐渐变暗
3. 用手电筒照射传感器
4. 观察屏幕亮度逐渐变亮
5. 亮度变化平滑无闪烁

### 4. 性能测试

使用Android Studio Profiler测试：
- 帧率：稳定60fps
- 内存：<50MB
- CPU：空闲时<5%
- 表情切换延迟：<100ms

## 与roboto_origin主控联调

### 网络配置

1. 主控和手机连接同一局域网
2. 主控启动TCP Server监听8899端口
3. 修改APP中 `DEFAULT_MASTER_IP` 为主控IP
4. 启动APP，自动连接主控

### 模块联动测试

**ASR语音模块联动**：
- 语音开始识别时发送 `{"cmd":"expression","type":"thinking"}`
- 开始播报时发送 `{"cmd":"expression","type":"speaking"}`
- 播报结束发送 `{"cmd":"expression","type":"response"}`

**人脸识别模块联动**：
- 开始识别发送 `{"cmd":"expression","type":"recognizing"}`
- 识别成功发送 `{"cmd":"expression","type":"response"}`

**手柄Demo触发**：
- 手柄按键按下发送sequence指令播放演示流程

## 项目结构

```
RpoRobotExpression/
├── app/
│   ├── build.gradle.kts          # 模块配置
│   ├── proguard-rules.pro        # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 清单文件
│       ├── java/com/roboparty/rpoexpression/
│       │   ├── expression/       # 表情管理模块
│       │   │   ├── ExpressionType.kt      # 表情类型枚举
│       │   │   ├── ExpressionState.kt     # 表情状态数据
│       │   │   └── ExpressionManager.kt   # 表情状态机
│       │   ├── animation/        # 动画渲染模块
│       │   │   ├── ExpressionRenderer.kt  # Canvas绘制
│       │   │   └── ExpressionCanvas.kt    # Compose组件
│       │   ├── communication/    # 通信模块
│       │   │   ├── ControlCommand.kt      # 指令数据类
│       │   │   └── CommunicationManager.kt # TCP通信
│       │   ├── sensor/           # 传感器模块
│       │   │   └── BrightnessManager.kt   # 亮度自适应
│       │   ├── ui/theme/         # UI主题
│       │   ├── util/             # 工具类
│       │   ├── MainActivity.kt   # 主界面
│       │   └── RpoExpressionApp.kt # Application
│       ├── res/                  # 资源文件
│       └── assets/expressions/   # 表情素材目录
├── docs/
│   ├── ARCHITECTURE.md           # 架构设计文档
│   ├── PROTOCOL.md               # 通信协议规范
│   └── ASSET_SPEC.md             # 素材设计规范
├── build.gradle.kts              # 项目配置
└── settings.gradle.kts           # 项目设置
```

## 部署说明

### Release打包

1. Build → Generate Signed Bundle/APK
2. 选择APK
3. 创建或选择签名密钥
4. 选择release构建
5. 生成签名APK安装到手机

### 开机自启设置

1. 安装APK后打开一次
2. 设置 → 应用设置 → 自启动管理 → 允许本APP自启动
3. 设置 → 电池 → 无限制（防止后台被杀）
4. 将APP加入后台清理白名单

### 固定竖屏

APP已配置固定竖屏，无需额外设置。安装后将手机固定在机器人头部即可。

## 扩展开发

### 添加新表情

1. 在 `ExpressionType` 枚举中添加新类型
2. 在 `ExpressionRenderer` 中添加对应绘制方法
3. 在 `ExpressionType.fromCommand()` 中添加指令映射

### 切换到位图帧动画

1. 将PNG序列放入assets/expressions对应目录
2. 实现AssetManager加载帧序列
3. 替换ExpressionRenderer中的矢量绘制为图片绘制

### USB串口通信

实现 `CommunicationManager.SerialCommunication` 接口，使用UsbManager连接主控串口。

## 许可证

GPL-3.0-only
