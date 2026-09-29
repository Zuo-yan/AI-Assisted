# T001-6：AI 辅助建造（蓝图 → 严格执行 → 可撤销）

## Context

**为什么做这个**：T001-1~5 只读工具、T002 身份与危险级指令都已上线。现在 AI 能"看"和"说"，
但不能"造" —— 而"帮我建一座木屋"是这类模组最常被期待的用法。

**为什么不能做成"给 Agent 加个放方块的工具"**：你们自己的任务文档已经把 T001-6 定性为
「以蓝图输入、由建造系统执行」的放方块，**不是** Agent 随手调用的动作接口
（`ai-t001-5-slice1-tool-calling.md:196`）。理由是 prompt 注入 + 模型幻觉一旦配合"直接写世界"，
后果不可控。所以本方案的核心是：**模型只负责设计，执行器严格照图，玩家确认后才落地，且可撤销。**

**已与你敲定的产品决策**（实施时不再讨论替代方案）：

| # | 决策 | 你的选择 |
|---|---|---|
| 1 | 蓝图来源 | **AI 生成**结构化施工图 |
| 2 | 模型对蓝图的改动权 | **执行器严格照图**，不做任何自行改动 |
| 3 | 落地安全阀 | **玩家确认 + 整场可撤销**（不做客户端可视化预览） |
| 4 | 材料 | **不消耗**背包材料 |
| 5 | 已有地形/建筑 | **只能覆盖自然地形**，判不准就当人工方块 → 拒绝 |
| 6 | 建造位置 | **脚下 + 面朝方向**（模型不指定坐标） |

---

## 已核实的仓库事实

- **运行期写世界已有先例**：`world/DomainExpansionWorldGen.java:20-32`（`setBlockAndUpdate`）、
  `world/GrassPortalShape.java:185`（`setBlock(pos, state, 18)`）、`event/TimeFreezeManager.java:169`、
  `entity/RickEntity.java:102,346`（`StructureTemplate.placeInWorld`）。
- **分 tick 推进的房内惯例**：静态管理器 + `ServerTickEvent.Post` 回调
  （`event/TimeFreezeManager.java:211`、`event/VacuumDecayBlackHoleManager.java:94`、
  `event/MultiverseCloneEventHandler.java:48`、`ai/chat/ReplyDispatcher.java:23`）。
- **危险级 + 确认机制已存在**（T002）：`ai/AiPermissions.java`、`ai/tool/ProposeCommandTool.java`、
  `ai/chat/PendingCommandStore.java`（每玩家一条 / TTL / take 即消费）、
  `ai/command/AiCommand.java` 的 `/ai confirm`、`/ai cancel`、`/ai whoami`。
- **工具入参 schema 能力有限**：`ai/core/llm/ToolSpec.java` 的 Builder 目前只有 `stringParam` /
  `integerParam`，**没有数组参数** → 需要为蓝图加一个 `arrayParam`。
- **零 MC 依赖层受测试保护**：`ai/core/` 不得引用 `net.minecraft`（`CorePurityTest` 守着），
  所以蓝图的解析/校验/展开/预览必须全部放 `ai/core/build/`。
- **原版 API 已核对**：
  - `BlockTags` 里有 `DIRT/SAND/BASE_STONE_OVERWORLD/BASE_STONE_NETHER/DEEPSLATE_ORE_REPLACEABLES/
    SCULK_REPLACEABLE_WORLD_GEN/SNOW/ICE/LEAVES/LOGS/FLOWERS/CROPS/REPLACEABLE/REPLACEABLE_BY_TREES/
    MOSS_BLOCKS/TERRACOTTA/ORES/CORAL_BLOCKS`；**没有** GRAVEL/WATER/LAVA 的 tag，需显式判 `Blocks`。
  - `Block` 更新位：`UPDATE_NEIGHBORS=1 / UPDATE_CLIENTS=2 / UPDATE_KNOWN_SHAPE=16 / UPDATE_SUPPRESS_DROPS=32`。
- **无需新网络包**：不做客户端预览，反馈沿用 `player.sendSystemMessage`（同 `ProposeCommandTool`）
  与命令回执，`network/PacketHandler.java` 与 client 侧一行不动。

---

## 方案

### 1. 蓝图格式（模型产出，core 层解析校验）

工具参数（**不加 `size`**：尺寸由 `layers` 推导，从根上消灭"size 与 layers 不一致"这类错误）：

| 参数 | 类型 | 说明 |
|---|---|---|
| `name` | string | 建筑名（给玩家看，可空） |
| `palette` | string[] | 每项形如 `W=minecraft:oak_planks`、`.=minecraft:air` |
| `layers` | string[] | 每项是**一层**，层内多行用 `;` 分隔；每行长度必须一致（= width），层数 = height，每层行数 = depth |

为什么用"分层字符画 + palette"而不是逐方块坐标列表：一个 9×7×5 的木屋用坐标列表要 300+ 条，
模型既贵又容易写错；字符画约 315 个字符就能表达，而且**玩家能在聊天里直接看懂**
（正好补上"不做可视化预览"的缺口）。

工具名：**`propose_build`**（与 `propose_command` 同族，名字即约束：它只提议）。

### 2. 校验（全部在 `ai/core/build/`，纯逻辑可单测）

顺序执行，任一步失败即整场拒绝并给出**可读原因**：

1. 分层结构自洽：行宽一致、层数/行数合法、palette 覆盖所有出现的字符、无重复字符映射；
2. 方块 id：必须形如 `namespace:path`（形状校验在 core，注册表存在性在 MC 侧）；
3. **危险方块黑名单**（core 按 id 字符串硬编码，MC 侧再按 Block 实例复查一遍）：
   `bedrock, barrier, light, structure_block, structure_void, jigsaw, command_block(+chain/repeating),
   end_portal, end_portal_frame, end_gateway, nether_portal, spawner, trial_spawner, vault,
   fire, soul_fire, lava, tnt, budding_amethyst`；
4. 总量：`width*height*depth ≤ ai.build.maxBlocks`（默认 4096）；
5. 覆盖检查：只检查"目标态 ≠ 现状"的格子，逐格判定是否为**自然地形**。

**自然地形判定（白名单式，未知=拒绝）** —— 顺序：

```
① 有 BlockEntity（箱子/机器/告示牌…）→ 人工，拒绝
② 空气 / 水 / 岩浆 → 可覆盖
③ 属于这些 tag → 可覆盖：
   REPLACEABLE, REPLACEABLE_BY_TREES, DIRT, SAND, BASE_STONE_OVERWORLD,
   BASE_STONE_NETHER, DEEPSLATE_ORE_REPLACEABLES, SCULK_REPLACEABLE_WORLD_GEN,
   SNOW, ICE, LEAVES, LOGS, FLOWERS, CROPS, MOSS_BLOCKS, TERRACOTTA, ORES, CORAL_BLOCKS
④ 显式补充：GRAVEL, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, VINE, SNOW_LAYER, CLAY
⑤ 其余一律当人工方块 → 拒绝，并报出**第一个违规坐标与方块名**
```

为什么必须白名单：黑名单对模组/数据包新增方块必然漏，漏的后果是"覆盖掉别人的机器"；
白名单漏判的后果只是"这块地建不了，自己去挖一下"。**代价不对称，所以往保守方向做。**

### 3. 位置与轮廓（`BuildPlacement`，core 纯数学）

- 原点 = 玩家脚下方块 **沿面朝方向再偏移 2 格**，蓝图从原点向面朝方向展开（y 向上）。
- 偏移 2 格是为了让玩家站在建筑外侧 —— 比"建完把玩家传送出去"少一个改世界的动作，
  也不用处理坠落/骑乘/落点安全/区块加载。**用单测钉死"玩家所在格恒在轮廓外"**（四朝向 × 多尺寸）。
- 模型**不能指定坐标**：蓝图的坐标系是相对的（0..w, 0..h, 0..d），物理位置由玩家站的地方决定。
  这样模型无法把建筑盖到别人家。

### 4. 链路（谁在哪个线程）

```
玩家：帮我建一座小木屋
  → 模型调用 propose_build（工具执行已被 AiChatService.dispatchTool 投回服务端主线程）
  → ① 门禁：ai.build.enabled 开 + 等级 ≥ ai.tool.adminLevel
  → ② 解析 + 校验（core）+ 自然地形覆盖检查（MC，逐格 getBlockState）
  → ③ 涉及区块用 getChunkNow 检查（绝不强加载）
  → ④ 存 PendingBuildStore（施工图 + 展开的方块列表 + 原点 + 覆盖清单）
  → ⑤ 聊天里发：分层 ASCII 施工图 + 尺寸 + 方块总数 + 耗时预估 + 覆盖范围坐标
       末尾：回复 /ai confirm 开始建造，/ai cancel 取消（120 秒内有效）⚠ 会改动世界，可用 /ai undo 撤销
  ——— 以上不写任何方块 ———

玩家：/ai confirm
  → take 待确认 → 三道门禁与覆盖**再查一遍**（提议后世界可能变了、权限可能被降）
  → palette 一次性解析成 BlockState[]（注册表查询只做这一次）
  → 建 BuildSession 入队 → 回执"N 块，约 M 秒"
  → 之后每个 ServerTickEvent.Post 推进 blocksPerTick 块
```

### 5. 分 tick 放置（`ai/build/`，MC 侧）

- 每 tick 预算：`ai.build.blocksPerTick`（默认 64，范围 1..512）→ 4096 块约 3.2 秒，
  顺便有"建筑长出来"的演出感。
- 放置顺序：从最低层开始、层内按行序 —— 保证门/楼梯/栅栏先有支撑再成形。
- `setBlock` flag = **`UPDATE_CLIENTS|UPDATE_NEIGHBORS|UPDATE_SUPPRESS_DROPS` = 2|1|32 = 35**：
  要客户端更新、要邻居更新（否则栅栏/门/楼梯不连接成一个整体）、抑制替换旧方块时的掉落。
  备选（真机若发现中间态异常再用，不加配置）：先 `2|16` 铺完、再对轮廓边界做一次 `blockUpdated` 补形状。
- 目标格所在区块**未加载**（`getChunkNow == null`）→ **中止整场**，保留已放置部分与撤销记录，
  回执告诉玩家"去哪、用什么命令复原"。绝不为了建造强加载区块。
  ⚠️ 因此**玩家在建造过程中离线/走远会导致区块卸载 → 建造中途停止**，这是刻意的（诚实失败 + 可撤销）。
- 服务端停机：不尝试回滚，只丢会话并记日志（已放置多少块、哪个玩家）。

### 6. 撤销（`UndoLog`，仅内存）

- 只记录**真正被改动**的格子（目标态 ≠ 现状 且 `setBlock` 返回 true）：`(BlockPos, 旧 BlockState)`。
- `/ai undo`：反向恢复，同样分 tick（复用同一预算）。
- **条件撤销**：恢复前校验"当前方块仍然是我们放下的那个 id"，不匹配则跳过并计数
  （防止吃掉别人后来放/改的方块），回执里如实说"跳过 N 格"。
- **门禁只受 `ai.tool.adminLevel` 约束，不受 `ai.build.enabled` 影响** ——
  开关管的是"制造新改动"，撤销只是回退我们自己的改动；关掉开关不该锁死玩家唯一的补救路径。
- 保留策略：每玩家只留最近一次；被下一次建造覆盖；TTL 10 分钟；重启即失（持久化归 T001-8）。

### 7. 新配置（字段表驱动，自动进 GUI）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `ai.build.enabled` | BOOL | **false** | 是否允许 AI 提议建造（独立于 `ai.tool.dangerousEnabled`：那个语义是"任意指令"，更宽） |
| `ai.build.maxBlocks` | INT 1..32768 | **4096** | 单次建造的方块总数上限 |
| `ai.build.blocksPerTick` | INT 1..512 | **64** | 每 tick 放置多少块（TPS 保护） |

新开一个分组 `Group.BUILD`（"AI 建造"）：**不塞进 `Group.TOOL`** —— 工具调用页已有 6 项，
再加 3 项会让同一页混两类关心点。（分页是按窗口高度动态算的，一个组跨多页本来就支持，
所以新开组是为了语义清晰，不是为了容量。）

配置改动的固定动作（一处漏了就会"界面能改但落不下去"）：
`Config.java` 的 `define/defineInRange` + 静态默认值 + `AI_SPEC_VALUES` + `syncAiSnapshot()`；
`AiConfig` record 加 3 字段 + 夹紧；`AiRuntime.readConfig()` 传参；
`AiConfigFields` 加 3 行；中英语言文件各加 label + desc（`AiLangKeysTest` 会强制要求，标签 ≤78px）；
`AiConfigTest` / `AiConfigEditsTest` 里的 `new AiConfig(...)` 补参。

### 8. 命令与工具注册

| 入口 | 权限 | 作用 |
|---|---|---|
| `propose_build`（工具） | `ai.build.enabled` + 等级 ≥ `ai.tool.adminLevel`（达不到**不注册**） | 只提议，不写世界 |
| `/ai confirm` | 所有玩家（只影响自己的待确认） | 先处理**待建造**，没有才回落到既有"待确认指令"分支 |
| `/ai cancel` | 所有玩家 | 两个待确认都清掉 |
| `/ai undo` | 等级 ≥ `ai.tool.adminLevel`（不受两个开关影响） | 撤销最近一次建造 |
| `/ai whoami` | 所有玩家 | 增加一行：建造是否开放 |

**互斥**：提议建造时清掉待确认指令，反之亦然 —— 避免 `/ai confirm` 执行到"不是玩家刚看到的那件事"。
`PendingBuildStore` 与 `PendingCommandStore` 并列存在（不改动刚验收的 T002 代码），
TTL 取 **120 秒**（建造比一条指令更需要时间读完预览）。

---

## 涉及文件

**新增（core，零 MC，可单测）** — `ai/core/build/`：
`BuildBlueprint`（值对象）、`BlueprintParser`（palette+layers → 字符网格）、`BlueprintValidator`
（结构/黑名单/总量校验，返回机器可读错误）、`BuildPlan`（展开后的方块列表 + 耗材统计）、
`AsciiPreview`（渲染分层施工图给玩家看）、`BuildPlacement`（原点 + 四朝向旋转）、
`TerrainPolicy`（自然地形判定的白名单策略）+ `TerrainProbe`（接口，便于用假实现做覆盖检查的单测）、
`UndoLog`（记录与反向恢复的数据结构，不碰 MC）。

**新增（MC 侧）** — `ai/build/`：
`ProposeBuildTool`（工具声明与提议逻辑）、`PendingBuildStore`、`BuildManager`（会话队列 + 每 tick 推进 +
审计日志）、`BuildExecutor`（**全仓库唯一的新增写方块处**）、`McTerrainProbe`（BlockTags 实现）。

**修改**：`ai/core/llm/ToolSpec.java`（+`arrayParam`）、`ai/core/agent/ToolArgs.java`（+`stringArray`）、
`ai/AiRuntime.java`（持有 `BuildManager`/`PendingBuildStore`，玩家退出与停机清理）、
`ai/event/AiServerEvents.java`（tick 推进 + 停机关闭）、`ai/tool/AiTools.java`（条件注册）、
`ai/command/AiCommand.java`（confirm/cancel 分支、新增 `/ai undo`、whoami/status/help 补充）、
`Config.java`、`ai/core/config/AiConfig.java`、`AiConfigFields.java`、中英语言文件、
既有测试的构造调用。

---

## 安全与边界（对照既有政策）

| 政策条款 | 本方案如何满足 |
|---|---|
| 危险级工具默认关闭 | `ai.build.enabled=false`，且它是唯一决定 `propose_build` 是否注册的开关 |
| 只能提议、不能执行 | 工具阶段一个方块都不写，只产出预览与待确认项 |
| 必须玩家二次确认 | `/ai confirm` 是唯一入口，确认时**重查**门禁与覆盖（世界可能已变） |
| 施动者 = 发起命令的玩家 | 原点取玩家脚下与朝向；模型无法指定坐标 |
| 不覆盖别人的东西 | 自然地形白名单 + BlockEntity 一票否决 + 未知=拒绝 + 报出首个违规坐标 |
| 可回滚性 | 整场可撤销，且**条件撤销**不会吃掉别人后来的改动 |
| 费用 | 一次设计 = 1 次调用（生成蓝图）；确认后不额外调模型 |

**已知取舍（如实记录）**：
- 取消"整场撤销"的唯一路径是建造后 10 分钟内、且没再建第二座 —— 超出就只剩手动修。这是"仅内存单条"的代价。
- 建造过程中玩家离线/走远 → 区块卸载 → 中途停止。刻意不做强加载（那会拖垮服务器）。
- 不消耗材料 = 多了一个刷建材的口子。它的门禁是"等级 + 默认关闭 + 玩家确认"，
  在多人服上等价于"只有你能给自己刷"。

---

## 验证

**纯 JVM 单测**（`src/test/java/org/gwfx/zuoyanmod/ai/core/build/`）：

- `BlueprintParserTest`：行宽不一致、层数/行数不符、缺 `=`、重复字符、空 layers、只有空行
- `BlueprintValidatorTest`：黑名单（参数化逐个 id）、未覆盖字符、id 缺命名空间、超总量
- `BuildPlacementTest`：四朝向的原点与旋转正确；**玩家所在格恒在轮廓外**（多尺寸参数化）
- `AsciiPreviewTest`：分层渲染、超长层截断
- `CoveragePlannerTest`：同态跳过（挖空的地方不检查）、首个违规坐标、未知方块按人工处理
- `UndoLogTest`：只记真正改动的格、反向恢复顺序、条件跳过、每玩家仅一条
- `PendingBuildStoreTest`：TTL / 取到即消费 / 后覆盖前 / 清理
- 补 `ToolSpecTest`（arrayParam 生成的 schema）与 `ToolArgsTest`（stringArray 拒绝非字符串元素）

**真机**（按顺序）：

1. 打开 `ai.build.enabled` → 站在草地上说"帮我建一座 9×7 的小木屋" → 聊天里出现分层施工图 + 总数 + 覆盖范围
2. `/ai confirm` → 方块逐 tick 长出来（F3 看 TPS 不掉），玩家自己不在建筑里
3. 站到自己的木板房上试同一句 → **必须被拒**，并告诉你碰到的是什么方块、在哪
4. `/ai undo` → 全量还原（含被替换的草/土），再 `/ai undo` 提示无记录
5. 建造中途跑远让区块卸载 → 中途停止 + 提示，`/ai undo` 能复原已放置部分
6. 关掉 `ai.build.enabled` → `/ai confirm` 被拒，但 `/ai undo` 仍可用
7. 关服重启 → `/ai undo` 无记录（仅内存），方块保留
8. 逼近 `maxBlocks` 与超限各试一次
9. 双人场景：A 提议后 B 在同一片区域放方块 → A 确认时应因覆盖检查失败被拒；
   A 建造后 B 改了其中一格 → A `/ai undo` 应跳过那一格并如实计数
10. `./gradlew build`（先显式杀客户端进程，避免卡 patched jar）

---

## 不在本次范围

- **客户端可视化预览**（半透明轮廓/幽灵方块）：本轮用聊天里的分层 ASCII 图代替；真做要新包 + 客户端渲染 + 分块下发
- **从选区导出蓝图 / 导入 .nbt/.schem 文件**（本轮蓝图只由 AI 生成）
- **材料消耗与物品消耗模式**
- **多座建造排队 / 撤销历史多级**
- 蓝图与撤销记录的**持久化**（归 T001-8）
