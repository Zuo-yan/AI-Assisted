package org.gwfx.aiassisted;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.gwfx.aiassisted.core.config.AiConfigEdits;
import org.gwfx.aiassisted.core.config.AiConfigFields;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@EventBusSubscriber(modid = AiAssistedMod.MODID)
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ===== AI 接入（T001）=====
    // ⚠️ 这里只放**非敏感**配置。API Key 绝不能进 ModConfigSpec ——
    // 它会把值明文写进 config/ai_assisted-common.toml 并在每次启动时重写文件。
    // 密钥统一走 ai/secret/KeyStore（环境变量优先，其次运行目录下的加密文件）。

    private static final ModConfigSpec.BooleanValue AI_ENABLED = BUILDER
            .comment("AI 功能总开关（关闭后 /ai 命令只保留 status/help）")
            .define("ai.enabled", true);

    // ===== AI 管理门槛（T001-6）=====
    // 「谁能改 AI 配置」是一个独立的管理面决策，和模型参数不是一回事，所以单独一张子表。
    // 判定入口只有 ai/AiPermissions 一处，/ai 的管理类子命令与图形配置界面共用它。

    private static final ModConfigSpec.IntValue AI_PERMISSION_ADMIN_LEVEL = BUILDER
            .comment("""
                    有权修改 AI 配置、使用 /ai 管理类子命令（status/key/model/provider/baseurl/reload/debug）的最低权限等级。
                      0 = 所有人（谁能进服谁就能改，慎用）
                      1 = 版主   2 = OP   3 = 管理员（默认）   4 = 服务器所有者
                    ⚠️ 这个管理面包含「改 API Key / 改模型 / 换 API 地址」，影响的是**全服所有人**
                    （把 baseUrl 改掉等于把全服请求导向别人的服务器），所以默认取 3 而不是 2。
                    ⚠️ 它和 ai.tool.adminLevel 是两个旋钮：那个管「用指令 / 读全服信息」，默认只要 2。
                    ⚠️ 无权限的玩家仍可打开配置界面查看（只读）：模型、地址、温度、Key 来源都照常展示。""")
            .defineInRange("ai.permission.adminLevel", 3, 0, 4);

    private static final ModConfigSpec.ConfigValue<String> AI_PROVIDER = BUILDER
            .comment("""
                    接口协议类型，可选：
                        openai-compatible  OpenAI 兼容端点（DeepSeek / 通义千问 / 智谱 GLM / GPT 等）
                        anthropic          Anthropic Messages 端点（Claude 系列）
                      填未知值会回退到 openai-compatible，可用 /ai status 查看实际生效的协议。""")
            .define("ai.provider", "openai-compatible");

    private static final ModConfigSpec.ConfigValue<String> AI_BASE_URL = BUILDER
            .comment("""
                    API 基础地址（不含 /chat/completions）。末尾斜杠会被自动去掉。
                      openai-compatible 例：https://api.openai.com/v1、https://api.deepseek.com/v1
                      ollama 例：http://localhost:11434""")
            .define("ai.baseUrl", "https://api.openai.com/v1");

    private static final ModConfigSpec.ConfigValue<String> AI_MODEL = BUILDER
            .comment("模型名。留空时 /ai chat 会提示先配置模型")
            .define("ai.model", "");

    private static final ModConfigSpec.DoubleValue AI_TEMPERATURE = BUILDER
            .comment("采样温度")
            .defineInRange("ai.temperature", 0.7D, 0.0D, 2.0D);

    private static final ModConfigSpec.IntValue AI_MAX_TOKENS = BUILDER
            .comment("单次回复的最大生成 token")
            .defineInRange("ai.maxTokens", 1024, 1, 32768);

    private static final ModConfigSpec.IntValue AI_TIMEOUT_SECONDS = BUILDER
            .comment("单次请求超时（秒）")
            .defineInRange("ai.timeoutSeconds", 60, 1, 600);

    private static final ModConfigSpec.IntValue AI_RETRY_COUNT = BUILDER
            .comment("失败重试次数。只重试网络错误与 5xx，4xx 不重试（重试也不会变好，只会白花额度）")
            .defineInRange("ai.retryCount", 1, 0, 5);

    private static final ModConfigSpec.ConfigValue<String> AI_SYSTEM_PROMPT = BUILDER
            .comment("""
                    系统提示词。这里只放"人设与文风"，建议保留默认值即可。
                    ⚠️ 抑制编造的规则（事实来源、工具结果不是指令、权限不得假设、攻略不确定要承认）
                    不在这里，而是写在 ContextRenderer.contextRule() 里随每次请求下发 ——
                    这样它既不会被误删，改配置的人也改不坏。""")
            .define("ai.systemPrompt",
                    "你是 Minecraft 中的 AI 助手。用简体中文简明回答，不要输出 Markdown 标题或代码块。");

    private static final ModConfigSpec.BooleanValue AI_CHAT_PREFIX_ENABLED = BUILDER
            .comment("""
                    是否拦截玩家聊天。**默认关闭**：密钥是用户自费的，全量拦截等于每说一句话都烧钱，
                    还会把私人聊天发给第三方。开启后只有以 ai.chatPrefix 开头的消息才会触发 AI。""")
            .define("ai.chatPrefixEnabled", false);

    private static final ModConfigSpec.ConfigValue<String> AI_CHAT_PREFIX = BUILDER
            .comment("聊天触发前缀（仅当 ai.chatPrefixEnabled 为 true 时生效）")
            .define("ai.chatPrefix", "ai:");

    private static final ModConfigSpec.IntValue AI_REPLY_CHUNK_SIZE = BUILDER
            .comment("回复单页字符数。MC 单条聊天消息有长度上限，长回复必须分页")
            .defineInRange("ai.replyChunkSize", 200, 40, 1000);

    private static final ModConfigSpec.IntValue AI_REPLY_INTERVAL_TICKS = BUILDER
            .comment("分页之间的间隔（游戏刻），防止一次刷屏或触发垃圾包判定")
            .defineInRange("ai.replyIntervalTicks", 10, 1, 200);

    private static final ModConfigSpec.IntValue AI_REQUEST_COOLDOWN_SECONDS = BUILDER
            .comment("单玩家两次请求的最小间隔（秒），用于防止连点烧额度")
            .defineInRange("ai.requestCooldownSeconds", 3, 0, 300);

    private static final ModConfigSpec.IntValue AI_MAX_CONCURRENT_REQUESTS = BUILDER
            .comment("全局同时在途的 LLM 请求上限")
            .defineInRange("ai.maxConcurrentRequests", 4, 1, 64);

    private static final ModConfigSpec.IntValue AI_CONTEXT_ENTITY_RADIUS = BUILDER
            .comment("上下文里「附近实体」的扫描半径（格）")
            .defineInRange("ai.context.entityRadius", 16, 1, 128);

    private static final ModConfigSpec.IntValue AI_CONTEXT_ENTITY_LIMIT = BUILDER
            .comment("上下文里「附近实体」最多列几个（按距离排序）")
            .defineInRange("ai.context.entityLimit", 8, 0, 64);

    private static final ModConfigSpec.IntValue AI_CONTEXT_CONTAINER_RADIUS = BUILDER
            .comment("上下文里「附近容器（箱子等）」的扫描半径（格）")
            .defineInRange("ai.context.containerRadius", 16, 1, 128);

    private static final ModConfigSpec.IntValue AI_CONTEXT_CONTAINER_LIMIT = BUILDER
            .comment("上下文里「附近容器」最多列几个（按距离排序）")
            .defineInRange("ai.context.containerLimit", 5, 0, 64);

    private static final ModConfigSpec.BooleanValue AI_CONTEXT_STRUCTURE_ENABLED = BUILDER
            .comment("""
                    是否查询「最近的村庄」。关闭后 AI 无法回答村庄坐标。
                    ⚠️ 这个查询与 /locate structure 同源，代价不低（会按区块环逐圈搜索），
                    因此有半径上限与每玩家缓存；若实测造成服务端卡顿，请关掉此项。""")
            .define("ai.context.structureEnabled", true);

    private static final ModConfigSpec.IntValue AI_CONTEXT_STRUCTURE_RADIUS_CHUNKS = BUILDER
            .comment("村庄搜索半径（区块）。原版 /locate 默认 100，这里默认更保守")
            .defineInRange("ai.context.structureRadiusChunks", 32, 1, 200);

    private static final ModConfigSpec.IntValue AI_CONTEXT_STRUCTURE_CACHE_SECONDS = BUILDER
            .comment("每玩家村庄查询结果的缓存时间（秒），0 表示不缓存")
            .defineInRange("ai.context.structureCacheSeconds", 300, 0, 86400);

    private static final ModConfigSpec.IntValue AI_CONTEXT_INVENTORY_TOP_N = BUILDER
            .comment("背包摘要按物品聚合后最多列几项（按数量排序）")
            .defineInRange("ai.context.inventoryTopN", 8, 0, 64);

    private static final ModConfigSpec.IntValue AI_HISTORY_MAX_MESSAGES = BUILDER
            .comment("单个玩家保留的历史消息条数上限（超出从最旧开始丢）")
            .defineInRange("ai.history.maxMessages", 20, 2, 200);

    private static final ModConfigSpec.IntValue AI_HISTORY_MAX_CHARS = BUILDER
            .comment("单个玩家保留的历史字符总量上限，防止上下文膨胀")
            .defineInRange("ai.history.maxChars", 8000, 200, 200000);

    // ===== AI 长期记忆（T001-8）=====
    // 与 chat/ 下几个仅内存的 Store 不同：记忆的全部价值就在于跨会话，落盘是它的本体。
    // 隐私边界：记忆是「每位玩家自己的」，模型只能读写发起对话那个人的记忆文件。

    private static final ModConfigSpec.BooleanValue AI_MEMORY_ENABLED = BUILDER
            .comment("""
                    是否启用 AI 长期记忆。
                    开启后每玩家一份记忆文件（config/zuoyanmod/memory/<uuid>.json），
                    模型可用 memory_write / memory_search / memory_forget 自主维护，
                    每轮对话把最近的记忆注入系统提示词。
                    关闭后三样都不发生（工具不注册、不注入、不落盘），已存在的记忆文件保留不删。""")
            .define("ai.memory.enabled", true);

    private static final ModConfigSpec.IntValue AI_MEMORY_MAX_ENTRIES = BUILDER
            .comment("单个玩家的记忆条数上限，超出时挤掉最旧的一条。文件在服务端本地，条数越大越占磁盘与注入预算")
            .defineInRange("ai.memory.maxEntries", 100, 10, 500);

    private static final ModConfigSpec.IntValue AI_MEMORY_INJECT_COUNT = BUILDER
            .comment("""
                    每轮对话注入系统提示词的最近记忆条数。这是每次请求的固定 token 开销；
                    注入的是「最近」而不是「全部」，更早的记忆模型可以用 memory_search 查到。0 表示只存不注入。""")
            .defineInRange("ai.memory.injectCount", 15, 0, 50);

    // ===== 进服问候（AI「主动性」的第一刀）=====
    // 与记忆共用一条隐私立场：问候数据只发给发起者本人的会话，不广播。

    private static final ModConfigSpec.BooleanValue AI_GREETING_ENABLED = BUILDER
            .comment("""
                    玩家加入服务器时，是否让 AI 主动发一次简短问候（只发给该玩家）：
                    叫出名字，并结合服务端刚采集的数据（在线人数、TPS、他的游玩时长等）做一句话概况。
                    每次问候 = 1 次 LLM 调用；同一玩家的最小间隔由 ai.greeting.cooldownSeconds 控制。""")
            .define("ai.greeting.enabled", true);

    private static final ModConfigSpec.IntValue AI_GREETING_COOLDOWN_SECONDS = BUILDER
            .comment("同一玩家两次进服问候的最小间隔（秒），防止反复重连刷问候。0 表示每次进服都问候")
            .defineInRange("ai.greeting.cooldownSeconds", 600, 0, 86400);

    // ===== AI 工具调用（T001-5 第一刀：只读查询工具）=====
    // 工具让模型「按需主动查」，而不是把一切预先塞进上下文。
    // ⚠️ 开启后一次提问可能触发多次 LLM 往返，费用随之上升 —— 这是由 ai.toolMaxSteps 封顶的。

    private static final ModConfigSpec.BooleanValue AI_TOOL_CALLING_ENABLED = BUILDER
            .comment("""
                    是否允许模型调用只读查询工具（搜方块 / 搜实体）。
                    关闭后请求体里不会出现 tools 字段，行为与 T001-4 完全一致。""")
            .define("ai.toolCallingEnabled", true);

    private static final ModConfigSpec.IntValue AI_TOOL_MAX_STEPS = BUILDER
            .comment("""
                    单条消息最多几次 LLM 往返。这是**费用上限**：
                    4 表示最坏情况下这一问会发起 4 次计费调用（模型连续要求调用工具时）。
                    达到上限会明确告知玩家「答案可能不完整」，而不是装作查完了。""")
            .defineInRange("ai.toolMaxSteps", 4, 1, 16);

    private static final ModConfigSpec.IntValue AI_TOOL_MAX_RESULTS = BUILDER
            .comment("单次工具调用最多回传的结果条数（坐标点 / 实体个数），防止把整片区域塞进上下文")
            .defineInRange("ai.toolMaxResults", 10, 1, 64);

    private static final ModConfigSpec.IntValue AI_TOOL_MAX_SCAN_BLOCKS = BUILDER
            .comment("""
                    搜方块工具的工作量预算（方块位置扫描次数上限）。
                    工具在主线程同步执行、无法硬中断，所以只能把工作量本身限死；
                    达到预算即停止扫描，并在结果里标注「可能不完整」。""")
            .defineInRange("ai.toolMaxScanBlocks", 32768, 1024, 1048576);

    private static final ModConfigSpec.IntValue AI_TOOL_LOOP_TIMEOUT_SECONDS = BUILDER
            .comment("""
                    整个工具调用循环的墙钟预算（秒）。到点后不再发起下一次 LLM 调用，直接在当前步收尾。
                    ⚠️ 这不是「工具执行超时」：工具在主线程同步执行，无法硬中断，此处只控制循环总时长。""")
            .defineInRange("ai.toolLoopTimeoutSeconds", 120, 1, 600);

    private static final ModConfigSpec.BooleanValue AI_TOOL_CONTAINERS_READ_CONTENTS = BUILDER
            .comment("""
                    是否允许「查容器」工具读取箱内物品（按内容过滤，例如「附近哪个箱子有钻石」）。
                    ⚠️ 开启意味着箱内的物品名称会被发给你配置的第三方 API —— 多人服务器请知情。
                    关闭后只能按方块类型（箱子 / 木桶 …）查找，给出坐标但不看里面有什么。
                    注：这只影响模型主动调用的那个工具；每轮都会跑的上下文采集始终不读箱内物品。""")
            .define("ai.tool.containersReadContents", true);

    // ===== AI 管理员级工具（T002）=====
    // 「能不能用指令 / 能不能读全服信息」与「能不能改配置」是两件事，所以是两个旋钮：
    // 前者最高只能做成本人本来就能做的事（执行走玩家自己的权限集），默认放给 OP（2）；
    // 后者影响全服，默认要管理员（3，见 ai.permission.adminLevel）。

    private static final ModConfigSpec.IntValue AI_TOOL_ADMIN_LEVEL = BUILDER
            .comment("""
                    使用管理员级 AI 工具所需的最低权限等级。涉及两个工具：
                      server_info      读全服玩家名单、维度、坐标、TPS、白名单、种子
                      propose_command  提议执行服务器指令（真正执行还要玩家 /ai confirm 确认）
                      0 = 所有人   1 = 版主   2 = OP（默认）   3 = 管理员   4 = 服务器所有者
                    ⚠️ 达不到门槛的玩家连这两个工具都看到不到（直接不注册），模型不会去尝试它没有的工具。
                    ⚠️ server_info 的结果（含其他玩家的名字与坐标）会随请求发给你配置的第三方 API ——
                    公开服务器若嫌宽，把它调到 3 或 4。""")
            .defineInRange("ai.tool.adminLevel", 2, 0, 4);

    private static final ModConfigSpec.BooleanValue AI_TOOL_DANGEROUS_ENABLED = BUILDER
            .comment("""
                    是否允许 AI「提议」执行服务器指令（**默认关闭**）。
                    开启后的实际能力边界：
                      · AI 只能**提议**，不能执行；执行需要你在游戏内敲 /ai confirm 确认；
                      · 执行用的是**你自己的权限**，AI 做不了你本来就做不到的事（达不到权限的指令在提议阶段就被拒）；
                      · 可提议的指令范围＝你本人有权执行的任何指令，**包含 /stop /ban /give 这类**；
                      · 执行**不可撤销**，且每次提议/确认/拒绝都会写进服务端日志。
                    ⚠️ 指令文本会随请求发给你配置的第三方 API。高风险功能，请确认你理解后再打开。""")
            .define("ai.tool.dangerousEnabled", false);

    // ===== AI 建造（T001-6）=====
    // 与 ai.tool.dangerousEnabled 刻意分成两个开关：那个管「提议执行任意指令」（宽），
    // 这个管「提议建造一座建筑」（窄、形态固定、可整体撤销）。共用一个会让"只想要其中一个"的人无法表达。

    private static final ModConfigSpec.BooleanValue AI_BUILD_ENABLED = BUILDER
            .comment("""
                    是否允许 AI **提议**建造（默认关闭）。开启后的实际边界：
                      · AI 只能提议，不能直接建；需要玩家在游戏内 /ai confirm 确认；
                      · 建筑只能建在玩家面前、随其朝向旋转 —— AI 拿不到也不允许指定坐标，无法盖到别人家；
                      · 只能覆盖自然地形：目标位置若有箱子/机器/他人建筑等人工方块，整场拒绝并报出坐标；
                      · 禁止命令方块 / 结构方块 / 基岩 / 屏障 等会绕过权限或留下残留的方块；
                      · 分 tick 落地（不卡服），全程可用 /ai undo 撤销。
                    ⚠️ 蓝图内容（含用料清单）会随请求发给你配置的第三方 API。
                    ⚠️ 不消耗背包材料 —— 等价于给有权限的人开了一个刷建材的口子，公开服请知情。""")
            .define("ai.build.enabled", false);

    private static final ModConfigSpec.BooleanValue AI_BUILD_ALLOW_NON_NATURAL_TERRAIN = BUILDER
            .comment("""
                    是否放权 AI 对建筑/非自然地形的所有操作（默认关闭）。
                    关闭时（默认）：只能覆盖自然地形（泥土、石头、草、空气等），遇到人工方块整场拒绝，防止意外覆盖建筑；
                    开启后：放权 AI 修改或拆除人造建筑方块（如木板、石砖、玻璃等），允许执行拆除屋顶、定向清除、做旧装饰等操作。
                    仍受 /ai undo 撤销保护与不可破坏方块（基岩/末地传送门等）的硬性保护。""")
            .define("ai.build.allowNonNaturalTerrain", false);

    private static final ModConfigSpec.IntValue AI_BUILD_MAX_BLOCKS = BUILDER
            .comment("单次建造的体积上限（按包围盒格子数算，含空气）。它同时限定了覆盖检查的代价")
            .defineInRange("ai.build.maxBlocks", 4096, 1, 32768);

    private static final ModConfigSpec.IntValue AI_BUILD_BLOCKS_PER_TICK = BUILDER
            .comment("每 tick 放几个方块。4096 块按默认 64/tick 约 3 秒建完；调大更快，但更吃 TPS")
            .defineInRange("ai.build.blocksPerTick", 64, 1, 512);


    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * 字段键 → ModConfigSpec 值。图形化配置界面与游戏内命令都通过它写回配置，
     * 因此<b>字段表里出现过的每一项都必须在这里有对应条目</b>，否则那一项改了会落不下去。
     *
     * <p>键与 {@link AiConfigFields} 里的键同名（也就是 TOML 键），三处一致。
     */
    private static final Map<String, ModConfigSpec.ConfigValue<?>> AI_SPEC_VALUES = Map.ofEntries(
            Map.entry(AiConfigEdits.KEY_ENABLED, AI_ENABLED),
            Map.entry(AiConfigEdits.KEY_PROVIDER, AI_PROVIDER),
            Map.entry(AiConfigEdits.KEY_BASE_URL, AI_BASE_URL),
            Map.entry(AiConfigEdits.KEY_MODEL, AI_MODEL),
            Map.entry(AiConfigEdits.KEY_TEMPERATURE, AI_TEMPERATURE),
            Map.entry(AiConfigEdits.KEY_MAX_TOKENS, AI_MAX_TOKENS),
            Map.entry(AiConfigEdits.KEY_CHAT_PREFIX_ENABLED, AI_CHAT_PREFIX_ENABLED),
            Map.entry(AiConfigEdits.KEY_CHAT_PREFIX, AI_CHAT_PREFIX),
            Map.entry(AiConfigEdits.KEY_TOOL_CALLING_ENABLED, AI_TOOL_CALLING_ENABLED),
            Map.entry(AiConfigEdits.KEY_CONTAINERS_READ_CONTENTS, AI_TOOL_CONTAINERS_READ_CONTENTS),
            Map.entry("ai.permission.adminLevel", AI_PERMISSION_ADMIN_LEVEL),
            Map.entry("ai.tool.adminLevel", AI_TOOL_ADMIN_LEVEL),
            Map.entry("ai.tool.dangerousEnabled", AI_TOOL_DANGEROUS_ENABLED),
            Map.entry("ai.build.enabled", AI_BUILD_ENABLED),
            Map.entry("ai.build.allowNonNaturalTerrain", AI_BUILD_ALLOW_NON_NATURAL_TERRAIN),
            Map.entry("ai.build.maxBlocks", AI_BUILD_MAX_BLOCKS),
            Map.entry("ai.build.blocksPerTick", AI_BUILD_BLOCKS_PER_TICK),
            Map.entry("ai.timeoutSeconds", AI_TIMEOUT_SECONDS),
            Map.entry("ai.retryCount", AI_RETRY_COUNT),
            Map.entry("ai.systemPrompt", AI_SYSTEM_PROMPT),
            Map.entry("ai.replyChunkSize", AI_REPLY_CHUNK_SIZE),
            Map.entry("ai.replyIntervalTicks", AI_REPLY_INTERVAL_TICKS),
            Map.entry("ai.requestCooldownSeconds", AI_REQUEST_COOLDOWN_SECONDS),
            Map.entry("ai.maxConcurrentRequests", AI_MAX_CONCURRENT_REQUESTS),
            Map.entry("ai.toolMaxSteps", AI_TOOL_MAX_STEPS),
            Map.entry("ai.toolMaxResults", AI_TOOL_MAX_RESULTS),
            Map.entry("ai.toolMaxScanBlocks", AI_TOOL_MAX_SCAN_BLOCKS),
            Map.entry("ai.toolLoopTimeoutSeconds", AI_TOOL_LOOP_TIMEOUT_SECONDS),
            Map.entry("ai.context.entityRadius", AI_CONTEXT_ENTITY_RADIUS),
            Map.entry("ai.context.entityLimit", AI_CONTEXT_ENTITY_LIMIT),
            Map.entry("ai.context.containerRadius", AI_CONTEXT_CONTAINER_RADIUS),
            Map.entry("ai.context.containerLimit", AI_CONTEXT_CONTAINER_LIMIT),
            Map.entry("ai.context.structureEnabled", AI_CONTEXT_STRUCTURE_ENABLED),
            Map.entry("ai.context.structureRadiusChunks", AI_CONTEXT_STRUCTURE_RADIUS_CHUNKS),
            Map.entry("ai.context.structureCacheSeconds", AI_CONTEXT_STRUCTURE_CACHE_SECONDS),
            Map.entry("ai.context.inventoryTopN", AI_CONTEXT_INVENTORY_TOP_N),
            Map.entry("ai.history.maxMessages", AI_HISTORY_MAX_MESSAGES),
            Map.entry("ai.history.maxChars", AI_HISTORY_MAX_CHARS),
            Map.entry("ai.memory.enabled", AI_MEMORY_ENABLED),
            Map.entry("ai.memory.maxEntries", AI_MEMORY_MAX_ENTRIES),
            Map.entry("ai.memory.injectCount", AI_MEMORY_INJECT_COUNT),
            Map.entry("ai.greeting.enabled", AI_GREETING_ENABLED),
            Map.entry("ai.greeting.cooldownSeconds", AI_GREETING_COOLDOWN_SECONDS));

    public static boolean logDirtBlock;

    // 这一组值由 AiRuntime 汇总进 ai.core.config.AiConfig（不含密钥）。

    public static boolean aiEnabled = true;
    /** AI 管理门槛（0~4，默认 3=管理员）。判定见 {@code ai.AiPermissions}，每次判定现读，改配置无需重启。 */
    public static int aiPermissionAdminLevel = 3;
    public static String aiProvider = "openai-compatible";
    public static String aiBaseUrl = "https://api.openai.com/v1";
    public static String aiModel = "";
    public static double aiTemperature = 0.7D;
    public static int aiMaxTokens = 1024;
    public static int aiTimeoutSeconds = 60;
    public static int aiRetryCount = 1;
    public static String aiSystemPrompt =
            "你是 Minecraft 中的 AI 助手。用简体中文简明回答，不要输出 Markdown 标题或代码块。";
    public static boolean aiChatPrefixEnabled = false;
    public static String aiChatPrefix = "ai:";
    public static int aiReplyChunkSize = 200;
    public static int aiReplyIntervalTicks = 10;
    public static int aiRequestCooldownSeconds = 3;
    public static int aiMaxConcurrentRequests = 4;
    public static int aiContextEntityRadius = 16;
    public static int aiContextEntityLimit = 8;
    public static int aiContextContainerRadius = 16;
    public static int aiContextContainerLimit = 5;
    public static boolean aiContextStructureEnabled = true;
    public static int aiContextStructureRadiusChunks = 32;
    public static int aiContextStructureCacheSeconds = 300;
    public static int aiContextInventoryTopN = 8;
    public static int aiHistoryMaxMessages = 20;
    public static int aiHistoryMaxChars = 8000;

    // ===== AI 长期记忆（T001-8）=====
    public static boolean aiMemoryEnabled = true;
    public static int aiMemoryMaxEntries = 100;
    public static int aiMemoryInjectCount = 15;

    // ===== 进服问候 =====
    public static boolean aiGreetingEnabled = true;
    public static int aiGreetingCooldownSeconds = 600;

    // ===== AI 工具调用（T001-5）=====
    public static boolean aiToolCallingEnabled = true;
    public static int aiToolMaxSteps = 4;
    public static int aiToolMaxResults = 10;
    public static int aiToolMaxScanBlocks = 32768;
    public static int aiToolLoopTimeoutSeconds = 120;
    public static boolean aiToolContainersReadContents = true;
    /** 管理员级工具门槛（0~4，默认 2=OP）。 */
    public static int aiToolAdminLevel = 2;
    /** 危险级工具总开关（默认关）。开启后才注册 propose_command。 */
    public static boolean aiToolDangerousEnabled = false;
    /** AI 建造总开关（默认关）。开启后才注册 propose_build。 */
    public static boolean aiBuildEnabled = false;
    public static boolean aiBuildAllowNonNaturalTerrain = false;
    /** 单次建造体积上限（包围盒格子数）。 */
    public static int aiBuildMaxBlocks = 4096;
    /** 每 tick 放置预算。 */
    public static int aiBuildBlocksPerTick = 64;


    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /** 游戏内命令修改默认模型（NFR-03：支持游戏内命令修改配置）。 */
    public static boolean setAiModel(String model) {
        return applyAiSettings(Map.of(AiConfigEdits.KEY_MODEL, normalize(model)));
    }

    /** 游戏内命令切换 Provider。 */
    public static boolean setAiProvider(String provider) {
        return applyAiSettings(Map.of(AiConfigEdits.KEY_PROVIDER, normalize(provider)));
    }

    /**
     * 游戏内命令修改 API 基础地址。
     *
     * <p>这里只去首尾空白，不裁末尾斜杠 —— 让 {@code /ai status} 能如实显示用户填了什么；
     * 真正发请求前会由 {@code AiConfig} 统一归一化。
     */
    public static boolean setAiBaseUrl(String baseUrl) {
        return applyAiSettings(Map.of(AiConfigEdits.KEY_BASE_URL, normalize(baseUrl)));
    }

    /**
     * 图形化配置界面 / 命令：把若干 AI 字段写回配置，<b>只落盘一次</b>。
     *
     * <p><b>为什么批量</b>：{@code ConfigValue#set()} 只改内存、不落盘；而每次落盘都会 fire
     * {@code ModConfigEvent}。攒齐后只 {@code save()} 一次，避免「改十项触发十次配置事件」。
     *
     * <p><b>为什么先全部转换、再统一写入</b>：转换阶段可能因单个值不合法而失败，
     * 若边转边写就会留下"写进去一半"的配置 —— 那样玩家与 TOML 文件会对不上账。
     * 这里先攒成待写列表，任何一个失败就整批放弃，一个字段都不动。
     *
     * <p><b>为什么同步静态快照要重读整表</b>：{@code AiRuntime.readConfig()} 读的是
     * {@code Config} 的静态字段，而延迟重载在下一个 tick 才发生 —— 不先同步就会用旧值重建一次 Provider。
     * 与其逐个字段写一遍赋值（漏一项就静默不一致），不如统一调 {@link #syncAiSnapshot()} 重读。
     *
     * <p><b>为什么不在这里调 {@code AiRuntime.reload()}</b>：{@code save()} 已经 fire 了配置事件，
     * AiRuntime 会在下一个服务端 tick 用延迟重载统一处理 —— 那条路径同时避开了与
     * {@code Config.onLoad} 的监听顺序竞态（见 {@code AiRuntime.requestReload} 的说明），
     * 这里再显式 reload 一次反而会变成重载两次。
     *
     * @param values 字段键（TOML 键）→ 规范文本值；来自 {@code AiConfigEdits.parse} 或命令
     * @return 是否写入成功；失败（字段不在表中、值转不成类型、配置尚未加载）返回 false，
     *         由调用方给出一句可操作的提示，而不是把异常抛进命令处理链
     */
    public static boolean applyAiSettings(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        try {
            List<Map.Entry<ModConfigSpec.ConfigValue<?>, Object>> pending = new ArrayList<>(values.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                ModConfigSpec.ConfigValue<?> specValue = AI_SPEC_VALUES.get(entry.getKey());
                AiConfigFields.Field field = AiConfigFields.byKey(entry.getKey()).orElse(null);
                if (specValue == null || field == null) {
                    // 字段表与规格表不同步：宁可整批拒绝，也不要写进去一半
                    return false;
                }
                pending.add(Map.entry(specValue, toSpecValue(field.kind(), entry.getValue())));
            }

            pending.forEach(entry -> setSpecValue(entry.getKey(), entry.getValue()));
            syncAiSnapshot();
            SPEC.save();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 规范文本 → 配置层的实际类型。转不出来会抛，由 {@link #applyAiSettings} 整批放弃。 */
    private static Object toSpecValue(AiConfigFields.Kind kind, String text) {
        return switch (kind) {
            case BOOL -> Boolean.parseBoolean(text);
            case INT -> Integer.parseInt(text.strip());
            case DOUBLE -> Double.parseDouble(text.strip());
            case TEXT -> text == null ? "" : text;
        };
    }

    @SuppressWarnings("unchecked")
    private static void setSpecValue(ModConfigSpec.ConfigValue<?> specValue, Object value) {
        switch (specValue) {
            case ModConfigSpec.BooleanValue bool -> bool.set((Boolean) value);
            case ModConfigSpec.IntValue number -> number.set((Integer) value);
            case ModConfigSpec.DoubleValue decimal -> decimal.set((Double) value);
            default -> ((ModConfigSpec.ConfigValue<String>) specValue).set((String) value);
        }
    }

    /**
     * 把 AI 配置从规格表重读回静态字段。
     *
     * <p>两个调用点：配置加载/重载事件（{@code onLoad}），以及界面/命令写回配置之后立即同步
     * （理由见 {@link #applyAiSettings}）。抽成一个方法而不是各写一遍，是为了让"漏同步一个字段"
     * 这件事不可能发生 —— 那正是同时有 30 多项可配置项之后最容易出的错。
     */
    private static void syncAiSnapshot() {
        // ===== AI（T001）=====
        aiEnabled = AI_ENABLED.get();
        aiPermissionAdminLevel = AI_PERMISSION_ADMIN_LEVEL.get();
        aiProvider = AI_PROVIDER.get();
        aiBaseUrl = AI_BASE_URL.get();
        aiModel = AI_MODEL.get();
        aiTemperature = AI_TEMPERATURE.get();
        aiMaxTokens = AI_MAX_TOKENS.get();
        aiTimeoutSeconds = AI_TIMEOUT_SECONDS.get();
        aiRetryCount = AI_RETRY_COUNT.get();
        aiSystemPrompt = AI_SYSTEM_PROMPT.get();
        aiChatPrefixEnabled = AI_CHAT_PREFIX_ENABLED.get();
        aiChatPrefix = AI_CHAT_PREFIX.get();
        aiReplyChunkSize = AI_REPLY_CHUNK_SIZE.get();
        aiReplyIntervalTicks = AI_REPLY_INTERVAL_TICKS.get();
        aiRequestCooldownSeconds = AI_REQUEST_COOLDOWN_SECONDS.get();
        aiMaxConcurrentRequests = AI_MAX_CONCURRENT_REQUESTS.get();
        aiContextEntityRadius = AI_CONTEXT_ENTITY_RADIUS.get();
        aiContextEntityLimit = AI_CONTEXT_ENTITY_LIMIT.get();
        aiContextContainerRadius = AI_CONTEXT_CONTAINER_RADIUS.get();
        aiContextContainerLimit = AI_CONTEXT_CONTAINER_LIMIT.get();
        aiContextStructureEnabled = AI_CONTEXT_STRUCTURE_ENABLED.get();
        aiContextStructureRadiusChunks = AI_CONTEXT_STRUCTURE_RADIUS_CHUNKS.get();
        aiContextStructureCacheSeconds = AI_CONTEXT_STRUCTURE_CACHE_SECONDS.get();
        aiContextInventoryTopN = AI_CONTEXT_INVENTORY_TOP_N.get();
        aiHistoryMaxMessages = AI_HISTORY_MAX_MESSAGES.get();
        aiHistoryMaxChars = AI_HISTORY_MAX_CHARS.get();
        aiMemoryEnabled = AI_MEMORY_ENABLED.get();
        aiMemoryMaxEntries = AI_MEMORY_MAX_ENTRIES.get();
        aiMemoryInjectCount = AI_MEMORY_INJECT_COUNT.get();
        aiGreetingEnabled = AI_GREETING_ENABLED.get();
        aiGreetingCooldownSeconds = AI_GREETING_COOLDOWN_SECONDS.get();
        aiToolCallingEnabled = AI_TOOL_CALLING_ENABLED.get();
        aiToolMaxSteps = AI_TOOL_MAX_STEPS.get();
        aiToolMaxResults = AI_TOOL_MAX_RESULTS.get();
        aiToolMaxScanBlocks = AI_TOOL_MAX_SCAN_BLOCKS.get();
        aiToolLoopTimeoutSeconds = AI_TOOL_LOOP_TIMEOUT_SECONDS.get();
        aiToolContainersReadContents = AI_TOOL_CONTAINERS_READ_CONTENTS.get();
        aiToolAdminLevel = AI_TOOL_ADMIN_LEVEL.get();
        aiToolDangerousEnabled = AI_TOOL_DANGEROUS_ENABLED.get();
        aiBuildEnabled = AI_BUILD_ENABLED.get();
        aiBuildAllowNonNaturalTerrain = AI_BUILD_ALLOW_NON_NATURAL_TERRAIN.get();
        aiBuildMaxBlocks = AI_BUILD_MAX_BLOCKS.get();
        aiBuildBlocksPerTick = AI_BUILD_BLOCKS_PER_TICK.get();
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        syncAiSnapshot();
    }

    private Config() {}
}

