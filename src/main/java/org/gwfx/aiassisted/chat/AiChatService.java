package org.gwfx.aiassisted.chat;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import org.gwfx.aiassisted.context.McContextCollector;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.core.agent.AgentLoop;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.agent.ToolRegistry;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.context.ChatHistory;
import org.gwfx.aiassisted.core.context.ContextRenderer;
import org.gwfx.aiassisted.core.context.ContextSnapshot;
import org.gwfx.aiassisted.core.greeting.GreetingPrompts;
import org.gwfx.aiassisted.core.llm.ChatMessage;
import org.gwfx.aiassisted.core.llm.ChatRequest;
import org.gwfx.aiassisted.core.llm.ChatResponse;
import org.gwfx.aiassisted.core.llm.LlmException;
import org.gwfx.aiassisted.core.llm.LlmProvider;
import org.gwfx.aiassisted.core.llm.ToolCall;
import org.gwfx.aiassisted.core.memory.MemoryStore;
import org.gwfx.aiassisted.core.text.KeyRedactor;
import org.gwfx.aiassisted.core.text.TextPager;
import org.gwfx.aiassisted.secret.KeyStore;
import org.gwfx.aiassisted.tool.AiTools;
import org.gwfx.aiassisted.tool.RecipeIndex;
import org.slf4j.Logger;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 聊天编排：校验 → 采集上下文 → 组装请求 → 驱动 Agent 循环 → 回主线程 → 分页下发。
 *
 * <p><b>线程模型</b>（本类最需要小心的地方）：
 * <ul>
 *   <li>{@link #request} 必须在<b>服务端主线程</b>调用（要读世界、要发消息）；</li>
 *   <li>网络等待发生在 {@code HttpTransport} 的线程池里，主线程不阻塞；</li>
 *   <li>T001-5 起变成「主线程 ↔ 传输线程」<b>交替</b>：模型要求调用工具时，
 *       循环会把执行投递回主线程（{@link #dispatchTool}），拿到结果再发起下一次请求；</li>
 *   <li>网络回调里<b>只做一件事</b>：{@code server.execute(...)} 把收尾投递回主线程，
 *       绝不在回调里碰世界或玩家；</li>
 *   <li>真正发包由 {@link ReplyDispatcher} 在 tick 里做。</li>
 * </ul>
 *
 * <p><b>费用控制</b>（用户自费，必须保守）：
 * <ul>
 *   <li>默认 {@code provider=mock}，没配模型/密钥时直接拒绝，不会静默联网；</li>
 *   <li>单玩家冷却 + 全局并发上限；</li>
 *   <li>工具调用有<b>往返步数上限</b>（{@code ai.toolMaxSteps}）与墙钟预算，到点如实收尾而不是继续烧钱；</li>
 *   <li>失败时区分「密钥无效 / 被限流 / 服务端故障 / 超时」，方便用户自己判断要不要重试。</li>
 * </ul>
 */
public final class AiChatService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final KeyRedactor redactor;
    private final KeyStore keyStore;
    private final McContextCollector collector;
    private final ChatSessionManager sessions;
    private final ReplyDispatcher dispatcher;
    private final GoalStore goals;
    private final RecipeIndex recipeIndex;
    /** 待确认执行的危险级指令（T002，仅内存）。 */
    private final PendingCommandStore pendingCommands;
    /** 最近一次确认执行的指令（T002，仅内存），进上下文供下一轮对话引用。 */
    private final LastCommandStore lastCommands;
    /** 待确认的建造方案（T001-6，仅内存）。 */
    private final PendingBuildStore pendingBuilds;
    /** 每玩家的长期记忆（T001-8，落盘）。由 AiRuntime 常驻持有并传入。 */
    private final MemoryStore memories;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();
    /** 每玩家最近一次进服问候的时间戳（冷却防重连刷问候；仅内存，重启即重置无妨）。 */
    private final Map<UUID, Long> lastGreetAt = new ConcurrentHashMap<>();

    private volatile AiConfig config;
    private volatile LlmProvider provider;

    public AiChatService(KeyRedactor redactor,
                         KeyStore keyStore,
                         McContextCollector collector,
                         ChatSessionManager sessions,
                         ReplyDispatcher dispatcher,
                         GoalStore goals,
                         RecipeIndex recipeIndex,
                         PendingCommandStore pendingCommands,
                         LastCommandStore lastCommands,
                         PendingBuildStore pendingBuilds,
                         MemoryStore memories,
                         AiConfig config,
                         LlmProvider provider) {
        this.redactor = redactor;
        this.keyStore = keyStore;
        this.collector = collector;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.goals = goals;
        this.recipeIndex = recipeIndex;
        this.pendingCommands = pendingCommands;
        this.lastCommands = lastCommands;
        this.pendingBuilds = pendingBuilds;
        this.memories = memories;
        this.config = config;
        this.provider = provider;
    }

    /** 待确认指令（{@code /ai confirm} / {@code /ai cancel} 用）。 */
    public PendingCommandStore pendingCommands() {
        return this.pendingCommands;
    }

    /** 最近一次确认执行的指令（供上下文与状态展示）。 */
    public LastCommandStore lastCommands() {
        return this.lastCommands;
    }

    /** 待确认的建造方案（{@code /ai confirm} 的建造分支用）。 */
    public PendingBuildStore pendingBuilds() {
        return this.pendingBuilds;
    }

    /** 每玩家的长期记忆（{@code /ai memory} 用）。 */
    public MemoryStore memories() {
        return this.memories;
    }

    /** 配置热重载：换快照与 Provider 实例，并让已有会话按新上限重新裁剪。 */
    public void reconfigure(AiConfig config, LlmProvider provider) {
        this.config = config;
        this.provider = provider;
        this.sessions.configure(config.historyMaxMessages(), config.historyMaxChars());
    }

    public AiConfig config() {
        return this.config;
    }

    public LlmProvider provider() {
        return this.provider;
    }

    /** 玩家退出：回收会话历史并丢掉未发完的分页。 */
    public void onPlayerLeave(UUID playerId) {
        this.sessions.forget(playerId);
        this.lastRequestAt.remove(playerId);
        this.dispatcher.clearFor(playerId);
        // 待确认指令跨会话没有意义（人都不在了），最近执行记录同理
        this.pendingCommands.clear(playerId);
        this.lastCommands.clear(playerId);
    }

    /** 处理一次玩家请求。必须在服务端主线程调用。 */
    public void request(ServerPlayer player, String rawText) {
        request(player, rawText, null);
    }

    /** 处理一次玩家请求，并在获得最终模型文字回复时触发回调。必须在服务端主线程调用。 */
    public void request(ServerPlayer player, String rawText, Consumer<String> onCompletion) {
        AiConfig currentConfig = this.config;
        String text = rawText == null ? "" : rawText.strip();

        if (text.isEmpty()) {
            send(player, Component.translatable("ai.ai_assisted.error.empty_input"));
            return;
        }
        if (!currentConfig.enabled()) {
            send(player, Component.translatable("ai.ai_assisted.error.disabled"));
            return;
        }
        if (!currentConfig.hasModel()) {
            send(player, Component.translatable("ai.ai_assisted.error.no_model"));
            return;
        }

        LlmProvider currentProvider = this.provider;
        if (currentConfig.requiresApiKey() && keyStore.resolved().isEmpty()) {
            send(player, Component.translatable("ai.ai_assisted.error.no_key"));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = currentConfig.requestCooldownSeconds() * 1000L;
        Long last = this.lastRequestAt.get(player.getUUID());
        if (last != null && now - last < cooldownMillis) {
            long remainingSeconds = (cooldownMillis - (now - last) + 999L) / 1000L;
            send(player, Component.translatable("ai.ai_assisted.error.cooldown", remainingSeconds));
            return;
        }
        if (this.inFlight.get() >= currentConfig.maxConcurrentRequests()) {
            send(player, Component.translatable("ai.ai_assisted.error.busy"));
            return;
        }

        // 26.3 的 ServerPlayer 上没有 getServer()，从所在 ServerLevel 取
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            send(player, Component.translatable("ai.ai_assisted.error.no_server"));
            return;
        }

        this.lastRequestAt.put(player.getUUID(), now);
        this.inFlight.incrementAndGet();

        // 采集、注册表与记忆读取都在主线程完成，且必须早于异步请求；
        // 这一整段到发起请求的任何同步异常都要把并发名额还回去 ——
        // 少还一次就永久占住一个名额，累计到上限后对话与进服问候都会被"并发已满"拒绝
        CompletableFuture<AgentLoop.Result> future;
        ChatHistory history;
        try {
            // 上下文采集必须在这里做：仍在主线程，且早于异步请求。
            // 最近一次确认执行的指令也在这里带进去 —— 它是"上一轮动作"的事实，属于上下文而非工具产物。
            ContextSnapshot snapshot = this.collector.collect(player, currentConfig,
                    this.lastCommands.recentFor(player.getUUID(), now));
            history = this.sessions.history(player.getUUID());

            // 工具注册表绑定到「发起命令的这个玩家」，所以每次请求现建，不能全局复用。
            // 关闭开关时用空注册表 —— 它的 specs() 为空，请求体里不会出现 tools 字段。
            // 管理员级工具还会按调用者等级（与危险级开关）二次裁剪，见 AiTools.registryFor。
            // 配方索引与记忆库相反：它们是跨请求复用才有意义的状态，由 AiRuntime 常驻持有、传进来。
            ToolRegistry tools = currentConfig.toolCallingEnabled()
                    ? AiTools.registryFor(player, currentConfig, this.recipeIndex,
                            this.pendingCommands, this.pendingBuilds, this.memories)
                    : ToolRegistry.empty();

            // 记忆读取在主线程完成（首次访问涉及一次读盘），与上下文采集同一段。
            // 玩家名字是记忆文件的元数据：每次对话顺手刷新，模型才知道「在跟谁说话」
            String memoryBlock;
            if (currentConfig.memoryEnabled()) {
                this.memories.rememberName(player.getUUID(), player.getGameProfile().name());
                memoryBlock = this.memories.renderForPrompt(player.getUUID(), currentConfig);
            } else {
                memoryBlock = "";
            }

            List<ChatMessage> messages = new ArrayList<>(history.messages());
            messages.add(ChatMessage.user(text));
            ChatRequest request = new ChatRequest(
                    currentConfig.model(),
                    buildSystemPrompt(currentConfig, snapshot, this.goals.goalOf(player.getUUID()), memoryBlock),
                    messages,
                    currentConfig.temperature(),
                    currentConfig.maxTokens(),
                    tools.specs());

            send(player, Component.translatable("ai.ai_assisted.thinking"));

            AgentLoop loop = new AgentLoop(
                    currentProvider,
                    tools,
                    currentConfig.toolMaxSteps(),
                    Duration.ofSeconds(currentConfig.toolLoopTimeoutSeconds()));
            // 工具执行必须回主线程：AgentLoop 的决策回调跑在传输线程上，不能直接碰世界
            AgentLoop.ToolCaller caller = call -> dispatchTool(server, tools, call);

            future = loop.run(request, caller);
        } catch (RuntimeException e) {
            // 同步抛出（例如 baseUrl 非法、采集器异常）时不会有 future，必须在这里把并发计数还回去
            this.inFlight.decrementAndGet();
            send(player, describeError(e));
            return;
        }

        future.whenComplete((result, error) ->
                // 回调只负责把收尾投递回主线程；这里绝不碰世界
                server.execute(() -> complete(player, history, text, result, error, onCompletion)));
    }

    /**
     * 把一次工具调用投递回<b>服务端主线程</b>执行，结果以 future 交给 {@link AgentLoop}。
     *
     * <p>为什么必须跳线程：工具要读世界（区块/实体），只能在主线程碰；
     * 而 AgentLoop 的决策回调运行在传输线程上（Provider 的 future 完成处）。
     * 这与「网络回调里只做 server.execute」是同一条铁律。
     */
    private CompletableFuture<ToolOutcome> dispatchTool(MinecraftServer server, ToolRegistry tools, ToolCall call) {
        CompletableFuture<ToolOutcome> result = new CompletableFuture<>();
        server.execute(() -> {
            // 这一行是 T001-5 真机验收要看的日志：能确认「模型确实按需查了，而不是瞎猜」
            LOGGER.info("[AI] 调用工具 {}({})", call.name(), call.argumentsJson());
            // ToolRegistry#invoke 内部已把未知工具名与实现异常都收敛成失败结果，这里不会再抛
            result.complete(tools.invoke(call));
        });
        return result;
    }

    /**
     * 玩家进服时的主动问候（「主动性」第一刀）。
     *
     * <p><b>为什么不是一次普通 {@link #request} </b>：问候是服务端发起的单次主动行为，
     * 不是一轮对话 —— 合成的驱动消息不该写进玩家的对话历史，回复不该分页排进同一队列，
     * 也不该消耗玩家的聊天冷却。所以这里走一条独立的轻量管线：
     * 主线程采集事实 → 单次 LLM 调用（无工具）→ 回主线程只发给该玩家。
     *
     * <p><b>所有前置不满足时静默返回</b>：这是主动行为，失败与跳过都不该打扰玩家
     * （报错只会让人莫名其妙）。冷却防止反复重连刷问候刷用户的 API 额度。
     * 必须在服务端主线程调用（{@code PlayerLoggedInEvent}）。
     */
    public void greetOnJoin(ServerPlayer player) {
        AiConfig currentConfig = this.config;
        // 对玩家静默，但对服务端日志要透明：跳过原因必须留痕（配置缺失这类问题
        // 否则只能靠猜 —— 曾有"为什么没问候"排查半天，结果是模型字段被清空了）
        String skip = null;
        if (!currentConfig.greetingEnabled()) {
            skip = "功能未开启（ai.greeting.enabled）";
        } else if (!currentConfig.enabled()) {
            skip = "AI 总开关未开启";
        } else if (!currentConfig.hasModel()) {
            skip = "模型未配置";
        } else if (currentConfig.requiresApiKey() && this.keyStore.resolved().isEmpty()) {
            skip = "密钥未配置";
        } else if (this.inFlight.get() >= currentConfig.maxConcurrentRequests()) {
            skip = "并发已满";
        }
        if (skip != null) {
            LOGGER.info("[AI] 进服问候跳过：{}（玩家 {}）", skip, player.getGameProfile().name());
            return;
        }
        long now = System.currentTimeMillis();
        Long last = this.lastGreetAt.get(player.getUUID());
        if (last != null && now - last < currentConfig.greetingCooldownSeconds() * 1000L) {
            LOGGER.debug("[AI] 进服问候跳过：冷却中（玩家 {}）", player.getGameProfile().name());
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }

        this.lastGreetAt.put(player.getUUID(), now);
        this.inFlight.incrementAndGet();

        // 事实采集与记忆读取都在主线程（与 request() 的采集段同一条铁律）
        List<String> facts = collectGreetingFacts(player, server);
        String memoryBlock = currentConfig.memoryEnabled()
                ? this.memories.renderForPrompt(player.getUUID(), currentConfig)
                : "";
        ChatRequest request = new ChatRequest(
                currentConfig.model(),
                GreetingPrompts.buildSystemPrompt(currentConfig,
                        GreetingPrompts.buildFactsBlock(facts), memoryBlock),
                List.of(ChatMessage.user(GreetingPrompts.userInstruction())),
                currentConfig.temperature(),
                currentConfig.maxTokens(),
                List.of());

        LlmProvider currentProvider = this.provider;
        CompletableFuture<ChatResponse> future;
        try {
            future = currentProvider.chat(request);
        } catch (RuntimeException e) {
            // 同步抛出（baseUrl 非法等）不会有 future，必须还回并发名额
            this.inFlight.decrementAndGet();
            LOGGER.debug("[AI] 进服问候发起失败：{}", e.toString());
            return;
        }
        future.whenComplete((response, error) ->
                server.execute(() -> finishGreeting(player, currentConfig, response, error)));
    }

    /** 问候的事实行（主线程采集）。刻意精选：只报"一句话概况"用得上的，不倒全服家底。 */
    private List<String> collectGreetingFacts(ServerPlayer player, MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        lines.add("玩家名: " + player.getGameProfile().name());
        lines.add("游玩总时长: " + GreetingPrompts.humanizePlaytime(
                player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME))));
        lines.add("历史死亡次数: " + player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)));
        lines.add("当前在线人数: " + server.getPlayerCount());
        double mspt = server.getCurrentSmoothedTickTime();
        double tps = mspt <= 50.0D ? 20.0D : Math.min(20.0D, 1000.0D / mspt);
        lines.add(String.format(Locale.ROOT, "服务器负载: 平均 %.1f ms/tick（约 %.1f TPS）", mspt, tps));
        lines.add("服务器已连续运行: " + GreetingPrompts.humanizePlaytime(server.getTickCount()));
        // 26.3 没有了 Level.getDayTime()：昼夜时钟独立成 WorldClock，主世界时钟即"游戏内天数"的计时源
        lines.add("游戏内天数: 第 " + (server.overworld().getOverworldClockTime() / 24000L + 1L) + " 天");
        return lines;
    }

    /** 问候收尾（已在主线程）。失败与空回复一律静默 —— 主动行为不该打扰玩家。 */
    private void finishGreeting(ServerPlayer player, AiConfig config, ChatResponse response, Throwable error) {
        this.inFlight.decrementAndGet();
        if (error != null) {
            LOGGER.debug("[AI] 进服问候失败：{}", describeError(error).getString(), error);
            return;
        }
        if (response == null) {
            return;
        }
        String reply = response.text().strip();
        if (reply.isEmpty()) {
            LOGGER.debug("[AI] 进服问候空回复：finish_reason={}", response.finishReason());
            return;
        }
        List<Component> components = new ArrayList<>();
        List<String> pages = TextPager.paginate(reply, config.replyChunkSize());
        for (int i = 0; i < pages.size(); i++) {
            components.add(i == 0
                    ? Component.translatable("ai.ai_assisted.reply_prefix").append(pages.get(i))
                    : Component.literal(pages.get(i)));
        }
        this.dispatcher.enqueue(player, components, config.replyIntervalTicks());
    }

    /**
     * 系统提示词 = 用户配置的提示词 + 事实来源规则 + （可选的）任务目标块 + 上下文数据块
     * + （可选的）长期记忆块。
     *
     * <p>目标块由 {@link ContextRenderer} 自己拼在 {@code <context>} <b>之前</b>：
     * 它是「引导行为」的，不能和「只是数据」的事实混在一个块里（理由见 ContextRenderer 的注释）。
     * 记忆块拼在 {@code <context>} <b>之后</b>：它同样承载行为引导（何时该记、何时该忘），
     * 且同样是「每玩家私有数据」，单独成块 + 配自己的规则最清晰。
     */
    private static String buildSystemPrompt(AiConfig config, ContextSnapshot snapshot, String goal, String memoryBlock) {
        StringBuilder prompt = new StringBuilder(config.systemPrompt().length() + 2400);
        if (!config.systemPrompt().isBlank()) {
            prompt.append(config.systemPrompt()).append("\n\n");
        }
        // 模型自我认知：配置的模型名/协议就是它的身份，防止它凭训练记忆自称别家模型
        prompt.append(ContextRenderer.identityStatement(config)).append("\n\n");
        prompt.append(ContextRenderer.contextRule()).append("\n\n");
        prompt.append(ContextRenderer.render(snapshot, goal));
        if (memoryBlock != null && !memoryBlock.isEmpty()) {
            prompt.append("\n\n").append(ContextRenderer.memoryRule()).append("\n\n");
            prompt.append(memoryBlock);
        }
        return prompt.toString();
    }

    /** 网络回调收尾（已在主线程）。 */
    private void complete(ServerPlayer player, ChatHistory history, String userText,
                          AgentLoop.Result result, Throwable error, Consumer<String> onCompletion) {
        this.inFlight.decrementAndGet();

        if (error != null) {
            send(player, describeError(error));
            return;
        }
        if (result == null) {
            send(player, Component.translatable("ai.ai_assisted.error.generic"));
            return;
        }
        if (!result.toolNames().isEmpty()) {
            LOGGER.debug("[AI] 本轮工具调用 {} 次：{}，收尾原因 {}",
                    result.toolNames().size(), result.toolNames(), result.stopReason());
        }

        AiConfig currentConfig = this.config;
        String reply = result.text().strip();

        // 工具调用链不写入历史：两家协议的 call id 跨轮次毫无意义，回放还可能被服务端拒绝，
        // 而且原始工具 JSON 会迅速吃光上下文预算。历史只留最终的一问一答（与 T001-4 一致）。
        if (!reply.isEmpty()) {
            history.add(ChatMessage.user(userText));
            history.add(ChatMessage.assistant(reply));
        }

        List<Component> components = new ArrayList<>();
        if (!reply.isEmpty()) {
            if (onCompletion != null) {
                try {
                    onCompletion.accept(reply);
                } catch (Throwable t) {
                    LOGGER.debug("[AI] onCompletion 回调异常: {}", t.getMessage());
                }
            }
            List<String> pages = TextPager.paginate(reply, currentConfig.replyChunkSize());
            for (int i = 0; i < pages.size(); i++) {
                components.add(i == 0
                        ? Component.translatable("ai.ai_assisted.reply_prefix").append(pages.get(i))
                        : Component.literal(pages.get(i)));
            }
        }

        // 没查完就收尾时必须如实说明，不能把半截回答当成完整答案（这是本刀的一条硬要求）
        switch (result.stopReason()) {
            case STEP_LIMIT -> components.add(Component.translatable(
                    "ai.ai_assisted.tool.step_limit", currentConfig.toolMaxSteps()));
            case TIMEOUT -> components.add(Component.translatable(
                    "ai.ai_assisted.tool.timeout", currentConfig.toolLoopTimeoutSeconds()));
            case COMPLETED -> {
            }
        }

        if (components.isEmpty()) {
            // 既没有正文、也不是半途收尾 → 等价于空回复。
            // 但"空回复"本身没法排查，所以要把 finish_reason 带出来：被 max_tokens 截断（length）
            // 与模型真的一句话没说，是两种完全不同的处置（前者玩家调配置即可，后者要查服务商）。
            LOGGER.info("[AI] 空回复：finish_reason={}，本次输出 {} tokens（上限 {}）",
                    result.finishReason(), result.completionTokens(), currentConfig.maxTokens());
            if (result.truncated()) {
                send(player, Component.translatable("ai.ai_assisted.error.truncated_reply",
                        currentConfig.maxTokens(), result.completionTokens()));
            } else if (!result.finishReason().isEmpty()) {
                send(player, Component.translatable("ai.ai_assisted.error.empty_reply_reason",
                        result.finishReason()));
            } else {
                send(player, Component.translatable("ai.ai_assisted.error.empty_reply"));
            }
            return;
        }
        if (result.hasUsage()) {
            components.add(Component.translatable("ai.ai_assisted.usage",
                    result.promptTokens(), result.completionTokens()));
        }

        // 分页按 tick 间隔逐条下发，避免刷屏
        this.dispatcher.enqueue(player, components, currentConfig.replyIntervalTicks());
    }

    /**
     * 把异常翻译成玩家能看懂、且<b>已脱敏</b>的一句话。
     *
     * <p>之所以细分这么多情况：LLM 失败的原因对用户很不一样 ——
     * 401 该去改密钥，429 该等一会或换模型，5xx 该稍后重试，超时该检查网络。
     * 一律回一句「请求失败」等于把排查成本全丢给用户。
     */
    private Component describeError(Throwable error) {
        Throwable cause = unwrap(error);

        if (cause instanceof LlmException llm) {
            if (llm.getCause() instanceof HttpTimeoutException) {
                return Component.translatable("ai.ai_assisted.error.timeout");
            }
            int status = llm.httpStatus();
            if (status == 401 || status == 403) {
                // 带上实际请求地址：401 最常见的真实原因不是"密钥打错了"，
                // 而是 provider/baseUrl/密钥三者不属于同一服务商（例如切了 DeepSeek 却忘了改 baseUrl）
                return Component.translatable("ai.ai_assisted.error.auth", redactor.redact(currentBaseUrl()));
            }
            if (status == 429) {
                return Component.translatable("ai.ai_assisted.error.rate_limited");
            }
            if (status >= 500) {
                return Component.translatable("ai.ai_assisted.error.server", status);
            }
            if (status > 0) {
                return Component.translatable("ai.ai_assisted.error.http",
                        status, redactor.redact(llm.getMessage()));
            }
            return Component.translatable("ai.ai_assisted.error.detail", redactor.redact(llm.getMessage()));
        }

        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        // 兜底日志：异常栈同样可能带 URL，统一过脱敏
        LOGGER.debug("[AI] 请求失败: {}", redactor.redact(detail));
        return Component.translatable("ai.ai_assisted.error.detail", redactor.redact(detail));
    }

    /** 当前生效的请求地址（供错误话术展示，提示用户三者是否同一服务商）。 */
    private String currentBaseUrl() {
        return this.config.baseUrl();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** 只发系统消息给发起者，不影响其他人。 */
    private static void send(ServerPlayer player, Component text) {
        if (player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(text);
        }
    }

    /** 供 {@code /ai status} 展示「密钥是否已配置、从哪来」，不返回密钥内容。 */
    public Optional<KeyStore.Source> keySource() {
        return Optional.of(this.keyStore.source());
    }
}

