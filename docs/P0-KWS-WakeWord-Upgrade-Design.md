# P0 唤醒词方案升级 — 深度设计与选型文档

> 项目：RPO 机器人表情系统（RpoRobotExpression）  
> 日期：2026 Roboparty Camp  
> 状态：方案设计（待评审）  
> 文件标识：P0-KWS-DESIGN-v1.0

---

## 目录

1. [现状分析](#1-现状分析)
2. [KWS 方案横向对比](#2-kws-方案横向对比)
3. [推荐方案与理由](#3-推荐方案与理由)
4. [架构设计](#4-架构设计)
5. [集成步骤](#5-集成步骤)
6. [风险与注意事项](#6-风险与注意事项)
7. [预期收益量化](#7-预期收益量化)

---

## 1. 现状分析

### 1.1 当前唤醒链路（基于代码审计）

```
MainActivity.startWakeUpListening()
  └─▶ AudioManager.startListening(triggerExpression=false)
        └─▶ AsrManager.startListening()
              ├─ ① WebSocket 连接到 dashscope.aliyuncs.com (~200-500ms)
              ├─ ② session.update 握手 (~100ms)
              ├─ ③ AudioRecord 录音 → Base64 编码 → WS 发送 (持续)
              ├─ ④ 服务端 VAD 检测 → 等用户说完 (~1-3s)
              ├─ ⑤ 服务端 ASR 转录 (~300-800ms)
              └─ ⑥ 返回 transcript 文本
        └─▶ MainActivity.isFuzzyMatch() 模糊匹配 "萝卜"等唤醒词
              └─▶ 命中 → triggerWakeUp() → 进入连续对话
              └─▶ 未命中 → continueWakeUpListen() → 循环重来
```

### 1.2 核心问题

| 问题 | 量化 | 原因 |
|------|------|------|
| **唤醒延迟高** | 1.5~3s | 全量 ASR 需等用户说完 + 服务端转录 + 网络往返 |
| **CPU/网络浪费** | 持续 100% 麦克风 + WebSocket 长连接 + Base64 编码 | 无人说话时也持续录音上传 |
| **误唤醒率高** | 长对话中任意包含 "萝"/"卜" 音的内容都可能触发 | `isFuzzyMatch()` 的 4 种匹配规则过于宽松 |
| **漏唤醒率高** | 语速快/含糊 → ASR 可能识别为 "罗布"/"罗波" → 需维护别名表 | 依赖 ASR 转录质量，间接检测 |
| **功耗高** | 持续网络 + CPU | 待机时仍跑全量 ASR 流水线 |

### 1.3 当前代码痛点清单

```
MainActivity.kt:
├── wakeWords = ["萝卜", "萝卜萝卜", "罗布", "罗波", "hiro", "hirobot", "hi bot"]
├── isFuzzyMatch() — 4 条宽松规则，误触面广
├── startWakeUpListening() — 每次循环都要 WS 连接→录音→转录→匹配→断开→重启
├── continueWakeUpListen() — 200ms 间隔重启，高频重连
├── WAKE_COOLDOWN_MS = 3000ms — 靠冷却防重复，治标不治本
└── 整个唤醒监听路径占用 AsrManager 全部资源（录音、WS、CPU）

AsrManager.kt:
├── triggerExpression=false 参数 → 唤醒监听与正式识别共用同一套代码
├── bargeIn 参数 → 又一套共用逻辑
└── 单例模式 → 同一时刻只能有一个 ASR 会话
```

---

## 2. KWS 方案横向对比

### 2.1 候选方案概览

| # | 方案 | 类型 | 中文支持 | 维护状态 |
|---|------|------|---------|---------|
| A | **Porcupine (Picovoice)** | 商业 SDK + 免费额度 | ✅ 内置中文 | 🟢 活跃 |
| B | **Sherpa-ONNX KWS (k2-fsa)** | 开源 (Apache 2.0) | ✅ 有预训练模型 | 🟢 活跃 |
| C | **TFLite KWS (Google)** | 开源 (Apache 2.0) | ❌ 需自训模型 | 🟡 社区维护 |
| D | **Snowboy** | 开源 (已弃) | ✅ 有社区模型 | 🔴 停止维护 (2020) |
| E | **Vosk (离线ASR)** | 开源 (Apache 2.0) | ✅ 支持中文 | 🟢 活跃 |
| F | **百度/讯飞离线唤醒** | 商业 SDK | ✅ 原生中文 | 🟢 活跃 |

### 2.2 详细对比矩阵

| 维度 | A. Porcupine | B. Sherpa-ONNX | C. TFLite KWS | D. Snowboy | E. Vosk | F. 百度/讯飞 |
|------|-------------|---------------|---------------|------------|---------|------------|
| **准确率** | ⭐⭐⭐⭐⭐ 业界领先 | ⭐⭐⭐⭐ 良好 | ⭐⭐⭐ 依赖训练 | ⭐⭐⭐ 中等 | ⭐⭐⭐ 中等(过重) | ⭐⭐⭐⭐⭐ 优秀 |
| **误唤醒率** | <0.5次/小时 | ~1次/小时 | 依赖训练 | ~2次/小时 | N/A(ASR级) | <0.5次/小时 |
| **端到端延迟** | **<50ms** | **<100ms** | **<50ms** | ~200ms | ~300ms(ASR级) | <100ms |
| **CPU占用** | **~0.5%** | ~1-3% | ~1-2% | ~2-3% | ~5-10%(过重) | ~1-2% |
| **模型大小** | ~300KB/词 | ~2-5MB | ~30-500KB | ~1-2MB | ~40MB(过重) | ~5-10MB |
| **功耗(待机)** | 极低 | 低 | 低 | 中 | 高 | 低 |
| **自定义唤醒词** | ⭐⭐⭐⭐⭐ Console在线生成，分钟级 | ⭐⭐⭐ 需录制/找预训练 | ⭐⭐ 需收集数据训练 | ⭐⭐ Web训练已下线 | ⭐⭐⭐⭐ 关键词配置即可 | ⭐⭐⭐⭐ 在线平台生成 |
| **中文"萝卜"支持** | ✅ Console 支持中文词 | ✅ 有中文KWS预训练模型 | ❌ 需自训(数据量大) | ✅ 有社区模型 | ✅ 可配置关键词 | ✅ 原生支持 |
| **Android集成** | ⭐⭐⭐⭐⭐ 官方AAR+5行代码 | ⭐⭐⭐⭐ 提供Android Demo | ⭐⭐⭐ 需自行封装推理 | ⭐⭐ NDK编译复杂 | ⭐⭐⭐⭐ 官方Android库 | ⭐⭐⭐⭐ 官方SDK |
| **开源协议** | SDK: Apache 2.0<br>模型: Picovoice专有许可 | **Apache 2.0**<br>(含模型) | **Apache 2.0** | Apache 2.0<br>(已弃) | **Apache 2.0** | 专有商业许可 |
| **商用费用** | 免费: 3词×3设备/月<br>付费: ~$1500/月起 | **完全免费** | **完全免费** | N/A | **完全免费** | 按设备授权 |
| **离线运行** | ✅ 完全离线 | ✅ 完全离线 | ✅ 完全离线 | ✅ 完全离线 | ✅ 完全离线 | ✅ 完全离线 |
| **GPL-3.0兼容** | ⚠️ 模型许可有争议 | ✅ 完全兼容 | ✅ 完全兼容 | ✅ 兼容 | ✅ 完全兼容 | ❌ 不兼容 |

### 2.3 关键数据来源

#### Porcupine 性能数据
- Picovoice 官方基准测试：在 Snapdragon 855 上 CPU 占用 <0.5%，延迟 <50ms
- 免费额度（Picovoice Free Plan）：每月 3 个自定义唤醒词、3 台设备，超出需付费
- 模型通过 Picovoice Console (https://console.picovoice.ai) 在线训练，中英文均支持

#### Sherpa-ONNX 性能数据
- k2-fsa/sherpa-onnx GitHub 项目：基于 Next-gen Kaldi + ONNX Runtime
- Android 示例项目 (https://github.com/k2-fsa/sherpa-onnx/tree/master/android)
- 预训练中文 KWS 模型基于 zipformer 架构，模型大小 ~3MB
- 在中端 Android 设备上 RTF (Real-Time Factor) <0.1，即处理 1 秒音频只需 <0.1 秒 CPU

#### TFLite KWS 局限性
- Google 官方预训练模型仅支持英语 12 个词 (yes/no/up/down/left/right/on/off/stop/go)
- 自定义中文词需按 Google ML 方案训练：至少需要数千条录音 + 背景噪声数据
- 训练流程复杂，周期 >2 周，不适合比赛项目快速迭代

#### Snowboy 已废弃
- 2020 年 KITT.AI 被 Microsoft 收购后停止维护
- 官网训练页面已下线，社区模型可能不匹配 "萝卜"
- 不推荐用于新项目

---

## 3. 推荐方案与理由

### 3.1 推荐方案：Sherpa-ONNX KWS（方案 B）

#### 推荐理由

| 决策因素 | 权重 | Sherpa-ONNX | Porcupine | 胜出原因 |
|---------|------|-------------|-----------|---------|
| GPL-3.0 合规性 | 🔴 P0 | ✅ Apache 2.0 全开源 | ⚠️ 模型专有许可 | **项目是 GPL-3.0，Porcupine 模型许可有冲突风险** |
| 中文"萝卜"支持 | 🔴 P0 | ✅ 有预训练模型 + 支持自定义 | ✅ Console 训练 | 两者都支持，Sherpa 有现成模型 |
| 集成速度 | 🟡 P1 | ~1-2 天 | ~0.5-1 天 | Porcupine 更快，但 Sherpa 也在可接受范围 |
| 准确率 | 🟡 P1 | ⭐⭐⭐⭐ 良好 | ⭐⭐⭐⭐⭐ 最佳 | Porcupine 略优，但 Sherpa 已满足 <500ms 目标 |
| 完全免费/无限制 | 🟡 P1 | ✅ | ⚠️ 3设备限制 | **比赛多设备部署场景，免费额度可能不够** |
| 社区支持 | 🟢 P2 | 🟢 活跃 GitHub | 🟢 官方支持 | 均可 |
| 无厂商锁定 | 🟢 P2 | ✅ | ❌ | Sherpa 可随时切换模型 |

#### 备选方案：Porcupine（方案 A）
- 如果优先追求**最快集成**和**最高准确率**，且不担心 GPL 许可问题
- 集成只需 0.5 天，代码量 <50 行
- 建议作为 **Plan B**，在 Sherpa-ONNX 准确率不达标时快速切换

#### 明确排除
- **TFLite KWS**：中文需自训模型，周期 >2 周，不现实
- **Snowboy**：已停止维护，不推荐新项目
- **Vosk**：本质是全量 ASR，功耗/模型体积过大，违背 KWS 轻量化初衷
- **百度/讯飞**：商业授权与 GPL-3.0 冲突，且有厂商锁定

### 3.2 Sherpa-ONNX KWS 技术细节

```
sherpa-onnx 项目
├── KWS 路径：sherpa-onnx/projects/android (官方 Android Demo)
├── 引擎：ONNX Runtime (微软，Apache 2.0)
├── 模型格式：.onnx (~3MB)
├── 音频输入：16kHz 16bit Mono PCM (与现有 AsrManager 配置一致)
├── 工作方式：流式推理，每 ~200ms 推理一次，窗口滑动检测
└── 关键词配置：tokens.txt + keywords.txt (文本配置，非二进制)
```

---

## 4. 架构设计

### 4.1 新增模块：KwsManager

```
com.roboparty.rpoexpression.audio/
├── AsrManager.kt          ← 现有：全量ASR（WebSocket → 通义千问）
├── TtsManager.kt          ← 现有：语音播报
├── AudioManager.kt        ← 现有：统一音频协调器
└── KwsManager.kt          ← 【新增】关键词检测引擎
```

#### KwsManager 职责

```
┌─────────────────────────────────────────────────────────┐
│                    KwsManager                            │
│                                                         │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐ │
│  │ AudioRecord  │─▶│ ONNX Runtime  │─▶│ Detection    │ │
│  │ (16kHz 16bit)│  │ KWS Inference │  │ Callback     │ │
│  │ 独立录音通道  │  │ (~200ms/帧)   │  │ onKeyword()  │ │
│  └──────────────┘  └───────────────┘  └──────────────┘ │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │            误触防护层 (Anti-False-Trigger)        │   │
│  │  • 置信度阈值过滤                                 │   │
│  │  • 冷却期（3s 内不重复触发）                       │   │
│  │  • 二次确认窗口（可选）                            │   │
│  │  • TTS 播报期间静音（回声保护）                     │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

#### KwsManager 接口设计

```
interface KwsCallback {
    fun onKeywordDetected(keyword: String, confidence: Float)
    fun onError(error: String)
}

class KwsManager(context: Context) {
    // 生命周期
    fun init()                              // 加载 ONNX 模型
    fun start(callback: KwsCallback)        // 启动持续监听
    fun stop()                              // 停止监听，释放录音
    fun isRunning(): Boolean

    // 配置
    fun setConfidenceThreshold(value: Float)  // 置信度阈值，默认 0.7
    fun setMuted(muted: Boolean)              // TTS 播报时静音

    // 资源
    fun release()                           // 完全释放模型和引擎
}
```

**关键设计决策**：
- KwsManager **使用独立的 AudioRecord 实例**，与 AsrManager 的录音通道隔离
- KWS 运行期间 AsrManager 的 WebSocket **完全断开**，零网络开销
- 两者互斥：KWS 运行时 ASR 必须已停止，反之亦然

### 4.2 状态机设计

#### 当前状态机（重构前）

```
                    ┌──────────────────────┐
                    │  startWakeUpListening │
                    │  (ASR全量 + 模糊匹配) │◀───────┐
                    └──────────┬───────────┘        │
                               │ 命中唤醒词          │
                               ▼                    │
                    ┌──────────────────────┐        │
                    │  triggerWakeUp()     │        │
                    │  进入连续对话         │        │
                    └──────────┬───────────┘        │
                               │                    │
                    ┌──────────▼───────────┐        │
                    │  startAsrRecognition │        │
       ┌───────────▶│  (全量ASR → LLM →    │        │
       │            │   TTS → 回到聆听)     │        │
       │            └──────────┬───────────┘        │
       │                       │                    │
       │           ┌───────────┴───────────┐        │
       │           │                       │        │
       │     ┌─────▼─────┐          ┌──────▼──────┐ │
       │     │ 继续对话   │          │ 退出对话     │ │
       │     │ (30s超时)  │          │ startWakeUp │─┘
       │     └───────────┘          └─────────────┘
       └───────────────────┘
```

#### 新状态机（重构后）

```
┌─────────────────────────────────────────────────────────────────┐
│                   VoiceInteractionStateMachine                   │
│                                                                 │
│                                                                 │
│   ┌──────────────┐   唤醒词命中    ┌──────────────┐            │
│   │  DORMANT     │───────────────▶│  AWAKENING    │            │
│   │  (休眠监听)   │  confidence>θ  │  (唤醒确认)   │            │
│   │              │                │              │            │
│   │ ✅ KWS 引擎ON │                │ • 播放唤醒音效 │            │
│   │ ❌ ASR WS 断开 │                │ • 800ms 延迟  │            │
│   │ ❌ 网络 = 0   │                │ • 切换表情     │            │
│   │ 💤 功耗极低   │                └──────┬───────┘            │
│   └──────┬───────┘                       │                     │
│          ▲                               ▼                     │
│          │                      ┌──────────────┐              │
│          │     TTS 播报完成      │  LISTENING    │              │
│          │     或超时退出        │  (对话聆听)    │              │
│          │                      │              │              │
│   ┌──────┴───────┐              │ ✅ KWS OFF    │              │
│   │  COOLDOWN     │              │ ✅ ASR WS 连接 │              │
│   │  (冷却防抖)    │              │ • 全量ASR启动  │              │
│   │  3s 不重复    │              │ • VAD → 转录   │              │
│   └──────────────┘              └──────┬───────┘              │
│          ▲                              │                      │
│          │                     ASR 结果返回                     │
│          │                              │                      │
│          │                      ┌───────▼────────┐             │
│          │                      │  PROCESSING     │             │
│          │                      │  (LLM 推理)     │             │
│          │                      │  • LLM 流式生成 │             │
│          │                      └───────┬────────┘             │
│          │                              │                      │
│          │                      ┌───────▼────────┐             │
│          │     单轮结束          │  SPEAKING       │             │
│          │     (非连续对话)      │  (TTS 播报)     │             │
│          ├──────────────────────│  • 流式 TTS     │             │
│          │                      │  • Barge-in 监听│             │
│          │                      │  • KWS 静音     │             │
│          │                      └───────┬────────┘             │
│          │                              │                      │
│          │               ┌──────────────┼──────────────┐      │
│          │               │              │              │      │
│          │        ┌──────▼─────┐ ┌──────▼─────┐ ┌─────▼──────┐│
│          │        │连续对话继续 │ │退出词命中   │ │30s空闲超时 ││
│          │        │→ LISTENING │ │→ COOLDOWN  │ │→ COOLDOWN  ││
│          │        └────────────┘ └──────┬─────┘ └─────┬──────┘│
│          │                              │              │      │
│          └──────────────────────────────┴──────────────┘      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### 状态定义与转移条件

| 状态 | KWS | ASR(WS) | TTS | 进入条件 | 退出条件 |
|------|-----|---------|-----|---------|---------|
| **DORMANT** | ✅ ON | ❌ | ❌ | 应用启动 / 超时退出 | 唤醒词命中 |
| **AWAKENING** | ❌ OFF | ❌ | 短音效 | 唤醒词命中 | 800ms 延迟结束 |
| **LISTENING** | ❌ OFF | ✅ ON | ❌ | 进入对话 / 连续对话继续 | ASR 返回结果 |
| **PROCESSING** | ❌ OFF | ❌ | ❌ | ASR 返回文本 | LLM 流式开始 |
| **SPEAKING** | 静音(mute) | Barge-in可选 | ✅ ON | LLM 流式输出 | TTS 播报完成 |
| **COOLDOWN** | ❌ OFF | ❌ | ❌ | 退出对话 / 超时 | 3s 冷却结束 → DORMANT |

### 4.3 低功耗策略

```
┌─────────────────────────────────────────────────┐
│              低功耗策略决策树                      │
│                                                 │
│                ┌────────────┐                   │
│                │ 屏幕状态？  │                   │
│                └─────┬──────┘                   │
│           ┌─────────┴──────────┐                │
│           ▼                    ▼                │
│     ┌──────────┐         ┌──────────┐          │
│     │ 亮屏      │         │ 息屏/休眠 │          │
│     │ (前台运行) │         │ (后台运行) │          │
│     └─────┬────┘         └─────┬────┘          │
│           ▼                    ▼                │
│     ┌──────────┐         ┌──────────────┐      │
│     │ 策略 A:   │         │ 策略 B:       │      │
│     │ 连续对话   │         │ KWS 低频模式  │      │
│     │ 直接 ASR  │         │              │      │
│     │           │         │ • KWS 推理间隔 │      │
│     │ 亮屏 =    │         │   从 200ms →  │      │
│     │ 用户在    │         │   500ms       │      │
│     │ 旁边，    │         │              │      │
│     │ 省唤醒    │         │ • 可接受延迟  │      │
│     │ 步骤      │         │   <800ms     │      │
│     └──────────┘         │              │      │
│                          │ CPU 降为     │      │
│                          │ ~0.2%        │      │
│                          └──────────────┘      │
└─────────────────────────────────────────────────┘
```

**实现方式**：
- 监听 `PowerManager.isInteractive` 判断屏幕状态
- 前台 Service + WakeLock 保证息屏时 KWS 继续运行
- 息屏时 KWS 推理间隔从 200ms 放宽到 500ms，节省 ~60% CPU

### 4.4 误触防护机制（多层防护）

```
┌─────────────────────────────────────────────────────────┐
│                  误触防护架构（4 层）                     │
│                                                         │
│  Layer 1: 模型置信度阈值                                 │
│  ├─ confidence < 0.5  → 丢弃                            │
│  ├─ 0.5 ≤ confidence < 0.7 → 低优先级，累计确认          │
│  └─ confidence ≥ 0.7   → 进入 Layer 2                   │
│                                                         │
│  Layer 2: 冷却期                                        │
│  ├─ 距上次触发 < 3000ms → 丢弃（防抖）                   │
│  └─ 距上次触发 ≥ 3000ms → 进入 Layer 3                  │
│                                                         │
│  Layer 3: TTS 回声保护                                  │
│  ├─ TTS 正在播报 → 丢弃（回声保护）                      │
│  ├─ TTS 停止 < 2500ms → 丢弃（尾音保护）                 │
│  └─ TTS 未运行/保护期外 → 进入 Layer 4                  │
│                                                         │
│  Layer 4: 可选二次确认                                   │
│  ├─ 模式 A（默认）: 直接触发，响应快                     │
│  └─ 模式 B（高噪声环境）: 等待用户继续说话 500ms 确认     │
│                                                         │
│  ──▶ 通过全部层级 → onKeywordDetected() → triggerWakeUp │
└─────────────────────────────────────────────────────────┘
```

### 4.5 音频通道管理（关键！）

当前项目的 `AsrManager` 和新的 `KwsManager` 都需要 `AudioRecord`。Android 系统中同时打开多个 `AudioRecord` 实例的行为因厂商而异（部分机型只有一个录音通道）。因此：

```
┌─────────────────────────────────────────────────┐
│            AudioRecord 互斥管理                   │
│                                                 │
│  AudioManager (协调者) 统一管理 AudioRecord 所有权│
│                                                 │
│  规则：同一时刻只有一个模块持有 AudioRecord       │
│                                                 │
│  状态切换时序：                                   │
│                                                 │
│  DORMANT → LISTENING:                           │
│    ① KwsManager.stop()  → 释放 AudioRecord      │
│    ② AsrManager.startListening() → 获取 AudioRecord │
│                                                 │
│  LISTENING → DORMANT:                           │
│    ① AsrManager.stopListening() → 释放 AudioRecord │
│    ② 等 200ms 确保 AudioRecord 完全释放           │
│    ③ KwsManager.start() → 获取 AudioRecord       │
│                                                 │
│  ⚠️ 切换间隔 200ms 是安全余量，                   │
│     防止 AudioRecord 状态未完全释放导致初始化失败   │
└─────────────────────────────────────────────────┘
```

### 4.6 与现有代码的集成点

```
MainActivity.kt 改动点：

┌─ 删除 ─────────────────────────────────────────────────────┐
│  • wakeWords 列表（移入 KwsManager 模型配置）              │
│  • isFuzzyMatch() 方法（KWS 引擎替代）                     │
│  • WAKE_COOLDOWN_MS（移入 KwsManager 防护层）              │
│  • startWakeUpListening() 中调用 audioManager.startListening│
│    的整个 ASR 循环（替换为 KwsManager.start()）             │
│  • continueWakeUpListen()（KWS 是持续监听，不需要循环重启） │
└─────────────────────────────────────────────────────────────┘

┌─ 新增 ─────────────────────────────────────────────────────┐
│  • kwsManager: KwsManager 实例                             │
│  • startKwsListening() — 替代 startWakeUpListening()       │
│  • KwsCallback 回调 → triggerWakeUp()                      │
└─────────────────────────────────────────────────────────────┘

┌─ 修改 ─────────────────────────────────────────────────────┐
│  • triggerWakeUp() — 新增 KwsManager.stop() 调用           │
│  • TTS 结束回调 — 根据模式决定重启 KWS 还是 ASR            │
│  • onPause() — 停止 KWS                                    │
│  • onResume() — 恢复 KWS                                   │
│  • AudioManager — 新增 KWS 互斥协调接口                    │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. 集成步骤

### Phase 1：Sherpa-ONNX 集成（1-2 天）

```
步骤 1: 添加依赖
├── app/build.gradle.kts 添加:
│   implementation("com.k2fsa.sherpa:onnxruntime-android:1.17.x")
│   implementation("com.k2fsa.sherpa:sherpa-onnx-kws:1.10.x")
└── 或使用预编译 AAR 本地引用

步骤 2: 准备模型文件
├── 下载中文 KWS 预训练模型（从 sherpa-onnx releases）
├── 放入 app/src/main/assets/kws/
│   ├── kws_model.onnx      (~3MB)
│   ├── tokens.txt
│   └── keywords.txt         ← 配置 "萝卜" 关键词
└── 验证模型可加载、推理正常

步骤 3: 实现 KwsManager
├── 创建 KwsManager.kt（单例，与 AsrManager 对称）
├── 独立 AudioRecord 实例
├── ONNX Runtime 推理循环（~200ms 帧）
├── 4 层误触防护
└── 单元测试：验证唤醒词检测 + 误触率

步骤 4: 集成到 AudioManager
├── AudioManager 新增 kwsManager 实例
├── 新增 startKwsListening() / stopKwsListening()
├── AudioRecord 互斥管理
└── TTS 播报时自动 mute KWS

步骤 5: 重构 MainActivity 唤醒流程
├── startWakeUpListening() → startKwsListening()
├── 删除 ASR 模糊匹配逻辑
├── triggerWakeUp() 新增 KWS→ASR 切换
└── 验证完整链路：KWS命中 → ASR → LLM → TTS → 回到KWS
```

### Phase 2：低功耗优化（1 天）

```
步骤 6: 前台 Service
├── 创建 WakeWordService (foreground service)
├── WakeLock 保证息屏运行
├── 通知栏常驻通知（Android 13+ 强制）
└── MainActivity bind to Service

步骤 7: 屏幕状态自适应
├── 监听 ACTION_SCREEN_ON / SCREEN_OFF
├── 亮屏：推理间隔 200ms（高灵敏度）
├── 息屏：推理间隔 500ms（低功耗）
└── 功耗基准测试对比
```

### Phase 3：调优与验证（1 天）

```
步骤 8: 参数调优
├── 置信度阈值 θ：从 0.7 开始，实测调整
├── 冷却期：3s（沿用现有值）
├── 误唤醒率测试：8 小时静默测试，目标 <1 次/小时
└── 唤醒率测试：20 次唤醒词，目标 >95% 命中

步骤 9: 边界场景验证
├── TTS 播报中大声说唤醒词 → 不应触发（回声保护）
├── 连续对话中说话 → 走 ASR 不走 KWS
├── 息屏 30 分钟 → KWS 仍在运行
├── 网络断开 → KWS 正常（纯离线）
└── 高噪声环境 → 误唤醒率评估
```

---

## 6. 风险与注意事项

### 6.1 技术风险

| 风险 | 概率 | 影响 | 缓解方案 |
|------|------|------|---------|
| **中文"萝卜"识别率不达标** | 中 | 高 | 准备 Porcupine 作为 Plan B；可通过录制自定义词表提升 |
| **AudioRecord 冲突**（同时打开两个录音实例） | 低 | 高 | 严格互斥管理，KWS/ASR 切换有 200ms 释放间隔 |
| **ONNX Runtime APK 体积增大** | 确定 | 中 | ONNX Runtime AAR ~8MB，模型 ~3MB；可用 ABI split 减小 |
| **Android 13 前台 Service 限制** | 确定 | 中 | 使用 foregroundServiceType=microphone，声明对应权限 |
| **息屏后系统杀 Service** | 中 | 高 | WakeLock + 前台通知 + 电池优化白名单 |
| **红米 Note 12 Turbo 兼容性** | 低 | 中 | MIUI 后台限制需手动加入「自启动」白名单 |

### 6.2 架构注意事项

```
⚠️ 注意事项清单：

1. AudioRecord 所有权
   ──── KwsManager 和 AsrManager 绝不能同时持有 AudioRecord
        必须通过 AudioManager 做互斥协调

2. KWS → ASR 切换的空白期
   ──── KWS 停止 → AudioRecord 释放 → ASR 初始化 → WS 连接
        这段空白期约 800-1200ms，用户可能正在说话
        解决：triggerWakeUp() 中先播 800ms 唤醒音效（"嗯？"），
              填补切换空白期，同时给用户反馈

3. Barge-in 与 KWS 的关系
   ──── Barge-in 是 TTS 播报期间的 ASR 打断监听
        KWS 在 TTS 播报期间应 mute（静音），避免回声触发唤醒
        Barge-in 仍走 AsrManager（需网络），KWS 不参与打断

4. 连续对话模式
   ──── 连续对话期间 KWS 应关闭（用户已在对话中，不需要唤醒词）
        连续对话超时退出后才重新启动 KWS
        现有 isInContinuousConversation 标志可直接复用

5. 单例生命周期
   ──── KwsManager 和 AsrManager 都是单例
        KWS 模型加载较重（~100ms），init() 应在 Application/启动时完成
        start()/stop() 只是控制录音和推理循环，不重新加载模型

6. 模型文件位置
   ──── 放入 assets/kws/ 目录，运行时 copy 到 cacheDir 后加载
        不要放入 res/raw（部分设备对 raw 文件大小有限制）

7. 隐私合规
   ──── KWS 纯本地运行，音频不上传服务器
        但仍需在隐私政策中声明「使用麦克风进行本地唤醒词检测」
        Android 13+ 麦克风指示灯会亮，属正常行为
```

---

## 7. 预期收益量化

### 7.1 性能对比

| 指标 | 现状（ASR模糊匹配） | 升级后（KWS） | 改善幅度 |
|------|-------------------|-------------|---------|
| **唤醒延迟** | 1500~3000ms | **50~200ms** | **↓ 85-93%** |
| **待机CPU占用** | ~8-12%（持续ASR+网络） | **~0.5-2%** | **↓ 80-95%** |
| **待机网络流量** | ~50-100KB/s（持续上传） | **0 KB/s**（完全离线） | **↓ 100%** |
| **误唤醒率** | 高（宽松模糊匹配） | **<1次/小时** | **↓ 显著** |
| **漏唤醒率** | 中（依赖ASR转录质量） | **<5%** | **↓ 显著** |
| **待机功耗** | 高（WS+录音+编码+网络） | **极低**（本地推理） | **↓ 70-90%** |

### 7.2 用户体验提升

```
现状（ASR 模糊匹配）：
用户说 "萝卜" → 等 1-3 秒 → 机器人才响应 → 感觉 "迟钝"

升级后（KWS）：
用户说 "萝卜" → <200ms 机器人立即响应 "嗯？" → 感觉 "灵敏"
```

### 7.3 APK 体积影响

```
当前 APK 大小：~? MB（需实测）
新增 sherpa-onnx 库：~8MB（ONNX Runtime AAR）
新增 KWS 模型：~3MB
预估总增量：~11MB

优化手段：
├── ABI Split（只打包 arm64-v8a）：体积减少 ~40%
├── 模型压缩（INT8 量化）：模型从 3MB → ~1MB
└── 预估净增量：~6-7MB
```

---

## 附录 A：决策记录

| 日期 | 决策 | 决策者 | 依据 |
|------|------|--------|------|
| 2026 | 选择 Sherpa-ONNX 为主方案 | 方案组 | GPL-3.0 合规 + 免费无限制 + Apache 2.0 |
| 2026 | 保留 Porcupine 作为 Plan B | 方案组 | 集成速度最快，准确率最高，若 Sherpa 不达标可快速切换 |
| 2026 | 排除 TFLite KWS | 方案组 | 中文需自训模型，周期 >2 周 |
| 2026 | 排除 Snowboy | 方案组 | 2020 年已停止维护 |
| 2026 | 排除商业 SDK | 方案组 | GPL-3.0 许可冲突 |

## 附录 B：参考资源

- [Sherpa-ONNX GitHub](https://github.com/k2-fsa/sherpa-onnx)
- [Sherpa-ONNX Android KWS Demo](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxKws)
- [Porcupine 官网](https://picovoice.ai/platform/porcupine/)
- [Picovoice Console（训练唤醒词）](https://console.picovoice.ai/)
- [ONNX Runtime Android](https://github.com/microsoft/onnxruntime)
- [Google ML KWS 论文](https://arxiv.org/abs/2002.01322)

---

*文档结束。下一步：评审通过后按 Phase 1 → 2 → 3 执行。*
