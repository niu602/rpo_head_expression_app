# APP部署与调试手册
## 版本：v1.0
本手册介绍RPO表情显示APP的编译、部署、调试方法，以及常见问题排查方案。

---
## 1. 环境准备
### 1.1 开发环境要求
| 软件 | 版本要求 | 下载地址 |
|------|----------|----------|
| Android Studio | Hedgehog | 2023.1.1 或更高版本 | https://developer.android.com/studio |
| JDK | 17 | Android Studio内置，无需单独安装 |
| Gradle | 8.5 | 项目内置Wrapper，无需单独安装 |
| Android SDK | API 34 (Android 14) | 通过Android Studio SDK Manager安装 |

### 1.2 测试设备要求
- 红米Note 12 Turbo（推荐），系统版本Android 13及以上
- 开启USB调试：设置 > 我的设备 > 全部参数 > 连续点击MIUI版本开启开发者选项，返回设置 > 更多设置 > 开发者选项 > 开启USB调试、USB安装
- （可选）蓝牙/OTG手柄，用于测试Demo触发

---
## 2. 编译部署步骤
### 2.1 导入项目
1. 打开Android Studio，选择`File > Open`，选择项目根目录
2. 等待Gradle自动同步，首次同步会下载依赖，需要联网，耗时约3-5分钟
3. 同步完成后，检查Gradle JDK配置：`File > Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`，选择jbr-17（Android Studio内置JDK17）

### 2.2 配置参数（可选）
根据实际情况修改配置：
1. **主控IP/端口**：打开`app/src/main/java/com/roboparty/rpoexpression/util/Constants.kt`，修改`DEFAULT_MASTER_IP`为主控板实际IP，`DEFAULT_MASTER_PORT`为端口（默认8888）
2. **调试模式**：打开`app/src/main/java/com/roboparty/rpoexpression/MainActivity.kt`，找到DebugPanel中的`val showDebug = false`，改为`true`即可开启调试面板，显示测试按钮
3. **自动亮度**：默认开启，如需关闭可修改`BrightnessManager.kt`中的逻辑

### 2.3 安装到设备
1. 用USB线连接手机和电脑，手机上允许USB调试授权
2. Android Studio顶部设备选择器中选择连接的红米Note 12 Turbo
3. 点击顶部运行按钮（绿色三角形），APP会自动编译并安装到手机
4. 安装完成后手机桌面会出现「RPO表情」图标

### 2.4 生成Release APK（发布用）
1. 点击`Build > Generate Signed Bundle/APK`
2. 选择APK，点击Next
3. 创建或选择已有签名密钥，填写密钥信息，点击Next
4. 选择release构建变体，点击Finish
5. 生成的APK位于`app/release/app-release.apk`，可直接安装到设备

---
## 3. 调试方法
### 3.1 可视化调试面板
开启调试模式（showDebug=true）后，APP屏幕底部会显示测试面板，所有功能无需写代码即可测试：
| 分组 | 按钮 | 功能 |
|------|------|------|
| Demo控制 | 播放Demo | 触发完整演示序列，和手柄触发效果一致 |
| | 停止Demo | 立即停止Demo，回到待机 |
| 基础表情 | 待机 | 强制回到待机表情 |
| | 眨眼/思考/识别中/应答/说话 | 切换对应表情，走优先级判断，低优先级无法抢占高优先级 |
| ASR测试 | 开始识别/识别中/识别完成/识别错误 | 模拟ASR各状态，验证联动逻辑 |
| TTS测试 | 开始播报/播报完成/播报错误 | 模拟TTS各状态，验证联动逻辑 |
| 人脸识别测试 | 开始识别/识别成功/识别失败 | 模拟人脸识别各状态，验证联动逻辑 |

### 3.2 手柄调试
1. 手柄通过蓝牙或OTG连接手机
2. 按手柄X/A键：触发完整Demo序列
3. 按手柄B键：停止Demo，回到待机
4. 验证表情顺序和时长：思考(1.5s)→识别中(2s)→应答(1s)→说话(3s)→回待机

### 3.3 通信调试
1. 电脑和手机连接同一WiFi
2. 运行项目根目录下的模拟服务端：
   ```bash
   python tools/mock_server.py
   ```
3. 修改APP中主控IP为电脑局域网IP，启动APP
4. 模拟服务端会显示连接成功，在命令行输入指令即可控制表情：
   - 输入`idle`/`blink`/`speaking`/`thinking`/`recognizing`/`response`：切换对应表情
   - 输入`demo`：播放演示序列
   - 输入`clear`：回到待机
   - 输入`brightness 0.5`：设置亮度为50%
5. 断开网络测试自动重连：断开手机WiFi，APP会自动尝试重连，恢复网络后自动连接成功

### 3.4 传感器调试
1. 亮度测试：用手捂住手机顶部光线传感器，屏幕亮度会自动降低；用手电筒照射，亮度自动升高
2. 后台测试：按Home键将APP切到后台，传感器停止工作，亮度恢复系统值；切回APP后自动恢复

### 3.5 日志调试
Android Studio Logcat中过滤以下TAG查看日志：
- `ExpressionManager`：表情状态切换、优先级判断、自动回落日志
- `CommunicationManager`：连接状态、收发指令、心跳日志
- `BrightnessManager`：亮度变化日志
- `AssetLoader`：素材加载日志
- `DemoSequence`：Demo序列、动作回调日志

---
## 4. 常见问题排查
### 4.1 编译错误
1. **Gradle同步失败**：检查网络，配置国内镜像源，或者使用本地Gradle
2. **JDK版本错误**：确认Gradle JDK选择的是JDK17，不要使用JDK1.8或JDK21
3. **TextUnit编译错误**：检查是否使用了`TextUnit(12f)`，必须使用`12.sp`写法
4. **资源找不到错误**：执行`Build > Clean Project`，然后`Build > Rebuild Project`

### 4.2 运行时问题
1. **APP启动闪退**：检查minSdk是否为33，设备系统版本是否Android13及以上；检查是否有NullPointerException，参考Logcat日志
2. **连接不上主控**：检查手机和主控是否在同一局域网，IP是否正确，主控端口是否开放，防火墙是否拦截
3. **表情不自动回落**：检查是否是临时表情（应答/眨眼），临时表情才会自动回落；检查Logcat中是否有回落日志
4. **素材不显示**：检查素材命名是否正确，是否放在`assets/expressions/`目录下，文件格式是否为png；查看Logcat中AssetLoader的日志，是否加载成功
5. **手柄按键没反应**：检查手柄是否连接成功，是否支持Android标准按键映射；可在系统设置中测试手柄按键
6. **亮度不自动调节**：检查APP是否有传感器权限，光线传感器是否被遮挡，是否手动设置了亮度

### 4.3 性能问题
1. **卡顿**：检查是否开启了GPU渲染模式分析，正常运行帧率应该稳定60fps
2. **内存过高**：检查是否加载了过大的素材，APP会自动采样缩放，正常内存占用不超过50MB
3. **耗电快**：APP运行时屏幕常亮，属于正常现象，固定到机器人上建议接常电

---
## 5. 整机部署注意事项
1. 手机固定到机器人头部时，注意光线传感器不要被遮挡，否则自动亮度会失效
2. 建议关闭手机自动旋转、通知栏弹窗、自动更新等影响显示的功能
3. 设置APP为自启动，避免被系统杀后台
4. 连接电源保持常亮，避免手机自动锁屏
5. 首次安装后授予所有权限，允许后台运行
