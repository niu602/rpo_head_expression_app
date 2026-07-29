# RPO纯表情系统 - 通信接口规范 v1.0

**文档版本**：v1.0  
**更新日期**：2026-07-21  
**适用对象**：嵌入式端（RDK主控）开发人员  
**通信角色**：Android表情APP（TCP客户端） ↔ RDK主控（TCP服务端）

---

## 一、网络基础配置

### 1.1 端口分配
| 链路类型 | 端口号 | 传输层协议 | 用途 | 优先级 |
|---------|--------|-----------|------|--------|
| 主链路 | **8899** | TCP | 控制指令、表情切换、配置下发、心跳 | 1（最高） |
| 高频链路 | 8900 | UDP | 传感器数据上报、高频状态更新 | 2 |
| 备用链路 | - | USB Serial / BLE | TCP断开时自动降级使用 | 3/4 |

### 1.2 连接方向
- **TCP服务端**：RDK主控端，监听 `0.0.0.0:8899`
- **TCP客户端**：Android表情APP，主动连接主控IP
- **默认主控IP**：`192.168.1.100`（可配置）
- **连接超时**：3秒
- **TCP参数**：`TCP_NODELAY=on`，`SO_KEEPALIVE=on`

### 1.3 帧格式（TCP）
所有TCP数据采用统一帧格式：
```
┌─────────────┬──────────────────────────────┐
│  2字节长度  │          N字节数据           │
│  (大端序)   │      (UTF-8 JSON文本)        │
└─────────────┴──────────────────────────────┘
```

- **长度字段**：2字节无符号整数，大端序（高字节在前），表示后续JSON数据的字节长度
- **数据字段**：UTF-8编码的JSON文本，最大长度 8192 字节
- **无粘包问题**：严格按长度字段分包，一帧一个JSON命令

**帧示例**：
```
长度: 0x00 0x25 (即37字节)
数据: {"cmd":"ping","timestamp":1234567890}
```

---

## 二、心跳与保活机制

### 2.1 心跳周期
- **心跳间隔**：3秒（3000ms）
- **心跳方向**：双向，APP和主控都可以发送
- **超时判定**：连续 3 次（9秒）未收到心跳/数据，判定为断开，自动重连

### 2.2 心跳包格式
**发送（ping）**：
```json
{
  "cmd": "ping",
  "timestamp": 1721548800000,
  "seq": 1
}
```

**响应（pong）**：
```json
{
  "cmd": "pong",
  "timestamp": 1721548800001,
  "seq": 1,
  "source": "expression_app"
}
```

---

## 三、下行指令（主控 → 表情APP）

### 3.1 通用字段说明
所有下行指令都包含以下通用字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `cmd` | string | ✅ | 指令类型，见下表 |
| `timestamp` | number | ✅ | 发送时间戳（毫秒） |
| `seq` | number | ❌ | 序列号，用于ACK匹配 |
| `source` | string | ❌ | 指令来源，固定为 `"roboto_origin"` |
| `data` | object | ❌ | 附加参数，不同指令不同 |

### 3.2 指令类型列表

| cmd值 | 说明 | data字段 |
|-------|------|----------|
| `expression` | 切换表情 | `type`(string), `duration`(number, ms), `force`(bool), `intensity`(number, 0-100) |
| `set_style` | 切换性别风格 | `style`(string: "male"/"female") |
| `set_intensity` | 设置表情强度 | `intensity`(number, 0-100) |
| `sequence` | 播放表情序列 | `types`(string数组) |
| `clear` | 清除当前表情，回到待机 | - |
| `ping` | 心跳探测 | - |
| `config` | 热更新配置 | `config`(配置JSON对象) |
| `reset_config` | 重置为默认配置 | - |
| `power_off` | 远程关机 | - |
| `power_on` | 远程开机 | - |

---

### 3.3 表情切换指令（最常用）

**指令**：`cmd = "expression"`

**参数**：
| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `type` | string | ✅ | - | 表情类型，见「表情类型对照表」 |
| `duration` | number | ❌ | 表情默认时长 | 持续时间（毫秒），0表示永久 |
| `force` | bool | ❌ | `false` | 是否强制抢占高优先级表情 |
| `intensity` | number | ❌ | 100 | 表情强度（0-100） |

**示例**：
```json
{
  "cmd": "expression",
  "type": "happy",
  "duration": 2000,
  "force": false,
  "intensity": 100,
  "timestamp": 1721548800000,
  "seq": 100,
  "source": "roboto_origin"
}
```

---

### 3.4 切换性别风格

**指令**：`cmd = "set_style"`

**参数**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `style` | string | ✅ | `"male"`=男款丹凤眼，`"female"`=女款杏眼 |

**示例**：
```json
{
  "cmd": "set_style",
  "data": {
    "style": "female"
  },
  "timestamp": 1721548800000,
  "source": "roboto_origin"
}
```

---

### 3.5 清除表情回到待机

**指令**：`cmd = "clear"`

**示例**：
```json
{
  "cmd": "clear",
  "timestamp": 1721548800000,
  "source": "roboto_origin"
}
```

---

### 3.6 表情类型对照表

| type字符串 | 中文说明 | 优先级 | 默认时长 | 自动回落 | 分类 |
|-----------|---------|--------|----------|----------|------|
| **功能类** | | | | | |
| `idle` | 待机 | 0 | 永久 | ❌ | 功能类 |
| `blink` | 眨眼（内部自动触发） | 0 | 150ms | ✅ | 功能类 |
| `speaking` | 说话中（TTS播报时） | 5 | 永久 | ❌ | 功能类 |
| `thinking` | 思考中 | 1 | 永久 | ❌ | 功能类 |
| `recognizing` | 识别中（ASR/人脸识别） | 2 | 30000ms | ✅ | 功能类 |
| `response` | 应答反馈 | 4 | 1500ms | ✅ | 功能类 |
| **情绪类** | | | | | |
| `happy` | 开心 | 4 | 1800ms | ✅ | 情绪类 |
| `confused` | 疑惑/没听懂 | 3 | 1500ms | ✅ | 情绪类 |
| `surprised` | 惊讶 | 4 | 1200ms | ✅ | 情绪类 |
| `sad` | 委屈/难过 | 4 | 1800ms | ✅ | 情绪类 |
| `angry` | 生气/警告 | 4 | 1500ms | ✅ | 情绪类 |
| `sleepy` | 困倦 | 1 | 永久 | ❌ | 情绪类 |
| `shy` | 害羞 | 4 | 1200ms | ✅ | 情绪类 |
| **系统状态类** | | | | | |
| `low_battery` | 低电量 | 1 | 永久 | ❌ | 系统类 |
| `charging` | 充电中 | 1 | 永久 | ❌ | 系统类 |
| `alert` | 告警/错误 | 6 | 永久 | ❌ | 系统类 |
| `sleeping` | 休眠 | 0 | 永久 | ❌ | 系统类 |
| `power_off` | 关机（最高优先级） | 7 | 永久 | ❌ | 系统类 |
| `updating` | 固件升级中 | 6 | 永久 | ❌ | 系统类 |

> **优先级规则**：数字越大优先级越高，高优先级表情会自动抢占低优先级；低优先级无法抢占高优先级表情。临时表情（isAutoFallback=true）时长到后自动回落到待机。

---

## 四、上行数据（表情APP → 主控）

### 4.1 设备状态上报（每秒1次，UDP）

**上报周期**：1秒（1000ms）

**格式**：
```json
{
  "cmd": "status",
  "device": "redmi_note_12_turbo",
  "status": "running",
  "current_expression": "idle",
  "brightness": 0.8,
  "battery_level": 80,
  "fps": 60,
  "uptime_ms": 3600000,
  "timestamp": 1721548800000
}
```

**字段说明**：
| 字段 | 类型 | 说明 |
|------|------|------|
| `device` | string | 设备型号 |
| `status` | string | 运行状态：`running`/`sleeping`/`power_off` |
| `current_expression` | string | 当前显示的表情 |
| `brightness` | number | 屏幕亮度（0.0-1.0） |
| `battery_level` | number | 电池电量（0-100） |
| `fps` | number | 渲染帧率（固定60fps） |
| `uptime_ms` | number | 运行时长（毫秒） |

---

### 4.2 触摸事件上报

用户触摸屏幕时上报：

```json
{
  "cmd": "touch_event",
  "x": 0.5,
  "y": 0.3,
  "action": 1,
  "pointer_count": 1,
  "timestamp": 1721548800000
}
```

**字段说明**：
| 字段 | 类型 | 说明 |
|------|------|------|
| `x` | number | 触摸X坐标（归一化 0.0-1.0，左→右） |
| `y` | number | 触摸Y坐标（归一化 0.0-1.0，上→下） |
| `action` | number | 动作类型：0=按下, 1=抬起, 2=移动 |
| `pointer_count` | number | 触摸手指数 |

---

### 4.3 人脸事件上报

人脸识别状态变化时上报：

```json
{
  "cmd": "face_event",
  "has_face": true,
  "face_count": 1,
  "offset_x": 0.05,
  "offset_y": -0.02,
  "face_size": 0.3,
  "smile_prob": 0.8,
  "timestamp": 1721548800000
}
```

**字段说明**：
| 字段 | 类型 | 说明 |
|------|------|------|
| `has_face` | bool | 是否检测到人脸 |
| `face_count` | number | 检测到的人脸数量 |
| `offset_x` | number | 人脸中心X偏移（归一化 -1.0~1.0） |
| `offset_y` | number | 人脸中心Y偏移（归一化 -1.0~1.0） |
| `face_size` | number | 人脸大小占比（0.0-1.0） |
| `smile_prob` | number | 微笑概率（0.0-1.0） |

---

### 4.4 指令执行ACK

配置类指令执行后返回ACK：

```json
{
  "cmd": "ack",
  "seq": 100,
  "code": 0,
  "msg": "ok",
  "timestamp": 1721548800000
}
```

**code说明**：
- `0`：执行成功
- `1`：执行失败
- `2`：指令格式错误
- `3`：不支持的指令

---

## 五、本地交互说明（无需主控参与）

以下交互在APP本地处理，不上报主控，主控也无需下发指令：

| 交互方式 | 功能 |
|---------|------|
| 左侧边缘15%上下滑动 | 调节屏幕亮度 |
| 右侧边缘15%上下滑动 | 调节媒体音量 |
| 双眼区域双指捏合 | 切换男/女风格 |
| 点击左眼 | 瞳孔右躲 |
| 点击右眼 | 瞳孔左躲 |
| 点击脸颊 | 害羞表情 |
| 点击额头（疑惑区域） | 疑惑表情 |
| 长按额头4秒 | 关机/开机 |
| "嘘"手势保持3秒 | 关机 |

---

## 六、注意事项

1. **编码格式**：所有JSON必须使用UTF-8编码，禁止使用BOM头
2. **大小写敏感**：所有cmd字段和type字段均为**小写**，大小写敏感
3. **长度限制**：单帧数据不超过8KB，超过请拆包
4. **断线重连**：APP会自动重连，主控无需处理重连逻辑，只需保持监听即可
5. **向后兼容**：未知字段会被自动忽略，可安全扩展新字段
6. **关机状态**：`power_off`状态下APP会停止摄像头、麦克风、TCP通信，只有长按额头才能唤醒，唤醒后自动重连

---

## 七、快速验证方法

主控端可以用以下Python脚本快速测试接口：

```python
import socket
import json
import time
import struct

def send_cmd(sock, cmd_dict):
    data = json.dumps(cmd_dict).encode('utf-8')
    # 2字节大端长度前缀
    header = struct.pack('>H', len(data))
    sock.send(header + data)
    print(f"发送: {cmd_dict}")

# 创建TCP服务端
server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('0.0.0.0', 8899))
server.listen(1)
print("等待APP连接...")

conn, addr = server.accept()
print(f"APP已连接: {addr}")

# 测试：发送开心表情
send_cmd(conn, {
    "cmd": "expression",
    "type": "happy",
    "duration": 2000,
    "timestamp": int(time.time() * 1000),
    "source": "roboto_origin"
})

# 接收心跳和状态上报
while True:
    try:
        header = conn.recv(2)
        if len(header) < 2:
            break
        data_len = struct.unpack('>H', header)[0]
        data = conn.recv(data_len)
        print(f"收到: {data.decode('utf-8')}")
    except:
        break

conn.close()
server.close()
```
