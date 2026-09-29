package org.gwfx.aiassisted;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.gwfx.aiassisted.Config;
import org.gwfx.aiassisted.build.BuildManager;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.chat.AiChatService;
import org.gwfx.aiassisted.chat.ChatSessionManager;
import org.gwfx.aiassisted.chat.GoalStore;
import org.gwfx.aiassisted.chat.LastCommandStore;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.chat.ReplyDispatcher;
import org.gwfx.aiassisted.context.McContextCollector;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.context.ContextSnapshot;
import org.gwfx.aiassisted.core.http.HttpTransport;
import org.gwfx.aiassisted.core.http.JdkHttpTransport;
import org.gwfx.aiassisted.core.llm.LlmProvider;
import org.gwfx.aiassisted.core.llm.ProviderRegistry;
import org.gwfx.aiassisted.core.text.KeyRedactor;
import org.gwfx.aiassisted.secret.KeyStore;
import org.gwfx.aiassisted.tool.RecipeIndex;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 功能的进程级单例与生命周期入口。
 *
 * <p>持有：脱敏器、密钥存储、HTTP 传输、Provider 注册表与当前 Provider、
 * 上下文采集器、会话管理、聊天编排、分页调度。
 *
 * <p><b>生命周期</b>：
 * <ul>
 *   <li>首次 {@link #get()} 时构造（由命令注册事件触发），期间完成一次配置读取与 Provider 构建；</li>
 *   <li>{@link #reload()}：配置热重载 —— 重读密钥与 ModConfigSpec 静态值，
 *       重建 HTTP 传输 / Provider 并换掉聊天编排里的快照；</li>
 *   <li>{@link #shutdown()}：服务端停机时关闭 HTTP 线程池。</li>
 * </ul>
 *
 * <p>{@link #get()} 刻意做成惰性：{@code ModConfigEvent} 在模组加载期就会触发，
 * 那时还不该建线程池；因此配置事件只在实例已存在时才触发 reload（见 {@code AiServerEvents}）。
 */
public final class AiRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 建立 TCP 连接的超时；单次请求的整体超时由配置项 ai.timeoutSeconds 控制。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static volatile AiRuntime instance;

    /** 是否已经初始化（用于让加载期的配置事件不去创建实例）。 */
    public static boolean isInitialized() {
        return instance != null;
    }

    /** 取（必要时创建）单例。 */
    public static AiRuntime get() {
        AiRuntime current = instance;
        if (current == null) {
            synchronized (AiRuntime.class) {
                if (instance == null) {
                    instance = new AiRuntime();
                }
                current = instance;
            }
        }
        return current;
    }

    private final KeyRedactor redactor = new KeyRedactor();
    private final KeyStore keyStore;
    private final McContextCollector collector = new McContextCollector();
    private final ChatSessionManager sessions = new ChatSessionManager();
    private final ReplyDispatcher dispatcher = new ReplyDispatcher();
    /** 每玩家的当前任务目标（仅内存，见 GoalStore）。 */
    private final GoalStore goals = new GoalStore();
    /** 待确认执行的危险级指令（T002，仅内存）。 */
    private final PendingCommandStore pendingCommands = new PendingCommandStore();
    /** 最近一次确认执行的指令（T002，仅内存）。 */
    private final LastCommandStore lastCommands = new LastCommandStore();
    /** 待确认的建造方案（T001-6，仅内存）。 */
    private final PendingBuildStore pendingBuilds = new PendingBuildStore();
    /** 建造/撤销的作业队列（T001-6，分 tick 推进）。 */
    private final BuildManager builds = new BuildManager();
    /**
     * 配方反查索引：唯一一个「跨请求复用才有意义」的工具状态。
     *
     * <p>所以它在这里持有、常驻，而不是像注册表那样每次请求现建 —— 否则每个问题都要
     * 全量枚举一遍配方，代价白付。
     */
    private final RecipeIndex recipeIndex = new RecipeIndex();
    private final AiChatService chatService;

    private volatile AiConfig config;
    private volatile HttpTransport transport;
    private volatile ProviderRegistry providers;
    private volatile LlmProvider provider;

    /** 由配置事件置位、在下一个服务端 tick 处理（见 {@link #requestReload()}）。 */
    private volatile boolean reloadPending;

    private AiRuntime() {
        this.keyStore = new KeyStore(FMLPaths.CONFIGDIR.get(), this.redactor);

        this.config = readConfig();
        this.transport = new JdkHttpTransport(CONNECT_TIMEOUT, this.config.retryCount());
        this.providers = new ProviderRegistry(this.transport);
        this.provider = createProvider(this.providers, this.config);

        this.chatService = new AiChatService(
                this.redactor, this.keyStore, this.collector, this.sessions, this.dispatcher,
                this.goals, this.recipeIndex, this.pendingCommands, this.lastCommands,
                this.pendingBuilds, this.config, this.provider);

        LOGGER.info("[AI] 初始化完成：provider={}（配置值 '{}'），model={}",
                this.provider.id(), this.config.provider(),
                this.config.hasModel() ? this.config.model() : "(未设置)");
    }

    public AiConfig config() {
        return this.config;
    }

    public KeyStore keyStore() {
        return this.keyStore;
    }

    public KeyRedactor redactor() {
        return this.redactor;
    }

    public ProviderRegistry providers() {
        return this.providers;
    }

    public AiChatService chatService() {
        return this.chatService;
    }

    public ChatSessionManager sessions() {
        return this.sessions;
    }

    public ReplyDispatcher dispatcher() {
        return this.dispatcher;
    }

    /** 每玩家的当前任务目标（{@code /ai goal}）。 */
    public GoalStore goals() {
        return this.goals;
    }

    /** 取某玩家的当前目标；未设定返回空串。供 {@code /ai goal show} 与 {@code /ai debug context} 使用。 */
    public String goalOf(UUID playerId) {
        return this.goals.goalOf(playerId);
    }

    /** 实际生效的 Provider id。 */
    public String effectiveProviderId() {
        return this.provider.id();
    }

    /**
     * 请求一次延迟热重载（配置事件触发时使用）。
     *
     * <p>为什么不直接 reload：{@code ModConfigEvent} 上同时挂着 {@code Config.onLoad}（回填静态字段）
     * 与本类，两个监听器的执行顺序没有保证 —— 直接 reload 可能读到还没回填的旧值。
     * 推到下一个服务端 tick 处理即可绕开这个竞态。
     */
    public void requestReload() {
        this.reloadPending = true;
    }

    /** 服务端 tick：处理挂起的重载并推进分页队列。 */
    public void tick() {
        if (this.reloadPending) {
            this.reloadPending = false;
            reload();
        }
        this.dispatcher.drain();
    }

    /** 配置热重载：重读密钥与配置，重建传输与 Provider，并换掉聊天编排里的快照。 */
    public synchronized void reload() {
        AiConfig newConfig = readConfig();

        HttpTransport oldTransport = this.transport;
        JdkHttpTransport newTransport = new JdkHttpTransport(CONNECT_TIMEOUT, newConfig.retryCount());
        ProviderRegistry newRegistry = new ProviderRegistry(newTransport);
        LlmProvider newProvider = createProvider(newRegistry, newConfig);

        this.config = newConfig;
        this.transport = newTransport;
        this.providers = newRegistry;
        this.provider = newProvider;
        this.chatService.reconfigure(newConfig, newProvider);

        if (oldTransport != null) {
            oldTransport.close();
        }
        LOGGER.info("[AI] 配置已重载：provider={}，model={}，密钥来源={}",
                newProvider.id(),
                newConfig.hasModel() ? newConfig.model() : "(未设置)",
                this.keyStore.source());
    }

    /** 服务端停机：清空待发分页并关闭 HTTP 线程池。 */
    public synchronized void shutdown() {
        this.dispatcher.clear();
        HttpTransport currentTransport = this.transport;
        if (currentTransport != null) {
            currentTransport.close();
        }
        // 停机时丢弃进行中的建造/撤销作业：不尝试回滚（见 BuildManager 的类注释），只记日志
        this.builds.abortAll("服务端停机");
        LOGGER.info("[AI] 已关闭");
    }

    /** 玩家退出：回收会话、待发分页与当前目标。 */
    public void onPlayerLeave(UUID playerId) {
        this.chatService.onPlayerLeave(playerId);
        this.goals.clear(playerId);
        // 待确认建造跨会话没有意义（人都不在了）；撤销记录<b>不</b>清 —— 10 分钟内回来还能撤销
        this.pendingBuilds.clear(playerId);
    }

    /** 待确认的建造方案（{@code /ai confirm} 的建造分支）。 */
    public PendingBuildStore pendingBuilds() {
        return this.pendingBuilds;
    }

    /** 建造/撤销作业（分 tick 推进、{@code /ai undo}）。 */
    public BuildManager builds() {
        return this.builds;
    }

    /** 待确认执行的危险级指令（{@code /ai confirm} / {@code /ai cancel}）。 */
    public PendingCommandStore pendingCommands() {
        return this.pendingCommands;
    }

    /** 最近一次确认执行的指令（{@code /ai status} 与上下文展示）。 */
    public LastCommandStore lastCommands() {
        return this.lastCommands;
    }

    /**
     * 按 {@code /ai chat} 的同一条路径采集一次上下文。
     *
     * <p>供 {@code /ai debug context} 使用 —— 排查「AI 为什么说附近没箱子」时，
     * 必须看到与真实请求完全一致的那份快照，而不是另写一套采集逻辑。
     * <b>必须在服务端主线程调用。</b>
     */
    public ContextSnapshot collectContextFor(ServerPlayer player) {
        return this.collector.collect(player, this.config,
                this.lastCommands.recentFor(player.getUUID(), System.currentTimeMillis()));
    }

    /** 清空某位玩家的对话历史。 */
    public void clearHistory(UUID playerId) {
        this.sessions.clear(playerId);
        this.dispatcher.clearFor(playerId);
    }

    /**
     * 从 ModConfigSpec 的静态快照读出 {@link AiConfig}。
     *
     * <p>读的是 {@code Config} 的静态字段而不是 {@code SPEC.get()}，
     * 与仓库既有做法一致（{@code onLoad} 已把值回填到静态字段）。
     */
    private static AiConfig readConfig() {
        return new AiConfig(
                Config.aiEnabled,
                Config.aiProvider,
                Config.aiBaseUrl,
                Config.aiModel,
                Config.aiTemperature,
                Config.aiMaxTokens,
                Duration.ofSeconds(Config.aiTimeoutSeconds),
                Config.aiRetryCount,
                Config.aiSystemPrompt,
                Config.aiChatPrefixEnabled,
                Config.aiChatPrefix,
                Config.aiReplyChunkSize,
                Config.aiReplyIntervalTicks,
                Config.aiRequestCooldownSeconds,
                Config.aiMaxConcurrentRequests,
                Config.aiContextEntityRadius,
                Config.aiContextEntityLimit,
                Config.aiContextContainerRadius,
                Config.aiContextContainerLimit,
                Config.aiContextStructureEnabled,
                Config.aiContextStructureRadiusChunks,
                Config.aiContextStructureCacheSeconds,
                Config.aiContextInventoryTopN,
                Config.aiHistoryMaxMessages,
                Config.aiHistoryMaxChars,
                Config.aiToolCallingEnabled,
                Config.aiToolMaxSteps,
                Config.aiToolMaxResults,
                Config.aiToolMaxScanBlocks,
                Config.aiToolLoopTimeoutSeconds,
                Config.aiToolContainersReadContents,
                Config.aiPermissionAdminLevel,
                Config.aiToolAdminLevel,
                Config.aiToolDangerousEnabled,
                Config.aiBuildEnabled,
                Config.aiBuildMaxBlocks,
                Config.aiBuildBlocksPerTick);
    }

    private LlmProvider createProvider(ProviderRegistry registry, AiConfig currentConfig) {
        String requested = currentConfig.provider();
        String effective = currentConfig.effectiveProvider();
        if (!requested.isBlank() && !requested.strip().toLowerCase(Locale.ROOT).equals(effective)) {
            LOGGER.warn("[AI] 未知的 provider '{}'，已回退到 '{}'", this.redactor.redact(requested), effective);
        }

        Optional<String> apiKey = this.keyStore.resolved();
        ProviderRegistry.ProviderSettings settings = new ProviderRegistry.ProviderSettings(
                currentConfig.baseUrl(), apiKey.orElse(""), currentConfig.timeout(), currentConfig.retryCount());
        return registry.create(effective, settings);
    }
}
