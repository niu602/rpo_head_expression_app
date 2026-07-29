# RPO纯表情系统 - 通信接口规范 v2.0 (Protobuf二进制版)

**文档版本**：v2.0  
**更新日期**：2026-07-21  
**适用对象**：嵌入式端（RDK主控）开发人员  
**通信角色**：Android表情APP（TCP客户端） ↔ RDK主控（TCP服务端）  
**序列化方式**：自定义轻量Protobuf二进制（不依赖Google Protobuf库）

---

## 一、网络基础配置

### 1.1 端口分配
| 链路类型 | 端口号 | 传输层协议 | 用途 | 优先级 | 链路ID |
|---------|--------|-----------|------|--------|--------|
| 主链路 | **8899** | TCP | 控制指令、表情切换、ACK、心跳 | 1（最高） | 1 |
| 高频链路 | 8900 | UDP | 传感器事件、状态上报 | 2 | 2 |
| 备用链路 | - | USB Serial | TCP断开时自动降级 | 3 | 3 |
| 备用链路 | - | BLE | USB断开时自动降级 | 4 | 4 |

### 1.2 连接方向
- **TCP服务端**：RDK主控端，监听 `0.0.0.0:8899`
- **TCP客户端**：Android表情APP，主动连接主控IP
- **默认主控IP**：`192.168.1.100`（可配置）
- **连接超时**：3秒
- **TCP参数**：`TCP_NODELAY=on`，`SO_KEEPALIVE=on`

### 1.3 帧格式（TCP/UDP统一）
所有数据包采用统一帧格式：
```
┌─────────────┬──────────────────────────────┐
│  2字节长度  │       N字节Protobuf数据      │
│  (大端序)   │        (二进制)              │
└─────────────┴──────────────────────────────┘
```

- **长度字段**：2字节无符号整数，**大端序**（高字节在前），表示后续Protobuf数据的字节长度
- **数据字段**：Protobuf二进制编码数据，最大长度 2048 字节
- **UDP数据报**：同样使用此格式，单包最大1400字节（避免分片）
- **粘包处理**：TCP严格按长度字段分包，一帧一个Protobuf消息

---

## 二、Protobuf编码基础

本项目使用**标准Protobuf Wire Format**，自实现Varint编解码，不依赖Google Protobuf库：

| Wire Type | 编码方式 | 适用类型 |
|-----------|---------|---------|
| 0 | Varint | int32, int64, uint32, uint64, bool, enum |
| 1 | 64-bit固定 | fixed64, double, float64 |
| 2 | Length-delimited | string, bytes, 嵌套消息 |
| 5 | 32-bit固定 | fixed32, float, float32 |

**Tag编码**：`tag = (field_number << 3) | wire_type`，Varint编码

**Varint编码**：每个字节低7位存数据，最高位(MSB)表示是否还有后续字节，小端序（低位在前）

---

## 三、消息头格式（所有消息统一）

所有消息（下行指令 + 上行事件）都包含统一的消息头：

| 字段号 | 字段名 | 类型 | Wire Type | 说明 |
|--------|--------|------|-----------|------|
| 1 | msg_id | uint32 | Varint | 消息唯一ID，自增，用于ACK匹配 |
| 2 | cmd_type | enum | Varint | 指令/事件类型，见「CmdType枚举」 |
| 3 | timestamp | uint64 | Varint | 时间戳（毫秒，Unix时间） |
| 4 | link_type | enum | Varint | 传输链路类型，见「LinkType枚举」 |
| 5 | seq | uint32 | Varint | 序列号，自增 |

**消息头之后**：根据不同cmd_type，跟不同的payload消息体（嵌套消息，Length-delimited编码）

---

## 四、CmdType 枚举值

### 4.1 下行指令（主控 → APP）
| 值 | 名称 | payload字段号 | 说明 |
|----|------|--------------|------|
| 1 | `CMD_EXPRESSION_SET` | 10 | 设置/切换表情 |
| 2 | `CMD_CLEAR_EXPRESSION` | - | 清除表情回到待机 |
| 3 | `CMD_SET_STYLE` | 11 | 设置眼型风格（男/女） |
| 7 | `CMD_HEARTBEAT` | 16 | 心跳ping |
| 8 | `CMD_CONFIG_UPDATE` | 18 | 配置热更新 |
| 9 | `CMD_POWER_CONTROL` | 19 | 电源控制（关机/开机） |

### 4.2 上行事件（APP → 主控）
| 值 | 名称 | payload字段号 | 说明 | 传输链路 |
|----|------|--------------|------|---------|
| 101 | `EVT_TOUCH` | 20 | 触摸屏幕事件 | UDP |
| 102 | `EVT_FACE_DETECT` | 21 | 人脸检测事件 | UDP |
| 103 | `EVT_GESTURE_IMU` | 22 | IMU/手势事件 | UDP |
| 105 | `EVT_STATUS_REPORT` | 24 | 设备状态上报（1Hz） | UDP |
| 107 | `EVT_LINK_SWITCH` | 26 | 链路切换通知 | TCP |
| 199 | `EVT_ACK` | 29 | 指令应答ACK | TCP |

---

## 五、LinkType 枚举值
| 值 | 名称 | 说明 |
|----|------|------|
| 0 | `LINK_UNKNOWN` | 未知 |
| 1 | `LINK_TCP` | TCP主链路 |
| 2 | `LINK_UDP` | UDP高频链路 |
| 3 | `LINK_USB_SERIAL` | USB串口备用 |
| 4 | `LINK_BLE` | BLE备用 |

---

## 六、ExpressionType 表情枚举值
| 值 | 名称 | 中文说明 | 优先级 | 默认时长(ms) | 自动回落 |
|----|------|---------|--------|-------------|----------|
| 0 | `IDLE` | 待机 | 0 | 永久 | ❌ |
| 1 | `BLINK` | 眨眼（内部） | 0 | 150 | ✅ |
| 2 | `SLEEPING` | 休眠 | 0 | 永久 | ❌ |
| 3 | `THINKING` | 思考中 | 1 | 永久 | ❌ |
| 4 | `RECOGNIZING` | 识别中 | 2 | 30000 | ✅ |
| 5 | `RESPONSE` | 应答反馈 | 4 | 1500 | ✅ |
| 6 | `SPEAKING` | 说话中 | 5 | 永久 | ❌ |
| 7 | `HAPPY` | 开心 | 4 | 1800 | ✅ |
| 8 | `CONFUSED` | 疑惑 | 3 | 1500 | ✅ |
| 9 | `SURPRISED` | 惊讶 | 4 | 1200 | ✅ |
| 10 | `SAD` | 委屈/难过 | 4 | 1800 | ✅ |
| 11 | `ANGRY` | 生气/警告 | 4 | 1500 | ✅ |
| 12 | `SLEEPY` | 困倦 | 1 | 永久 | ❌ |
| 13 | `SHY` | 害羞 | 4 | 1200 | ✅ |
| 14 | `LOW_BATTERY` | 低电量 | 1 | 永久 | ❌ |
| 15 | `CHARGING` | 充电中 | 1 | 永久 | ❌ |
| 16 | `ALERT` | 告警/错误 | 6 | 永久 | ❌ |
| 17 | `UPDATING` | 升级中 | 6 | 永久 | ❌ |
| 18 | `POWER_OFF` | 关机 | 7 | 永久 | ❌ |

> **优先级规则**：数字越大优先级越高，高优先级自动抢占低优先级；临时表情时长到后自动回落IDLE。

---

## 七、下行指令详细定义

### 7.1 心跳 CMD_HEARTBEAT (7)
**payload字段号**：16（嵌套消息Heartbeat）

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 1 | interval_ms | uint32 | 心跳间隔（毫秒），固定3000 |

**说明**：双向心跳，每3秒发送一次；9秒未收到心跳判定断开，自动重连。

---

### 7.2 表情设置 CMD_EXPRESSION_SET (1)
**payload字段号**：10（嵌套消息ExpressionSet）

| 字段号 | 字段名 | 类型 | 默认值 | 说明 |
|--------|--------|------|--------|------|
| 5 | expr_type | enum(ExpressionType) | - | 表情类型，见上表 |
| 6 | intensity | uint32 | 100 | 表情强度（0-100） |
| 7 | duration_ms | uint64 | 表情默认时长 | 持续时间（毫秒），0表示使用默认时长 |
| 8 | force | bool | false | 是否强制抢占高优先级表情 |

**编码示例（开心表情，持续2秒）**：
```
msg_id: 1, cmd_type: 1, timestamp: xxx, link_type: 1, seq: 1
ExpressionSet: expr_type=7, intensity=100, duration_ms=2000, force=false
```

---

### 7.3 设置风格 CMD_SET_STYLE (3)
**payload字段号**：11（嵌套消息StyleSet）

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 1 | style | enum | 0=男款丹凤眼，1=女款杏眼 |

---

### 7.4 电源控制 CMD_POWER_CONTROL (9)
**payload字段号**：19（嵌套消息PowerControl）

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 1 | power_on | bool | true=开机，false=关机 |

---

### 7.5 清除表情 CMD_CLEAR_EXPRESSION (2)
无payload，收到后立即回到IDLE待机状态。

---

## 八、上行事件详细定义

### 8.1 ACK应答 EVT_ACK (199)
**payload字段号**：29（嵌套消息Ack）

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 1 | ack_msg_id | uint32 | 应答的消息ID |
| 2 | code | enum | 0=成功，1=失败，2=格式错误，3=不支持 |

---

### 8.2 设备状态上报 EVT_STATUS_REPORT (105)
**payload字段号**：24（嵌套消息StatusReport），**每秒1次，UDP传输**

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 6 | current_expr | enum(ExpressionType) | 当前显示的表情 |
| 7 | battery_level | uint32 | 电池电量（0-100） |
| 10 | fps | uint32 | 渲染帧率（固定60） |
| 11 | link_type | enum(LinkType) | 当前活跃链路 |
| 12 | uptime_ms | uint64 | 运行时长（毫秒） |

---

### 8.3 触摸事件 EVT_TOUCH (101)
**payload字段号**：20（嵌套消息TouchEvent），**UDP传输，50ms节流**

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 2 | x | float | X坐标（归一化0.0-1.0，左→右） |
| 3 | y | float | Y坐标（归一化0.0-1.0，上→下） |
| 4 | action | enum | 0=按下, 1=抬起, 2=移动, 3=长按, 4=双击 |
| 5 | duration_ms | uint64 | 触摸时长（毫秒） |
| 6 | pointer_count | uint32 | 手指数 |

---

### 8.4 人脸事件 EVT_FACE_DETECT (102)
**payload字段号**：21（嵌套消息FaceDetectEvent），**UDP传输，100ms节流**

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 1 | has_face | bool | 是否检测到人脸 |
| 2 | face_count | uint32 | 人脸数量 |
| 3 | face_info | 嵌套消息(FaceInfo) | 主脸信息（has_face=true时存在） |

**FaceInfo子消息**：
| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 1 | offset_x | float | 中心X偏移（-1.0~1.0） |
| 2 | offset_y | float | 中心Y偏移（-1.0~1.0） |
| 3 | face_size | float | 人脸大小占比（0.0-1.0） |
| 4 | smile_prob | float | 微笑概率（0.0-1.0） |

---

### 8.5 IMU/手势事件 EVT_GESTURE_IMU (103)
**payload字段号**：22（嵌套消息GestureEvent），**UDP传输，80ms节流**

| 字段号 | 字段名 | 类型 | 说明 |
|--------|--------|------|------|
| 1 | action | enum | 0=倾斜, 1=摇晃, 2=点头, 3=摇头 |
| 2 | confidence | float | 置信度（0.0-1.0） |
| 3 | yaw | float | 偏航角（度） |
| 4 | pitch | float | 俯仰角（度） |

---

## 九、本地交互（无需主控参与）

以下交互完全在APP本地处理，不上报主控，主控无需下发指令：

| 交互方式 | 功能 |
|---------|------|
| 左侧边缘15%上下滑动 | 调节屏幕亮度 |
| 右侧边缘15%上下滑动 | 调节媒体音量 |
| 双眼区域双指捏合 | 切换男/女眼型 |
| 点击左眼 | 瞳孔右躲 |
| 点击右眼 | 瞳孔左躲 |
| 点击脸颊 | 害羞表情 |
| 点击额头 | 疑惑表情 |
| 长按额头4秒 | 关机/开机 |
| "嘘"手势保持3秒 | 关机 |

---

## 十、编解码注意事项

1. **字节序**：所有多字节数字（长度字段、float32）都是**小端序**，除了帧头2字节长度是**大端序**
2. **布尔值**：false=0，true=1，Varint编码
3. **枚举值**：使用Varint编码，等同于uint32
4. **浮点数**：float32使用固定4字节小端序（IEEE 754）
5. **可选字段**：Protobuf字段可选，解析时遇到未知字段直接跳过，保证向后兼容
6. **JSON兼容**：Protobuf解码失败时会自动fallback到JSON格式（仅TCP链路），建议新代码统一使用Protobuf
7. **关机状态**：POWER_OFF状态下APP停止摄像头、麦克风、所有通信，只有长按额头4秒唤醒后自动重连

---

## 十一、快速验证（Python示例）

```python
import socket
import struct
import time

def encode_varint(value):
    """编码Varint"""
    result = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            result.append(byte | 0x80)
        else:
            result.append(byte)
            return bytes(result)

def encode_tag(field_num, wire_type):
    """编码Protobuf tag"""
    return encode_varint((field_num << 3) | wire_type)

def encode_expression_set(msg_id, seq, expr_type, intensity=100, duration_ms=0, force=False):
    """编码表情设置指令"""
    # 先编码ExpressionSet payload (field 10)
    expr_payload = bytearray()
    expr_payload += encode_tag(5, 0) + encode_varint(expr_type)  # expr_type
    expr_payload += encode_tag(6, 0) + encode_varint(intensity)  # intensity
    expr_payload += encode_tag(7, 0) + encode_varint(duration_ms)  # duration
    expr_payload += encode_tag(8, 0) + encode_varint(1 if force else 0)  # force
    
    # 编码消息头
    msg = bytearray()
    msg += encode_tag(1, 0) + encode_varint(msg_id)  # msg_id
    msg += encode_tag(2, 0) + encode_varint(1)  # cmd_type = 1 (EXPRESSION_SET)
    msg += encode_tag(3, 0) + encode_varint(int(time.time() * 1000))  # timestamp
    msg += encode_tag(4, 0) + encode_varint(1)  # link_type = TCP
    msg += encode_tag(5, 0) + encode_varint(seq)  # seq
    msg += encode_tag(10, 2) + encode_varint(len(expr_payload)) + bytes(expr_payload)  # payload
    
    return bytes(msg)

def send_frame(sock, data):
    """发送帧：2字节大端长度 + 数据"""
    header = struct.pack('>H', len(data))
    sock.send(header + data)

# 创建TCP服务端
server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('0.0.0.0', 8899))
server.listen(1)
print("等待APP连接...")

conn, addr = server.accept()
print(f"APP已连接: {addr}")

# 发送开心表情（expr_type=7），持续2秒
happy_cmd = encode_expression_set(
    msg_id=1, seq=1,
    expr_type=7,  # HAPPY
    duration_ms=2000
)
send_frame(conn, happy_cmd)
print("已发送开心表情指令")

conn.close()
server.close()
```
