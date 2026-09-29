# AI-Assisted (Minecraft 1.20.1 / Forge)

基于 Forge 1.20.1 的独立 AI 助手模组。本分支移植自 [`26.3-neoforge`](../AI-Assisted)（NeoForge 26.3），功能面保持一致，仅平台与 API 适配不同。

## 特性

- **大模型问答交互**：支持 OpenAI Compatible（DeepSeek、GPT、通义千问、GLM 等）和 Anthropic Messages（Claude）协议。
- **只读环境感知**：支持查询周围方块、容器内容、实体状态、玩家状态、配方索引及服务器基础信息。
- **高危操作二次确认**：管理员级指令建议与 AI 蓝图建造均需玩家在游戏内输入 /ai confirm 确认执行，并支持分 tick 建造与撤销（/ai undo）。
- **隐私与安全**：API Key 永远不写入 TOML 配置文件、不写入日志、不下发给客户端，采用机器绑定的 PBKDF2 + AES-GCM 加密存储或环境变量注入。

详细使用说明见 [docs/ai.md](docs/ai.md)。

## 平台与构建

| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| 加载器 | Forge `47.4.x`（ForgeGradle 6） |
| Java | 17（编译与运行） |
| 映射 | official（Mojang） |

```bash
# 构建
./gradlew build

# 运行客户端 / 服务端
./gradlew runClient
./gradlew runServer

# 单元测试（core 层纯逻辑，不依赖 Minecraft）
./gradlew test
```

⚠️ **Java 版本注意**：ForgeGradle 6 只能在 Gradle 8.x 上运行，而 Gradle 8.x 无法跑在 Java 25 上。
如果本机默认 `java` 是 25，构建前请把 `JAVA_HOME` 指向 JDK 17（或 21）：

```bash
# Git Bash 示例
JAVA_HOME="C:/Program Files/Java/jdk-17" ./gradlew build
```

## 与 26.3-neoforge 分支的主要差异

移植时逐文件适配，两个分支的 `core/**`（零 MC 依赖层）与全部单测保持一致。平台差异集中在：

- **元数据**：`META-INF/mods.toml`（Forge）而非 `neoforge.mods.toml`；额外补了 1.20.1 必需的 `pack.mcmeta`。
- **网络层**：6 个包从 `CustomPacketPayload + StreamCodec + PayloadRegistrar` 改写为 Forge `SimpleChannel`（`FriendlyByteBuf` 手写编解码，方向与协议版本和 26.3 一致）。
- **配置**：`ModConfigSpec` → `ForgeConfigSpec`（同源 API，字段表不变）；1.20.1 的 `save()` 不派发配置事件，写回配置后由 `Config.applyAiSettings` 显式请求延迟重载。
- **权限**：26.3 的 vanilla `PermissionSet/PermissionCheck` 体系在 1.20.1 不存在，改用等级制 `hasPermission/hasPermissions`（0~4 级语义与配置项一一对应）。
- **配方**：26.3 的 `RecipeDisplay/SlotDisplayContext` 展示体系改为 1.20.1 的 `Recipe#getResultItem/getIngredients`；`Ingredient` 在 1.20.1 不实现 equals，材料归并按「可接受物品集合」做键。
- **客户端**：`GuiGraphicsExtractor` → `GuiGraphics`、`extractRenderState` → `render`、聊天入口走 `gui.getChat().addMessage`；按键分类从 `KeyMapping.Category` 对象改为字符串 `key.categories.ai_assisted`；`ClientTickEvent` 在 FORGE 总线、按键注册在 MOD 总线，因此拆成 `AiConfigKeyHandler`（MOD）与 `AiClientEvents`（FORGE）两个类。
- **语言层**：1.20.1 无 `Identifier` 命名，全部使用 `ResourceLocation`；`Level#dimension().location()`、`GameProfile#getName()` 等命名差异按 official mapping 逐一适配。
- **构建**：Java 25/Gradle 9.7 → Java 17/Gradle 8.9；`Config` 的模式匹配 switch 与 `List#reversed()`（Java 21 语法）降级为 instanceof 链与显式倒序拷贝。
