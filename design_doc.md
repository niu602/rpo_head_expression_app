# RPO 机器人｜统一设置入口 \+ 用户自选音色 设计方案

> 部分内容由豆包生成
> 
> 

# 一、目标与范围

本方案合并两个需求：其一，把当前散落的两个交互——「表情模式切换」（传统眼部 / 能量面部，现由屏幕左下角按钮触发）和「男女风格切换」（现由双指捏合手势触发）——统一收进一个类似常见 App「设置」齿轮的入口，点击后弹出面板集中管理；其二，在该面板中新增「音色选择」，允许用户从语音模型支持的音色里自选，并强制约束：男风格下只能选男音色，女风格下只能选女音色。切换音色时，对固定句式音频做预缓存以保证秒开。

方案完全贴合现有架构（**PersonaRegistry** 单一配置源、**EyeStyleFactory** 按风格取渲染器、**CosyVoice** 预缓存与 LRU 机制、**PersonaSwitchManager** 事务锁），以最小侵入落地。本文只做设计，不改动现有代码。

**非目标：**不改动能量场 / 眼部渲染算法本身；不新增新的语音模型；不改动 LLM 人格 system prompt 文案。

# 二、总体设计

核心思路：新增一个统一的「设置面板」作为唯一交互入口，内部承载三类设置项。三者作用域与底层耦合关系不同，必须分清——表情模式是纯渲染层开关，与人格 / 音色无关；男女风格切换是重量级操作，会联动 LLM 人格、TTS 音色、表情风格三者一起切；音色选择则依附于当前风格的性别。下表汇总三项差异，是整套设计的地基。

|设置项|可选值|作用域 / 是否联动人格|底层调用|
|---|---|---|---|
|表情模式|传统眼部 TRADITIONAL / 能量面部 ENERGY|纯渲染层，**不联动**人格与音色|`ExpressionManager.setMode()`|
|男女风格|女款萝卜 FEMALE / 男款阿凛 MALE|重量级，**联动** LLM 人格 \+ TTS 音色 \+ 表情风格|走 `switchPersona()` 事务流程|
|音色|当前性别组内的多个音色|依附当前风格性别；切风格会重置为该人格已选音色|`TtsManager.setVoice()` \+ 预缓存|

# 三、统一设置入口（Settings 面板）

## （一）入口按钮

把屏幕左下角现有的「模式切换键」升级为「设置齿轮键」：单击不再直接切换能量模式，而是弹出设置面板。能量模式改为面板内的一个选项，不再单独占用一个常驻按钮。双指捏合切风格的手势可保留作为快捷方式，但正式入口统一到面板，避免用户找不到功能。设置面板建议做成覆盖在表情画布之上的半透明弹层，点击面板外区域或关闭键收起。

## （二）面板结构

面板自上而下分三个分区，每区一行标题加若干可选项：

- **表情模式**：两个互斥选项（传统眼部 / 能量面部），当前项高亮。点选即调用 `setMode()`，实时生效，无需确认。

- **形象风格**：两个互斥选项（女款·萝卜 / 男款·阿凛），当前项高亮。点选触发完整人格切换事务（见第五节）。切换后本区下方的「音色」子列表要自动刷新为新性别的音色。

- **音色**：以卡片列表展示**当前性别组**的音色，每张卡片含展示名、风格标签、【试听】按钮与选中态。点选即应用并预缓存（见第五、六节）。

## （三）三项之间的依赖关系

表情模式与另外两项完全解耦，任何时候可独立切换。形象风格是「音色」的上游约束：面板永远只展示当前风格性别对应的音色组，因此天然不会出现「男风格选到女音色」。当用户在面板里切换风格时，音色子列表随之刷新，并把选中态定位到「该人格上次选过的音色」（读不到则回落到该人格默认音色）。

# 四、音色库设计

## （一）数据模型

新增一个按性别分组的音色库 **VoiceLibrary**，模式与现有 `EyeStyleFactory`、`PersonaRegistry` 一致，新增音色只需加一行配置，不改业务逻辑。核心数据结构如下。

|VoiceOption 字段|含义|
|---|---|
|voiceId|云端音色 ID（如 longanyang），唯一键，同时作为缓存 key 前缀|
|displayName|展示名，如「阳光大男孩」|
|tag|一句话风格描述，如「低沉稳重 / 播报腔」|
|gender|直接复用 ExpressionStyle：MALE / FEMALE|
|systemProfile|系统 TTS 降级用的 VoiceProfile（pitch / rate）|
|sampleText|试听句，如「你好，我是你的机器人伙伴。」|

**VoiceLibrary** 对外提供三个方法：`getVoices(gender)` 返回该性别的音色列表（面板渲染用）、`getById(voiceId)` 反查单个音色（降级映射与校验用）、`getDefault(gender)` 返回该性别默认音色（即现有人格默认）。用 `ExpressionStyle` 作为分组键的好处是：「男风格→男音色」直接写成 `getVoices(config.expressionStyle)`，与人格配置里的 `expressionStyle` 字段天然对齐，零额外映射。

## （二）音色清单

下表为候选分组示例，**具体 voiceId 必须以 dashscope cosyvoice\-v3\-flash 官方音色列表为准**（阿里会调整），上线前核对一遍。建议每组先上 3 个（含默认 1 个），够用且能控制后文的缓存膨胀。

|性别|voiceId（示例）|展示名|风格标签|
|---|---|---|---|
|女|longanhuan\_v3（现默认）|欢欢|元气活泼（萝卜标配）|
|女|longxiaochun\_v2|小春|温柔知性|
|女|longxiaoxia\_v2|小夏|清亮少女|
|男|longanyang（现默认）|洋洋|阳光大男孩（阿凛标配）|
|男|longshu\_v2|书叔|低沉磁性|
|男|longjing\_v2|靖哥|稳重播报|

## （三）用户选择的持久化

新增轻量封装 **VoicePreferenceStore**（基于 SharedPreferences），**按人格分开存**：key 规则为 `voice_selected_{persona}`，即 `voice_selected_LUOBO` 与 `voice_selected_ALIN` 两条，值为 voiceId。读方法 `getSelectedVoice(persona)` 未选过返回 null；写方法 `setSelectedVoice(persona, voiceId)`。按人格分存的意义在于：用户给萝卜挑的女声、给阿凛挑的男声互不覆盖，来回切人格仍是各自选定的音色。

## （四）性别约束的三道关卡

任一关卡都足以保证「男风格只能选男音色」：展示层只渲染当前性别组，异性音色根本不出现；写入层在 `setSelectedVoice` 前断言 voiceId 的 gender 等于当前风格，不符直接拒绝（防止语音指令等入口绕过 UI）；解析层在人格切换时若发现持久化的 voiceId 性别与目标人格不符（脏数据），回退到该性别默认音色。

# 五、与切换流程的衔接

**表情模式**：面板点选后直接调用 `setMode()`，通过 StateFlow 原子更新，画布 `collectAsState` 自动重绘，无任何人格 / 音色耦合，最简单。

**男女风格**：面板点选后走现有 `switchPersona()` 事务流程（申请锁→切断旧流→数据层切换→表情动画→播登场语音→恢复监听），不改时序。唯一改动点是「数据层切换」这一步的音色解析：现在写死 `config.cloudVoiceId`，改为「先读用户为该人格选过的音色，读不到或非法才用默认」。概念逻辑：先取 `VoicePreferenceStore.getSelectedVoice(persona)`，用 `VoiceLibrary.getById` 校验其 gender 等于目标风格，通过则用它，否则回落 `config.cloudVoiceId`，再交给 `setTtsVoice`。效果是：切到阿凛时若用户之前给阿凛选过「书叔」，登场台词就用书叔音色说出。

**音色**：面板点选某音色时的应用顺序为——校验性别→持久化→`stopSpeaking()`→`TtsManager.setVoice(新id)`→播一句 sampleText 试听→对当前人格固定句式逐句预缓存。若正处于对话中，试听前先停旧流，试听毕再 `startAsrRecognition()` 恢复听。音色切换也应走 PersonaSwitchManager 锁（或一个轻量 voice\-switch 守卫），避免与人格切换竞态。

# 六、切换音色时的预缓存方案

## （一）固定句式集

把散落的固定句集中成「当前人格的固定句式清单」，建议在 `PersonaConfig` 上加一个 `fixedPhrases` 字段汇总，来源包括：PersonaConfig 里的 introQuote、alreadyHereQuote、greetingWithoutName、mockHello / mockName / mockBye / mockThanks、preloadPhrases（"好的 / 没问题 / 稍等"）；以及 MainActivity 里硬编码的系统句，如「好的，随时可以叫我，拜拜」「抱歉，我没有听清，请再说一遍」「好的，我已经清空对话记忆啦，我们重新开始吧」。

## （二）触发时机

|场景|动作顺序|
|---|---|
|用户在面板选新音色|校验性别 → 持久化 → stopSpeaking → setVoice\(新id\) → 播 sampleText 试听 → 对当前人格 fixedPhrases 逐句 preload|
|人格切换且解析出的音色与当前不同|在数据层 setVoice 之后，紧接着对目标人格 fixedPhrases 逐句 preload|

## （三）为什么这样能生效

贴 CosyVoice 现有机制：`preload()` 内部会先捕获 `currentVoiceId` 快照再后台合成，因此必须**先 setVoice 再 preload**，顺序不能反，才能保证缓存进的是新音色；缓存 key 为「新 voiceId : 句子」，与旧音色缓存天然隔离，切回旧音色仍秒开；preload 走后台线程池，不阻塞主线程，也不需要长时间占用事务锁（setVoice 本身耗时不足 1ms，锁只需覆盖它）。

# 七、缓存与 LRU 风险及规避

**风险：**preload 进的条目是 protected，而 LRU 只淘汰「已过期」或「非 protected」条目。若用户反复试很多音色，每个音色都预缓存一整套固定句，protected 条目会越堆越多，突破 60 条上限后缓存只增不减。

**规避（按优先级）：**第一，**只预缓存「当前人格 \+ 当前选中音色」这一套**，绝不预缓存整个音色库，这样任意时刻 protected 条目约为「单人格固定句数（约 10 条）× 活跃音色 1\~2 个」，远小于 60；第二，每性别音色控制在 3 个（见第四节）；第三，作为后续可选优化项——切换离开某音色时把它的 protected 条目降级为非 protected（或提供 clearVoiceCache\(voiceId\)），让 LRU 能回收，或把 MAX\_CACHE\_SIZE 提到 90。另需注意 CacheEntry 有 URL 过期时间，protected 但已过期的条目仍会被清，所以预缓存应「临用前做」（选择时 / 切换时），这正是第六节的时机安排。

# 八、系统 TTS 降级映射（必须补的 hook）

现状：`TtsManager.setVoice` 中系统降级靠 `PersonaRegistry.getByVoiceId(voiceId)` 找 VoiceProfile，而新音色不在 Registry 里，会返回 null 并一律回落女声——男用户断网时会变成女声，体验错乱。方案是把降级映射的数据源从 `PersonaRegistry.getByVoiceId` 换成 `VoiceLibrary.getById(voiceId)?.systemProfile`（每个 VoiceOption 自带 systemProfile）。这样任意新音色在断网走系统 TTS 时，也能按其性别给出正确的 pitch / rate。

# 九、边界与容错

|场景|处理|
|---|---|
|选中音色在库中被移除（版本更新）|解析时 getById 为 null，回退 getDefault\(gender\)|
|对话中试听|先 stopSpeaking / stopListening，播完 sample 后 startAsrRecognition 恢复听|
|音色切换与人格切换并发|音色切换也走 PersonaSwitchManager 锁或轻量守卫，避免 setVoice 竞态|
|云端 TTS 关闭 / 断网|走第八节系统降级映射，按性别给 pitch / rate|
|预缓存失败（网络抖动）|preload 内部已 try\-catch 静默失败；真正播放时 synthesizeSentence 会再合成兜底|
|切换表情模式（能量↔传统）|与人格 / 音色无关，setMode 即时生效，不触发任何切换事务|

# 十、分期落地建议

建议分三期推进，每期都保持现有默认行为不被破坏（读不到用户选择时一律回落默认）。

1. **Phase 1（地基）：**新增 VoiceLibrary \+ VoicePreferenceStore \+ 第五节「切换时解析用户音色」。此时暂无面板，默认行为与现在完全一致，架构先就位。

2. **Phase 2（入口 \+ 音色）：**做统一设置面板（表情模式 / 形象风格 / 音色三分区）\+ 试听 \+ 第六节预缓存 \+ 第八节系统降级映射。用户开始能自选音色。

3. **Phase 3（打磨）：**可选的语音指令入口（如"换个音色"在当前性别组内轮换）\+ 第七节缓存回收优化。

> （注：部分内容可能由 AI 生成）
