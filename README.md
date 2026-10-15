# AI-Assisted (Minecraft AI 助手模组)

适用于 **Minecraft 1.21.1 + NeoForge 21.1.262** 的独立 AI 助手模组。

当前版本：`1.6-neoforge-1.21.1`，开发分支：`codex/1.21.1-neoforge`。

## 开发与构建

需要 Java 21。Windows PowerShell：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat build
.\gradlew.bat runClient
```

构建产物：`build/libs/ai_assisted-1.6-neoforge-1.21.1.jar`。

开发运行目录按任务隔离在 `run-1.21.1/` 下；客户端使用 `run-1.21.1/client/`，避免误用原 26.3 开发目录里的存档和配置。

## 特性

- **大模型问答交互**：支持 OpenAI Compatible（DeepSeek、GPT、通义千问、GLM 等）和 Anthropic Messages（Claude）协议。
- **只读环境感知**：支持查询周围方块、容器内容、实体状态、玩家状态、配方索引及服务器基础信息。
- **高危操作二次确认**：管理员级指令建议与 AI 蓝图建造均需玩家在游戏内输入 /ai confirm 确认执行，并支持分 tick 建造与撤销（/ai undo）。
- **隐私与安全**：API Key 永远不写入 TOML 配置文件、不写入日志、不下发给客户端，采用机器绑定的 PBKDF2 + AES-GCM 加密存储或环境变量注入。

详细使用说明见 [docs/ai.md](docs/ai.md)。
