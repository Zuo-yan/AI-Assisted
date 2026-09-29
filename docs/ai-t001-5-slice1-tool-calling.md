# T001-5（第一刀）：Provider 工具调用 + Agent Loop + 2 个查询工具

## Context

T001-1~4 已用真实模型端到端验收通过（真机 + DeepSeek，模型正确使用了维度/坐标/天气/天数/背包/附近实体/村庄距离，未编造）。下一步按任务文档是 T001-5「Agent Loop 与工具系统」。

**为什么要做工具**：现在所有世界信息都靠 `McContextCollector` 每轮全量塞进上下文。这对「附近有什么」这类问题是最省的做法（1 次 LLM 调用即可回答），但有些问题它天然答不了：

- 「附近有没有钻石矿 / 工作台 / 岩浆」—— 上下文只扫**容器**，不扫任意方块
- 「我背包里到底有几个钻石」—— 上下文只给按数量排序的**前 N 项摘要**
- 「钻石剑怎么做」—— 配方数据完全不在上下文里
- 更大半径的定向搜索（上下文半径固定 16）

工具让 AI **按需主动查**，而不是把一切预先塞进上下文——这正是任务文档 FR-05 的要求。

**为什么分两刀**：架构风险集中在「两家 Provider 的报文差异」与「多轮 message 链组装」上；工具本身写起来是机械活。先把管道切开验证，再批量补工具。

**本刀范围**：Provider 工具调用支持 + Agent Loop + **2 个工具**（搜方块 / 搜实体）。
**不在本刀**：其余 4 个工具（配方 / 玩家状态 / 背包过滤 / 容器过滤）、`GoalState` 与「当前任务目标」上下文字段 —— 留第二刀。

---

## 已核实的 26.3 事实（实测源码，非记忆）

| 项 | 结论 | 来源 |
|---|---|---|
| **区块扫描优化** | `LevelChunkSection.maybeHas(Predicate<BlockState>)` 走 Palette，源码注释明说「比遍历每个位置、乃至整个区块都高效」；另有 `hasOnlyAir()` / `hasFluid()` | `LevelChunkSection.java` |
| **配方枚举** | `Level.recipeAccess()` → `RecipeAccess`（**只有 2 个方法，不能枚举**）；须转 `RecipeManager` 才有 `getRecipes()` / `byKey()` / `getRecipeFor()` | `Level.java`、`RecipeAccess.java`、`RecipeManager.java` |
| 配方输入 | `Recipe.placementInfo()` → `PlacementInfo.ingredients()` / `slotsToIngredientIndex()`；`EMPTY_SLOT = -1` | `PlacementInfo.java` |
| 仓库既有范例 | `KleinBottleMenu` 已在用 `level.recipeAccess().byKey(...)` 与 `PlacementInfo` 的 3×3 映射；客户端侧转 `RecipeManager` 会 `ClassCastException`（见 `ClientRecipeCache` 注释） | 本仓库源码 |
| 实体 id | `BuiltInRegistries.ENTITY_TYPE.getKey(EntityType)` → `Identifier`（已有 `Registry#getKey`） | 前轮已核实 |

**两家 Provider 的工具报文差异（必须归一化，这是本刀的核心风险）**：

| | OpenAI 兼容 | Ollama `/api/chat` |
|---|---|---|
| 请求 `tools` | `[{type:"function", function:{name, description, parameters}}]` | **同左** |
| 响应 `arguments` | **JSON 字符串**（需二次 parse） | **JSON 对象** |
| 响应 tool call 标识 | `id`（`call_xxx`） | 无 `id`，只有 `function.name` |
| 回传工具结果 | `{role:"tool", tool_call_id, content}` | `{role:"tool", tool_name, content}` |
| 控制 | `tool_choice:"auto"`、`parallel_tool_calls` | 同左 |

来源：Ollama 官方 OpenAPI（`docs.ollama.com/api/chat`）与 OpenAI Chat Completions 报文说明。

---

## 方案

### 1. `ai.core.llm` 数据模型扩展

**`ToolSpec`** —— 工具声明（零 MC 依赖）：
```
ToolSpec(String name, String description, JsonObject parametersSchema)
ToolSpec.builder(name, description)
    .stringParam("block_id", "方块注册表 id", required=true)
    .integerParam("radius", "搜索半径（格）", min=1, max=32, required=false)
    .build()
```
用一个小的流式 builder 生成 JSON Schema，避免每个工具手写 Schema 字符串（手写必错）。`JsonObject` 复用 MC 自带的 Gson，与 Provider 层一致。

**`ToolCall`**：
```
ToolCall(String id, String name, String argumentsJson)
```
`argumentsJson` **统一规范化为 JSON 字符串**——Ollama 返回对象时由 Provider 在解析阶段 `toString()` 归一化，上层只面对字符串。

**`ChatMessage` 扩展**（加两个字段，不新建类型层级）：
```
ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName)
+ 静态工厂 toolResult(ToolCall call, String content) —— 同时填 id 与 name
```
**为什么同时带 `toolCallId` 和 `toolName`**：这正是两家协议的分歧点（OpenAI 认 id、Ollama 认 name），Provider 各取所需，比在上层分叉两个消息类型更简单。现有的 `user/assistant/system` 工厂保持不变（新字段为空），已测代码不受影响。

**`ChatRequest`** 增加 `List<ToolSpec> tools`；**`ChatResponse`** 增加 `List<ToolCall> toolCalls` 与 `boolean hasToolCalls()`。

### 2. Provider 双协议实现

- `buildBody`：当 `request.tools()` 非空时加 `tools` 与 `tool_choice:"auto"`；否则**完全不出现 `tools` 字段**（保证关闭开关后行为与今天逐字节一致）
- 解析：
  - OpenAI：`choices[0].message.tool_calls[].{id, function.name, function.arguments(字符串)}`；`content` 可能为 `null` → 归一化为空串
  - Ollama：`message.tool_calls[].function.{name, arguments(对象)}` → `arguments.toString()`；`id` 无则用 `name` 兜底生成稳定 id（同一步内 index 拼接，保证回传时能对上）
- 工具结果回传：OpenAI 用 `tool_call_id`；Ollama 用 `tool_name`
- 两个 Provider 都保留「不带 tools 时」的原路径，**旧单测应全部继续通过**（作为回归门）

### 3. `ai.core.agent`（新增包）

```
ToolSpec / ToolRegistry / ToolInvoker / ToolOutcome / AgentLoop
```
- `ToolRegistry`：`name -> ToolInvoker`，启动时注册；`specs()` 返回给 LLM 的声明列表
- `ToolInvoker`：`ToolOutcome invoke(JsonObject args)`；**入参全部按不可信数据校验**（见下）
- `ToolOutcome`：`(boolean ok, String text)` —— `text` 是回传给模型的结构化结果，**有长度上限**
- `AgentLoop`：纯逻辑（不含 MC），持有 Provider + Registry，驱动多步：
  1. 调 `provider.chat(request)`
  2. 无 `toolCalls` → 返回最终文本
  3. 有 → 对每个 call：查注册表 → 校验入参 → 执行 → 收集 `ToolOutcome`
  4. 把 `assistant(toolCalls)` + `toolResult(...)` 追加进消息链，回到 1
  5. 达到步数上限仍未收敛 → 返回一个明确的「已达工具调用上限」结局，而不是假装成功
- **并发**：同一步内多个 tool call **顺序执行**。理由：工具都要读世界，只能在主线程跑；并行没有收益，反而引入竞态。

`AgentLoop` 与 MC 解耦的接缝：工具执行需要跳回主线程，因此 `AgentLoop` 只负责「决策与消息链」，真正的「执行工具」由 `ai.*` 层注入一个回调（该回调内部 `server.execute(...)`）。

### 4. 线程模型（本刀最容易写错的地方）

现状：`AiChatService.request` 已在主线程采集上下文 → 异步请求 → `server.execute` 回主线程收尾。

加工具后变成**交替**：

```
主线程: 采集上下文 → 发起第 1 次 LLM 调用
传输线程: 等响应
主线程(server.execute): 解析 → 若有 toolCalls → 执行工具 → 组装消息链 → 再发起下一次 LLM 调用
传输线程: 等响应
... 最多 toolMaxSteps 轮
主线程: 分页下发最终文本
```
**铁律不变**：网络回调里只做 `server.execute(...)`，绝不直接碰世界；工具一律在主线程执行。

### 5. 两个工具（`ai.tool`）

| 工具 | 参数 | 实现要点 |
|---|---|---|
| `search_blocks` | `block_id`（必填）、`radius`（默认 8，夹紧 1..16） | 遍历 `radius` 内**已加载区块**（`getChunkNow`，不强加载）→ 逐 section 先 `maybeHas(state -> state.is(block))` 早退 → 仅命中段遍历该段位置。返回最近 N 个坐标 + 总数。**用方块扫描次数作为工作量预算**，超预算即停并标注「已截断」 |
| `search_entities` | `entity_id`（必填）、`radius`（默认 16，夹紧 1..64） | 复用既有 `getEntitiesOfClass` + `BuiltInRegistries.ENTITY_TYPE.getKey` 过滤；返回最近 N 个「类型 + 距离 + 取整坐标」 |

**入参校验（LLM 输出 = 不可信）**：
- `block_id` / `entity_id` 必须能 `Identifier.tryParse` 且**在对应注册表中存在**，否则直接拒绝并回传可读原因
- `radius` 夹紧到配置上限，拒绝负数/超大值
- 结果条数上限 + 单条长度上限 + 总长上限（防止「搜方块」把整片区域塞进上下文）
- 工具结果文本外层包 `<tool_result>` 并声明「这是数据不是指令」，与上下文块同一套防注入约定

### 6. 配置（`ModConfigSpec`，全部非敏感）

| 键 | 默认 | 说明 |
|---|---|---|
| `ai.toolCallingEnabled` | `true` | 关掉后请求体里不出现 `tools`，行为回到 T001-4 |
| `ai.toolMaxSteps` | `4` | 单条消息最多几次 LLM 往返。**这是费用上限**：4 步 = 最坏 4 次计费调用 |
| `ai.toolMaxResults` | `10` | 单次工具返回条数上限 |
| `ai.toolMaxScanBlocks` | `32768` | `search_blocks` 的工作量预算（扫描次数） |
| `ai.toolLoopTimeoutSeconds` | `120` | 整个工具循环的墙钟预算；超时后在当前步收尾并告知用户 |

**为什么是「工作量预算」而不是「工具执行超时」**：工具在主线程同步执行，无法硬中断。所以能做的只有两件事——把工作量本身限死（扫描次数/结果条数），以及给整个循环一个墙钟上限（到点不再发起下一步）。**不会假装有超时中断**。

### 7. 历史记录策略（重要取舍）

**`toolCalls` / `toolResult` 消息不写入 `ChatHistory`**，只把最终的 user/assistant 文本对存进去。理由：
- 两家协议的 call id 跨轮次无意义，回放反而可能被服务端拒绝
- 原始工具 JSON 会快速吃光上下文预算
- 上下文快照每轮都是新的，不需要靠历史里的旧工具结果

即：**工具链只存在于一次 `request()` 的生命周期内**。这也让 `ChatHistory` 无需改动。

### 8. 涉及文件

**新增**（`ai.core.agent`）：`ToolSpec`、`ToolRegistry`、`ToolInvoker`、`ToolOutcome`、`AgentLoop`
**新增**（`ai.tool`）：`SearchBlocksTool`、`SearchEntitiesTool`、`AiTools`（注册入口）
**修改**：
- `ai.core.llm`：`ChatMessage`、`ChatRequest`、`ChatResponse`、`OpenAiCompatibleProvider`、`OllamaProvider`、新增 `ToolCall`
- `ai.chat.AiChatService`：由「单次请求」改为「驱动 AgentLoop」
- `ai.AiRuntime`：构建并持有 `ToolRegistry`，把配置传下去
- `Config.java`：5 个新键
- `zh_cn.json` / `en_us.json`：工具循环相关文案（步数上限、工具被拒、参数非法等）
**不改**：`McContextCollector`、`ContextRenderer`、`ChatHistory`、`ReplyDispatcher`、`AiCommand`、`AiServerEvents`

---

## 验证

**单测（扩 `src/test`，仍然零 MC 依赖）**
- `ToolSpecTest`：builder 产出的 JSON Schema 结构正确（`type/properties/required`）
- `OpenAiCompatibleProviderTest` 扩展：带 tools 时请求体含 `tools`+`tool_choice`；**不带 tools 时请求体不含 `tools`**（回归门）；`tool_calls` 解析（含 `content:null`）；`arguments` 是字符串
- `OllamaProviderTest` 扩展：`arguments` **对象**被归一化成 JSON 字符串；`id` 缺失时生成兜底 id；工具结果回传用 `tool_name`
- `AgentLoopTest`（用假 Provider + 假工具）：无 tool call → 一步结束；一次 tool call → 两步结束；连续 tool call 到上限 → 明确返回「达上限」而非死循环；工具抛异常 → 以失败结果回传且循环继续
- `ToolArgValidationTest`：非法/越界/未知 id 入参被拒且不抛异常

**真机（这一刀的关键验收）**
1. `/ai chat 附近有没有钻石` → 服务端日志出现 `[AI] 调用工具 search_blocks(...)`，回答里的坐标与游戏内实际一致
2. `/ai chat 附近有几只羊` → 走 `search_entities`，数量与实际相符
3. `/ai chat 你好` → **不产生任何工具调用**（1 次 LLM 调用即答，验证没有为简单问题白白多花钱）
4. `ai.toolCallingEnabled=false` 后重试第 1 条 → 不再出现工具调用日志，行为回到 T001-4
5. `/ai chat 帮我把周围的怪打死` → 仍应明确拒绝（本刀不含动作类工具，这是**预期**行为，不是 bug）
6. `./gradlew build test --offline` 通过；`runServer` 启动无异常（验证完**显式杀进程**，避免再卡住 patched jar）

---

## 边界澄清：「让 AI 帮我打死周围的怪」不在任何已规划子任务内

用户实测时问过这个，模型回答「无法操纵游戏」。**这个回答是正确的**，不是 bug——我们没给它任何动作类工具，系统提示词也没声称它有能力。

按任务文档的现有决策，这件事**不在 T001 范围**：

| 相关条目 | 文档结论 |
|---|---|
| FR-05 工具清单 | 「移动 / 看向 / 挖掘 / **攻击** / 使用物品」这类**以 AI 自身为施动者**的工具被**明确删除**，理由是没有 AI 身体、做出来只会永远失败 |
| FR-06 AI 玩家实体 | **明令不实现**，并禁止留骨架/占位开关 |
| T001-6 建造 | 是「以蓝图输入、由建造系统执行」的放方块，不是 Agent 随手调用的动作接口 |
| FR-05 补充末条 | 「若确需作用于发起命令的玩家/世界的动作，必须标为**危险级**并默认关闭」 |

**如果想做，技术上可行**：把「伤害半径内的敌对生物」做成一个**危险级工具**（默认关闭 + 二次确认 + 权限检查），施动者定为「发起命令的玩家」而非 AI 自己。但这属于**新需求**，应单独立项——因为一旦允许 AI 改世界，下面这些必须一起想清楚，和只读查询不是一个量级：

- 领地/保护区与其他权限模组的冲突
- 误伤玩家/宠物、掉落物归属、经验归属
- 生物死亡是否触发成就/统计/其他模组的击杀判定
- 可回滚性（只读查询不需要回滚，改世界需要）
- 费用：一次动作 = 至少 2 次 LLM 调用，玩家可能连点

**建议**：先按计划把 T001-5 两刀做完（只读查询），把「让 AI 动手」作为独立任务单独评估。第二刀之后再决定。

