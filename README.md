# AI-Assisted (Minecraft AI 助手模组)

基于 NeoForge 26.3 (Minecraft 1.26.3) 的独立 AI 助手模组。

## 特性

- **大模型问答交互**：支持 OpenAI Compatible（DeepSeek、GPT、通义千问、GLM 等）和 Anthropic Messages（Claude）协议。
- **只读环境感知**：支持查询周围方块、容器内容、实体状态、玩家状态、配方索引及服务器基础信息。
- **高危操作二次确认**：管理员级指令建议与 AI 蓝图建造均需玩家在游戏内输入 /ai confirm 确认执行，并支持分 tick 建造与撤销（/ai undo）。
- **隐私与安全**：API Key 永远不写入 TOML 配置文件、不写入日志、不下发给客户端，采用机器绑定的 PBKDF2 + AES-GCM 加密存储或环境变量注入。

详细使用说明见 [docs/ai.md](docs/ai.md)。
