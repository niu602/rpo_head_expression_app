# roboto_origin 通信接口规范 v1.0

## 一、通信基础

- **传输协议**：TCP
- **默认端口**：8899
- **数据格式**：UTF-8编码JSON，每条指令以换行符(\n)结尾
- **连接方式**：手机端作为客户端主动连接主控
- **心跳间隔**：2秒

## 二、通用指令格式

所有指令遵循以下JSON结构：

```json
{
  "cmd": "指令类型",
  "type": "表情类型（可选）",
  "duration": 1000,
  "force": false,
  "timestamp": 1234567890123,
  "seq": 1,
  "source": "指令来源",
  "data": {}
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| cmd | string | 是 | 指令类型，见下文枚举 |
| type | string | 否 | 表情类型，expression指令必填 |
| duration | number | 否 | 表情持续时长(ms)，不填使用默认值 |
| force | boolean | 否 | 是否强制切换表情，默认false |
| timestamp | number | 是 | 毫秒级时间戳 |
| seq | number | 是 | 消息序列号，递增 |
| source | string | 是 | 指令来源标识 |
| data | object | 否 | 扩展数据字段 |

### 指令类型枚举

| cmd值 | 方向 | 说明 |
|-------|------|------|
| `expression` | 主控→手机 | 切换单个表情 |
| `sequence` | 主控→手机 | 播放表情序列 |
| `clear` | 主控→手机 | 清除表情，回到待机 |
| `ping` | 双向 | 心跳检测 |
| `pong` | 双向 | 心跳回应 |
| `status` | 手机→主控 | 设备状态上报 |
| `brightness` | 主控→手机 | 设置屏幕亮度 |

### 指令来源枚举

| source值 | 说明 |
|----------|------|
| `roboto_origin` | 主控系统 |
| `asr_module` | 语音识别模块 |
| `face_recognition` | 人脸识别模块 |
| `gamepad` | 手柄按键 |
| `expression_app` | 表情APP自身 |
| `system` | 系统 |

## 三、主控→手机 指令详情

### 3.1 切换表情 (expression)

触发单个表情切换。

**请求示例**：
```json
{
  "cmd": "expression",
  "type": "speaking",
  "duration": 3000,
  "force": false,
  "timestamp": 1690000000000,
  "seq": 1,
  "source": "asr_module"
}
```

**type字段可选值**：
| type值 | 对应表情 | 默认时长 | 自动回落 |
|--------|----------|----------|----------|
| `idle` | 待机 | 永久 | 否 |
| `blink` | 眨眼 | 200ms | 是 |
| `speaking` | 说话 | 永久 | 否 |
| `thinking` | 思考 | 永久 | 否 |
| `recognizing` | 识别中 | 永久 | 否 |
| `response` | 应答反馈 | 1000ms | 是 |

### 3.2 播放表情序列 (sequence)

按顺序播放一系列表情，用于Demo演示流程。

**请求示例**：
```json
{
  "cmd": "sequence",
  "timestamp": 1690000000000,
  "seq": 2,
  "source": "gamepad",
  "data": {
    "types": ["recognizing", "response", "idle"]
  }
}
```

**说明**：
- types数组按顺序执行
- 自动回落表情使用默认时长
- 非自动回落表情默认等待1.5秒后执行下一个

### 3.3 清除表情 (clear)

立即回到待机状态。

**请求示例**：
```json
{
  "cmd": "clear",
  "timestamp": 1690000000000,
  "seq": 3,
  "source": "roboto_origin"
}
```

### 3.4 设置亮度 (brightness)

手动设置屏幕亮度，覆盖自动亮度。

**请求示例**：
```json
{
  "cmd": "brightness",
  "timestamp": 1690000000000,
  "seq": 4,
  "source": "roboto_origin",
  "data": {
    "value": 0.8
  }
}
```

**说明**：
- value范围：0.0~1.0
- 设置后自动亮度调节暂停

## 四、手机→主控 指令详情

### 4.1 状态上报 (status)

APP定期上报当前运行状态。

**上报示例**：
```json
{
  "cmd": "status",
  "timestamp": 1690000000000,
  "seq": 100,
  "source": "expression_app",
  "data": {
    "device": "redmi_note_12_turbo",
    "status": "running",
    "current_expression": "idle",
    "brightness": 0.75,
    "battery_level": 85
  }
}
```

**状态字段说明**：
| 字段 | 类型 | 说明 |
|------|------|------|
| device | string | 设备型号 |
| status | string | 运行状态：running/error |
| current_expression | string | 当前表情 |
| brightness | number | 当前屏幕亮度 |
| battery_level | number | 电池电量百分比 |

### 4.2 心跳 (ping/pong)

双向心跳检测，2秒间隔。

## 五、典型交互流程

### 5.1 手柄触发Demo流程

```
手柄按键按下
    ↓
主控发送 sequence 指令
    ↓
APP按顺序播放：识别中 → 应答 → 待机
    ↓
APP上报状态变化
```

### 5.2 ASR语音交互流程

```
用户开始说话
    ↓
ASR模块发送 expression: thinking
    ↓
识别完成，开始播报
    ↓
ASR模块发送 expression: speaking
    ↓
播报结束
    ↓
ASR模块发送 expression: response
    ↓
1秒后自动回到待机
```

### 5.3 人脸识别流程

```
开始人脸检测
    ↓
人脸识别模块发送 expression: recognizing
    ↓
识别成功
    ↓
人脸识别模块发送 expression: response
    ↓
1秒后自动回到待机
```

## 六、错误处理

1. **连接断开**：手机端3秒后自动重连
2. **JSON解析失败**：忽略该条指令，日志记录
3. **未知指令**：忽略该条指令，日志记录
4. **优先级抢占**：低优先级表情请求被拒绝时不返回错误，静默处理
