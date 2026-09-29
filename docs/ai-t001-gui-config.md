# T001 客户端 GUI：按键召唤 + 图形化配置

> ## ⚠️ 实施后修订（本文件下面第 125-131 行的「明确不做」已被后续两轮推翻，以这里为准）
>
> | 原决策 | 实际做法 | 原因 |
> |---|---|---|
> | API Key 的读写都不做，界面只显示来源 | **界面可写**（掩码只写框，留空=不改）；读取仍然绝不下发 | 你的要求。网络侧只在有管理权限时上行，服务端写 `KeyStore`，回包里没有任何可还原密钥的内容 |
> | 系统提示词留在 TOML、界面只读展示 | **界面完全不出现**（也不进快照） | 你的要求：单行输入框容易误清空，而它是抑制模型编造坐标/物品的护栏，删掉会让 AI 静默变坏 |
> | 其余 20+ 数值旋钮留在 TOML | **全部进界面**（31 项，分组分页） | 你的要求：「大多数配置都可以在游戏内 GUI 里配」 |
> | 门槛固定为 op 2 | 两个可配门槛：改配置默认 **3**、管理员级工具默认 **2** | 见 `ai-t002-admin-tools.md`；后者的执行用的是玩家自己的权限集，不会提权 |
>
> 界面结构也随之变成：主界面（常用 + API Key）+「更多设置」分页（请求/工具/上下文/历史/权限），
> 每项带 1~2 行灰字说明，装不下自动翻页（按分组对齐）。
> 字段定义已收敛到 `ai/core/config/AiConfigFields` 一张表 —— 加一项配置 = 表里加一行 + 语言文件加两条。

## Context

T001-1 ~ T001-5 已全部完成并真机验收（对话闭环 + 6 个只读工具 + 任务目标）。当前改配置只有两条路：
敲 `/ai provider|baseurl|model` 命令，或者手改 `config/zuoyanmod-common.toml`。

这带来两个实际问题：

1. **看不见全貌**：`/ai status` 是逐行文本，玩家要改三四个值就得来回敲命令、反复 `/ai status`；
2. **配置项已经很多**：T001-5 之后有 30+ 个键，手改 TOML 容易写错键名或越界值（越界会被静默夹紧，玩家以为生效了其实没有）。

本迭代做「按一个键打开 AI 配置界面」，把**最常改的十来项**做成图形化控件；其余留在 TOML，并在界面里明确告诉玩家去哪儿改。

**不在本迭代**：系统提示词的多行编辑；20+ 个数值旋钮全部图形化；**API Key 的读写**（见下「明确不做」）。

---

## 已核实的仓库既有范例（照抄对象，非猜测）

| 用途 | 照抄对象 | 关键点 |
|---|---|---|
| 按键注册 + 打开界面 | `client/UpgradeKeyHandler` | `@EventBusSubscriber(value = Dist.CLIENT)`；`ClientTickEvent.Post` 里判断 `minecraft.player != null && minecraft.gui.screen() == null` 再 `consumeClick()`；`minecraft.gui.setScreen(new XxxScreen())` |
| 按键分类 | `event/RealmKeybindHandler#CATEGORY` | 分类**只能在一处** `registerCategory`，其它 handler 只 `event.register(keyMapping)`（重复登记会炸）。26.x 的按键值是 SDL scancode |
| 打开界面时拉快照 | `UpgradeKeyHandler` + `RequestUpgradeSyncPacket` + `UpgradeSyncPacket` | 先发请求包**再立刻**开界面；界面每帧读客户端侧的静态数据，包到了自然就渲染出来 |
| 双端安全 | `network/PacketHandler` 与 `AiChatReplyPacket` | `src/main/java` 是**双端共用**源集。凡引用 `net.minecraft.client.*` 的客户端类，必须只出现在 `enqueueWork(() -> ...)` 的 lambda 里（或仅在 `Dist.CLIENT` 挂点里 new），否则专用服务端会因解析客户端类而崩 |
| 多字段同步包 | `UpgradeSyncPacket` | 用 `StreamCodec.composite` + `ByteBufCodecs.VAR_INT/VAR_LONG`；多值部分用 `.apply(ByteBufCodecs.list(n))` 而不是堆 composite 参数 |
| 配置写入 | `Config#writeString` | ⚠️ `ConfigValue#set()` **只改内存，不落盘、不发事件**，必须显式 `SPEC.save()`；`save()` 会 fire `ModConfigEvent` → 触发 `AiRuntime.requestReload()` |
| 权限 | `AiCommand` 的 `REQUIRES_GAMEMASTER` | 26.3 没有 `src.hasPermission(2)`，是 `Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER))` |

---

## 方案

### 1. 交互流程

```
玩家按 O
  → 客户端发 RequestAiConfigPacket
  → 客户端立刻打开 AiConfigScreen（此帧起数据可能是空的，显示"加载中…"）
  → 服务端（主线程）回 AiConfigSyncPacket{canEdit, 各字段当前值, 密钥来源, 生效 provider, 系统提示词 }
  → 界面进入可编辑（op）或只读（非 op）状态

玩家改完点「应用」
  → 客户端发 AiConfigUpdatePacket{enabled, provider, baseUrl, model, temperature, maxTokens,
                                   chatPrefixEnabled, chatPrefix, toolCallingEnabled, containersReadContents }
  → 服务端（主线程）：权限校验 → 逐字段校验 → 有错则回 AiConfigSyncPacket{error=…} 且**不写盘**
                     → 全通过则批量写入 + **一次** SPEC.save() + AiRuntime.reload()
  → 服务端回一份**权威快照**（含归一化后的值，例如 baseUrl 末尾斜杠已被去掉）
  → 界面用回包刷新自己，并显示「已应用」或逐字段错误
```

**为什么是「应用」批量提交，而不是改一项存一项**：`ConfigValue#set()` 不落盘，每次都要 `SPEC.save()`；而每次 save 都会 fire 配置事件、触发 `AiRuntime` 重建 HTTP 传输与 Provider 实例。逐项保存等于让玩家拖一次滑条就重建一次传输层。批量提交把 N 次保存压成 1 次。

### 2. 三个新 payload（`ai.net`，均为双端类）

| 包 | 方向 | 字段 |
|---|---|---|
| `RequestAiConfigPacket` | C→S | 无 |
| `AiConfigUpdatePacket` | C→S | 上述 10 个可编辑值（原始类型：boolean/String/double/int） |
| `AiConfigSyncPacket` | S→C | `canEdit`、10 个当前值、`effectiveProvider`、`keySource`、`keyFilePath`、`systemPrompt`、`error` |

- 服务端在**主线程**处理（`enqueueWork`）：要用到注册表/配置/权限，且 `SPEC.save()` 与 `reload()` 都应在主线程。
- `AiConfigSyncPacket` 的 `canEdit` 由**服务端**判定并下发；客户端只用它控制控件是否可点，**不构成任何授权**——真正的授权在 update 包的处理里再做一次。
- ⚠️ **密钥内容永不进入任何包**。包里只有「来源枚举名」与「文件路径」。

### 3. 校验与写入（`ai.core.config.AiConfigEdits`，新增，零 MC 依赖）

把「原始字符串 → 合法值 + 逐字段错误」的逻辑放进 `ai.core`，这样它是**纯逻辑、可直接单测**的（界面与网络层只负责搬运）：

```java
AiConfigEdits.Result result = AiConfigEdits.validate(rawInput);   // rawInput: 字段名 -> 原始字符串
result.errors();   // 字段名 -> 本地化键（非空则整体拒绝，不写盘）
result.values();   // 校验通过后的 10 个值
```

复用既有判据，而不是另写一套：
- provider → `ProviderRegistry.isKnown(...)`（与 `/ai provider` 同源，拼错当场报错而非静默回退 mock）
- baseUrl → 必须 `http://` / `https://` 开头（与 `/ai baseurl` 同源）+ `AiConfig.normalizeBaseUrl` 去尾斜杠
- temperature / maxTokens → 数字解析失败即报错；范围夹紧交给既有 `AiConfig` 构造器（**不重复实现夹紧**）

`Config` 新增批量写入：`applyAiSettings(AiConfigEdits.Values) → boolean`，内部逐个 `ConfigValue#set()` 后**只调用一次** `SPEC.save()`，并同步静态快照字段。返回值表示是否写盘成功（失败时界面提示「请手动改 TOML」，与既有 `config.write_failed` 文案一致）。

### 4. 客户端（`client/ai`，三件）

| 类 | 职责 |
|---|---|
| `AiConfigKeyHandler` | `Dist.CLIENT` 挂点，注册按键 + tick 里开界面；**唯一** new Screen 的地方 |
| `AiConfigClientData` | 持有最近一次同步的快照（静态字段 + `apply(packet)`）。参照 `client/ClientUpgradeData` |
| `AiConfigScreen extends Screen` | 渲染与交互。参照 `client/UpgradeScreen` 的写法 |

界面布局（自上而下）：标题 → 只读信息区（生效 provider / 密钥来源 / 密钥文件 / 系统提示词截断）→ 可编辑区（10 项）→ 底部按钮「应用」「完成」+ 状态行（错误或「已应用」）。

- 滑条用原版 `AbstractSliderButton` 的匿名子类（温度 0.0–2.0，步进 0.1）
- 开关用原版 `Button`，文案在「开 / 关」之间切换
- provider 用循环按钮（mock → openai-compatible → ollama → mock），并实时显示归一化后的取值
- `canEdit == false`：所有可编辑控件 `active = false`，顶部显示「只读：需要管理员（op 2）权限」

### 5. 权限与「能做到什么」

| 情形 | 行为 |
|---|---|
| op 2 玩家 | 可看可改 |
| 非 op 玩家 | **可打开、只读**。让他能确认服务端到底配没配好（这是玩家最常问的：「为什么 AI 用不了」），但改不了 |
| 无权限者伪造 update 包 | 服务端拒绝并回错误；客户端控件状态不影响判定 |

### 6. 字段范围

**可编辑（10 项）** —— 覆盖玩家实际会调的东西：

| 控件 | 对应键 |
|---|---|
| 总开关 | `ai.enabled` |
| Provider（循环） | `ai.provider` |
| API 地址（文本框） | `ai.baseUrl` |
| 模型（文本框） | `ai.model` |
| 温度（滑条） | `ai.temperature` |
| 最大 token（文本框） | `ai.maxTokens` |
| 聊天前缀开关 + 前缀（开关+文本框） | `ai.chatPrefixEnabled` / `ai.chatPrefix` |
| 工具调用开关 | `ai.toolCallingEnabled` |
| 容器内容可读开关 | `ai.tool.containersReadContents` |

**只读展示**：实际生效的 provider（配置值可能拼错被回退成 mock）、API Key 来源与文件路径、系统提示词、对话历史仅存内存的提示。

**明确不做（及理由）**：

| 项 | 为什么不做 |
|---|---|
| **API Key 的读写** | 密钥**绝不下发到客户端**是这个功能从一开始就定下的边界（见 T001-4 的「关键设计决策」）。GUI 里只显示「来源」，写入仍走 `/ai key set` 或环境变量。让界面能读密钥等于把密钥放进客户端内存，任何截图/日志/内存转储都可能带出 |
| 系统提示词的多行编辑 | MC 的 `EditBox` 是单行的，多行编辑要自写换行与滚动，工作量与风险都远超收益；这一项本就不常改，留在 TOML，界面只读展示 |
| 其余 20+ 数值旋钮（上下文半径、工具预算、历史上限…） | 都属于「配一次就不动」的参数。界面里给一句「其余项见 config/zuoyanmod-common.toml」比塞 30 个控件更清楚 |

### 7. 按键

新增 `key.zuoyanmod.open_ai_config`，默认 **`O`**（26.x 用 SDL scancode `InputConstants.KEY_O`），分类复用 `RealmKeybindHandler.CATEGORY`。

已占用的键：`K`(四维空间瓶) / `HOME`(领域切换) / `U`(升级界面) / `Y`(终极天赋)。玩家可在原版「按键设置」里改键。

---

## 涉及文件

**新增**（`ai.net`）：`RequestAiConfigPacket`、`AiConfigUpdatePacket`、`AiConfigSyncPacket`
**新增**（`ai.core.config`）：`AiConfigEdits`（校验与解析，可单测）
**新增**（`client.ai`）：`AiConfigKeyHandler`、`AiConfigClientData`、`AiConfigScreen`
**修改**：
- `network/PacketHandler`：注册 3 个 payload + 3 个 `sendXxx` 包装（包装内部走既有的 `client.ClientPacketSender`）
- `Config.java`：批量写入 `applyAiSettings(...)`（一次 `SPEC.save()`）+ 必要的静态快照同步
- `ai.command` 的服务端处理：新增一个 `ai/net` 侧的处理方法（或放在 `ai.chat`/`ai.config` 下）承接 update 包
- `zh_cn.json` / `en_us.json`：按键名、界面标题/标签/按钮、错误与只读提示
**不改**：`AiRuntime`（`config()` / `keyStore()` / `reload()` 已足够）、`AiCommand`、6 个工具、两家 Provider、`McContextCollector`

---

## 验证

**单测（扩 `src/test`，仍然零 MC 依赖）**
- `AiConfigEditsTest`：
  - provider 拼错 → 报错且**不产生**可写入值（不能静默回退成 mock）
  - baseUrl 缺 scheme → 报错；带尾斜杠 → 归一化后通过
  - temperature / maxTokens 非数字 → 报错；越界 → 按 `AiConfig` 的既有规则夹紧
  - 全部合法 → `errors` 为空且 `values` 各项正确
  - 空字符串视为「未改动」而不是「清空」（避免误删 baseUrl/model）

> 界面与三个 payload 依赖 MC，**无法**进纯 JVM 单测 —— 与既有 `UpgradeScreen` 的处理一致，靠真机验收覆盖。

**真机**
1. op 按 `O` → 界面打开并显示当前配置；`/ai status` 的值与界面一致
2. 改模型 → 点「应用」→ 界面显示已应用 → `/ai chat 你好` 用的是新模型（看服务端日志的 model）
3. 把 API 地址改成 `foo.com`（缺 scheme）→ 应用 → 界面报错且**配置未被改动**（`/ai status` 仍显示旧值）
4. 触发一次配置变更后确认服务端日志里 `[AI] 配置已重载` 只出现**一次**（验证批量 save 没有引发多次重建）
5. 非 op 玩家按 `O` → 能打开但控件全灰、顶部提示只读；改不了
6. 界面里确认**任何地方都不显示 API Key 内容**，只有「来源：环境变量/加密文件/未配置」
7. 专用服务端场景：`runServer` 启动加载无异常（验证客户端类引用都在 `enqueueWork`/`Dist.CLIENT` 内）
8. `./gradlew build test --offline` 通过（验证完**显式杀进程**，避免卡住 patched jar）

---

## 决策记录

| # | 决策 | 结论 |
|---|---|---|
| 1 | 密钥能否在界面里读/写 | **都不做**。只显示来源与文件路径；写入仍走 `/ai key set` 或环境变量 |
| 2 | 非 op 玩家能否打开 | **能打开但只读**。玩家最常问的是「为什么 AI 用不了」，只读视图正好回答；改不了 |
| 3 | 应用方式 | **批量「应用」**，不做逐项即时保存。理由：每次保存都会触发一次配置事件与 Provider 重建 |
| 4 | 可编辑范围 | 上表 10 项；系统提示词与其余数值旋钮留在 TOML（界面给出指引） |
| 5 | 默认按键 | `O`，可改键 |

> 若你觉得某几项该进/该出可编辑区（例如「系统提示词也想在界面里改」），说一声我调整——这一项是本方案里唯一比较主观的取舍。
