# roboto_origin 表情模块通信接口规范

## 版本：v2.0

本规范规定了 roboto_origin 主控板（RDK）与表情显示模块（手机 APP）之间的 TCP 通信协议，所有指令均采用 JSON 格式，按行传输，兼容 UTF-8 编码。

---

## 1. 连接基础

| 项 | 参数 | 说明 |
|----|------|------|
| 传输协议 | TCP | 面向连接，可靠传输 |
| 默认端口 | 8899 | 主控监听端口，可通过 Constants.kt 修改 |
| 连接方向 | APP 作为客户端主动连接主控 | APP 启动后自动连接主控 IP，支持断线自动重连 |
| 字符编码 | UTF-8 | 所有 JSON 内容使用 UTF-8 编码 |
| 分隔符 | `\n` | 每条指令以换行符结尾，解决 TCP 粘包问题 |
| 心跳间隔 | 3秒 | APP 每 3 秒发送心跳包 |
| 超时时间 | 5秒 | 5 秒未收到心跳响应则判定断开，自动重连 |
| 重连间隔 | 3秒 | 断开后每 3 秒尝试重连一次 |
| TCP_NODELAY | 开启 | 禁用 Nagle 算法，保证指令低延迟 |

---

## 2. 通用格式

所有指令均为扁平化 JSON 对象，通用字段：

```json
{
  "cmd": "expression",
  "type": "speaking",
  "duration": 1000,
  "force": false,
  "timestamp": 1234567890,
  "seq": 1,
  "source": "roboto_origin"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `cmd` | string | ✅ | 指令类型 |
| `type` | string | - | 子类型，如表情类型、风格类型等 |
| `duration` | number | - | 持续时长（毫秒），用于临时表情 |
| `force` | boolean | - | 是否强制切换，默认 false |
| `data` | object | - | 扩展参数字段，部分指令使用 |
| `timestamp` | number | - | 发送时间戳（毫秒） |
| `seq` | number | - | 序列号，递增 |
| `source` | string | - | 指令来源标识 |

### 2.1 ACK 响应

APP 收到每条有效指令后，会回复 ACK 响应：

```json
{
  "cmd": "ack",
  "code": 0,
  "msg": "ok",
  "seq": 1
}
```

- `code`：数字，0 表示成功，非 0 表示错误（详见第 8 节错误码）
- `msg`：字符串，错误信息描述

---

## 3. 心跳机制

### 3.1 APP → 主控 心跳包

APP 每 3 秒主动发送心跳包：

```json
{
  "cmd": "ping",
  "timestamp": 1234567890,
  "seq": 10,
  "source": "expression_app"
}
```

### 3.2 主控 → APP 心跳响应

主控收到 ping 后需回复 pong：

```json
{
  "cmd": "pong",
  "timestamp": 1234567890,
  "seq": 10
}
```

APP 超过 5 秒未收到 pong 则判定连接断开，自动重连。

---

## 4. 表情控制指令（主控 → APP）

### 4.1 切换表情

```json
{
  "cmd": "expression",
  "type": "idle",
  "duration": 0,
  "force": false
}
```

**参数说明：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `type` | string | 表情类型，可选值见 4.1.1 表情列表 |
| `duration` | number | 可选，持续时间（毫秒），0 表示永久。设置后该表情为临时表情，到期自动回落到待机 |
| `force` | boolean | 可选，是否强制切换，默认 false。true 时忽略优先级直接切换 |

#### 4.1.1 完整表情列表

| 指令值 | 表情名称 | 优先级 | 自动回落 | 说明 |
|--------|---------|--------|---------|------|
| `idle` | 待机 | 0 | 否 | 默认基础状态 |
| `blink` | 眨眼 | 0 | 是 | 自动随机眨眼，无需手动调用 |
| `sleeping` | 休眠 | 0 | 否 | 闭眼休眠状态 |
| `thinking` | 思考 | 1 | 否 | LLM 生成回复时 |
| `sleepy` | 困倦 | 1 | 否 | 低电量/疲劳状态 |
| `low_battery` | 低电量 | 1 | 否 | 电量不足提示 |
| `charging` | 充电中 | 1 | 否 | 充电状态 |
| `recognizing` | 识别中 | 2 | 否 | ASR 语音识别中 |
| `confused` | 疑惑 | 3 | 是 | 没听清/没听懂 |
| `response` | 应答 | 4 | 是 | 检测到人脸/唤醒成功反馈 |
| `happy` | 开心 | 4 | 是 | 正面情绪 |
| `surprised` | 惊讶 | 4 | 是 | 意外/震惊 |
| `sad` | 难过 | 4 | 是 | 负面情绪 |
| `angry` | 生气 | 4 | 是 | 愤怒情绪 |
| `shy` | 害羞 | 4 | 是 | 触摸脸颊触发 |
| `speaking` | 说话 | 5 | 否 | TTS 播报中 |
| `alert` | 告警 | 6 | 否 | 异常/警告 |
| `updating` | 升级中 | 6 | 否 | OTA 升级状态 |

#### 4.1.2 优先级规则

优先级从低到高：`0 < 1 < 2 < 3 < 4 < 5 < 6`

- 高优先级表情可以抢占低优先级表情
- 同优先级新表情覆盖旧表情
- 低优先级请求默认被拒绝，`force=true` 时可强制切换
- 自动回落表情到期后，回落到切换前的低优先级基础表情

### 4.2 播放表情序列

```json
{
  "cmd": "sequence",
  "data": {
    "types": ["thinking", "recognizing", "response", "speaking", "idle"]
  }
}
```

按顺序依次播放一组表情，每个表情使用默认时长。

### 4.3 播放标准 Demo

```json
{
  "cmd": "play_demo"
}
```

触发内置标准演示流程：思考(1.5s) → 识别中(2s) → 应答(1s) → 说话(3s) → 回待机。
同时触发 `DemoActionListener` 回调，可用于同步头部电机动作。

### 4.4 停止/清屏

```json
{
  "cmd": "clear"
}
```

立即停止当前所有 Demo、临时表情，强制回到待机状态。

---

## 5. 渲染风格控制（主控 → APP）

### 5.1 切换渲染风格

```json
{
  "cmd": "set_style",
  "data": {
    "style": "cartoon"
  }
}
```

| 风格值 | 名称 | 说明 |
|--------|------|------|
| `cartoon` / `0` | 卡通圆眼 | 默认风格，圆润可爱 |
| `cyberpunk` / `cyber` / `1` | 机械赛博 | 科技感机械眼，发光线条 |
| `minimal` / `line` / `2` | 极简线条 | 简约线条风格 |
| `pixel` / `8bit` / `3` | 像素 8bit | 复古像素风格 |

---

## 6. 表情强度控制（主控 → APP）

### 6.1 设置全局表情强度

```json
{
  "cmd": "set_intensity",
  "data": {
    "intensity": 80
  }
}
```

- `intensity`：0~100，默认 100
- 控制眼睑开合幅度、瞳孔大小等参数的整体缩放
- 设置后对当前表情和后续所有表情生效

---

## 7. 硬件控制指令（主控 → APP）

### 7.1 设置屏幕亮度

```json
{
  "cmd": "brightness",
  "data": {
    "value": 0.8
  }
}
```

- `value`：0.0 ~ 1.0，0 为最暗，1 为最亮
- 手动设置后关闭自动亮度调节，直到 APP 重启

---

## 8. 状态查询与上报

### 8.1 查询当前状态（主控 → APP）

```json
{
  "cmd": "status"
}
```

APP 回复当前设备状态：

```json
{
  "cmd": "status_report",
  "device": "redmi_note_12_turbo",
  "status": "running",
  "current_expression": "idle",
  "current_style": "cartoon",
  "brightness": 0.8,
  "battery_level": 85,
  "timestamp": 1234567890
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `device` | string | 设备型号标识 |
| `status` | string | 运行状态：running / sleeping / error |
| `current_expression` | string | 当前表情类型 |
| `current_style` | string | 当前渲染风格 |
| `brightness` | number | 当前屏幕亮度 0~1 |
| `battery_level` | number | 电量百分比 0~100 |

### 8.2 事件主动上报（APP → 主控，推荐接口）

APP 内部状态变化时可主动上报事件，主控收到后可同步驱动电机、灯光等外设。

#### 表情变化事件

```json
{
  "cmd": "event_expression_changed",
  "from": "idle",
  "to": "thinking",
  "timestamp": 1234567890
}
```

#### 人脸检测事件

```json
{
  "cmd": "event_face_detected",
  "status": "detected",
  "face_count": 1,
  "gaze_x": 0.2,
  "gaze_y": -0.1,
  "timestamp": 1234567890
}
```

- `status`：`detected` / `lost`
- `gaze_x` / `gaze_y`：人脸相对屏幕中心的偏移量（-1 ~ 1），可用于头部跟随

#### 语音状态事件

```json
{
  "cmd": "event_voice_state",
  "state": "listening",
  "timestamp": 1234567890
}
```

`state` 可选值：
- `wake_up_listening`：唤醒词监听中
- `listening`：ASR 识别中
- `thinking`：LLM 思考中
- `speaking`：TTS 播报中
- `barge_in`：用户打断播报
- `idle`：空闲

#### 头部姿态事件（IMU 检测）

```json
{
  "cmd": "event_head_motion",
  "motion": "nod",
  "timestamp": 1234567890
}
```

`motion` 可选值：
- `nod`：点头
- `shake`：摇头
- `look_up`：抬头仰视
- `look_down`：低头俯视
- `shake_hard`：剧烈晃动

---

## 9. 错误码定义

| code | 说明 |
|------|------|
| 0 | 成功 |
| 400 | 指令格式错误，JSON 解析失败 |
| 404 | 未知指令类型 |
| 403 | 优先级不足，无法抢占当前高优先级表情（force=false 时） |
| 405 | 参数无效，如表情类型不存在、风格不存在 |
| 500 | 内部错误 |

---

## 10. 测试方法

1. 确保手机和电脑在同一局域网
2. 电脑端启动 TCP 服务端监听 8899 端口（模拟主控）
3. 修改 APP 中主控 IP 为电脑局域网 IP，启动 APP
4. 发送测试指令：
   - `{"cmd":"expression","type":"happy","duration":1500}`：切换开心表情
   - `{"cmd":"set_style","data":{"style":"cyberpunk"}}`：切换赛博风格
   - `{"cmd":"play_demo"}`：播放演示序列
   - `{"cmd":"clear"}`：回到待机
   - `{"cmd":"status"}`：查询当前状态
5. 观察 APP 表情变化，验证心跳保活和自动重连

---

## 11. 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-08 | 初版发布，包含基础表情控制、心跳、Demo、亮度控制 |
| v2.0 | 2026-07-16 | 重大更新：<br>1. 修正端口为 8899，明确 APP 为客户端连接模式<br>2. 指令格式改为扁平化结构，与代码对齐<br>3. 补全全部 18 种表情及 6 级优先级体系<br>4. 新增渲染风格切换指令（4 种风格）<br>5. 新增表情强度控制指令<br>6. 新增表情序列播放指令<br>7. 新增状态查询与设备状态上报<br>8. 新增事件主动上报规范（表情/人脸/语音/IMU）<br>9. 补充 405 参数无效错误码 |
