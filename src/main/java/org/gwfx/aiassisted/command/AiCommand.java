package org.gwfx.aiassisted.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.Config;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.build.McTerrainProbe;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.build.CoveragePlanner;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.context.ContextRenderer;
import org.gwfx.aiassisted.core.context.ContextSnapshot;
import org.gwfx.aiassisted.core.llm.ProviderRegistry;
import org.gwfx.aiassisted.secret.KeyStore;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code /ai} 命令组。
 *
 * <p>权限分级：{@code chat / clear / help} 对所有玩家开放；
 * {@code status / key / model / provider / baseurl / reload / debug} 走 {@link AiPermissions}——
 * 这几条会看到配置细节或改动全局行为，不该让普通玩家碰。
 *
 * <p>门槛由配置 {@code ai.permission.adminLevel}（0~4，默认 4）决定，<b>运行时生效</b>：
 * 谓词写成 {@code AiPermissions::allows} 是刻意的 —— Brigadier 只在注册时拿一次谓词对象，
 * 若在这里预先构造 {@code PermissionCheck}，改配置就必须重启服务端才认。
 *
 * <p><b>密钥处理</b>：{@code /ai key set} 的入参会写进加密文件，但<b>绝不</b>回显、绝不进日志。
 * 命令反馈里只说明「已保存」以及「更推荐用环境变量」。
 *
 * <p>⚠️ 1.20.1 的权限是等级制：{@code src.hasPermission(n)}（0=所有人…4=服务器所有者），
 * 而是 {@code Commands.hasPermission(PermissionCheck)}，等级常量在 {@code Commands.LEVEL_*}。
 */
public final class AiCommand {

    /** 危险级操作要留痕（谁确认了什么），所以这个类自己有一份日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    private AiCommand() {
    }

    /*
     * ⚠️ 参数类型必须用 greedyString，不能用 string —— 这是个踩过的坑。
     *
     * Brigadier 的 StringArgumentType.string() 在"不加引号"形式下只接受 word 字符集
     * [a-zA-Z0-9_.+-]，遇到 ':' 或 '/' 就截断，然后报
     *   "Expected whitespace to end one argument, but found trailing data"。
     * 后果：
     *   /ai baseurl https://api.deepseek.com/v1   ← URL 必带 ':' 和 '/'，直接填不进去
     *   /ai model llama3.1:8b                     ← Ollama 模型名带冒号
     *   /ai model deepseek/deepseek-chat          ← OpenRouter 风格带斜杠
     *   /ai key set <base64 含 + / = 的密钥>      ← 同样被截断
     * greedyString 会吃掉剩余全部输入，是这里唯一正确的选择。
     * 只有 provider（取值是固定几个 id）保持 string，让多打的词直接报解析错。
     */

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai")
                .then(Commands.literal("chat")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(AiCommand::chat)))
                .then(Commands.literal("clear")
                        .executes(AiCommand::clear))
                // T002：身份自查 + 危险级指令的确认/取消（都是玩家级，只影响自己）
                .then(Commands.literal("whoami")
                        .executes(AiCommand::whoami))
                .then(Commands.literal("confirm")
                        .executes(AiCommand::confirmPending))
                .then(Commands.literal("cancel")
                        .executes(AiCommand::cancelPending))
                // 撤销最近一次 AI 建造：门槛是"管理员级工具"那一档（ai.tool.adminLevel）
                .then(Commands.literal("undo")
                        .requires(AiCommand::canUseBuildTools)
                        .executes(AiCommand::undoBuild))
                .then(Commands.literal("goal")
                        .then(Commands.literal("set")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(AiCommand::setGoal)))
                        .then(Commands.literal("show")
                                .executes(AiCommand::showGoal))
                        .then(Commands.literal("clear")
                                .executes(AiCommand::clearGoal)))
                .then(Commands.literal("help")
                        .executes(AiCommand::help))
                .then(Commands.literal("status")
                        .requires(AiPermissions::allows)
                        .executes(AiCommand::status))
                .then(Commands.literal("reload")
                        .requires(AiPermissions::allows)
                        .executes(AiCommand::reload))
                .then(Commands.literal("model")
                        .requires(AiPermissions::allows)
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(AiCommand::setModel)))
                .then(Commands.literal("provider")
                        .requires(AiPermissions::allows)
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(AiCommand::setProvider)))
                .then(Commands.literal("baseurl")
                        .requires(AiPermissions::allows)
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(AiCommand::setBaseUrl)))
                .then(Commands.literal("debug")
                        .requires(AiPermissions::allows)
                        .then(Commands.literal("context")
                                .executes(AiCommand::debugContext)))
                .then(Commands.literal("key")
                        .requires(AiPermissions::allows)
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(AiCommand::setKey)))
                        .then(Commands.literal("clear")
                                .executes(AiCommand::clearKey)))
                .executes(AiCommand::help)
        );
    }

    // ===== 玩家可用 =====

    private static int chat(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String text = StringArgumentType.getString(context, "text");
        AiRuntime.get().chatService().request(player, text);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiRuntime.get().clearHistory(player.getUUID());
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.history.cleared"), false);
        return 1;
    }

    // ===== 当前任务目标（T001-5 第二刀）=====
    //
    // 权限：所有玩家。目标只影响发起者自己的请求，与 /ai chat、/ai clear 同级；
    // 它也不是「配置」—— 不写盘、不广播，服务端重启后清空（持久化归 T001-8）。

    /**
     * 设定目标。空白输入等价于清除。
     *
     * <p>回显的是<b>实际生效</b>的文本（可能被 {@code GoalStore} 截断），
     * 避免出现「我设了 500 字、发出去的只有 200 字」这种落差。
     */
    private static int setGoal(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String applied = AiRuntime.get().goals().set(player.getUUID(), StringArgumentType.getString(context, "text"));

        if (applied.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("ai.ai_assisted.goal.cleared"), false);
            return 1;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.goal.set", applied), false);
        sendLine(context.getSource(), "ai.ai_assisted.goal.hint");
        return 1;
    }

    private static int showGoal(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String goal = AiRuntime.get().goalOf(player.getUUID());

        if (goal.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("ai.ai_assisted.goal.none"), false);
            return 1;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.goal.show", goal), false);
        return 1;
    }

    private static int clearGoal(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiRuntime.get().goals().clear(player.getUUID());
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.goal.cleared"), false);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        sendLine(source, "ai.ai_assisted.help.header");
        sendLine(source, "ai.ai_assisted.help.chat");
        sendLine(source, "ai.ai_assisted.help.clear");
        sendLine(source, "ai.ai_assisted.help.goal");
        sendLine(source, "ai.ai_assisted.help.whoami");
        sendLine(source, "ai.ai_assisted.help.confirm");
        sendLine(source, "ai.ai_assisted.help.cancel");
        sendLine(source, "ai.ai_assisted.help.undo");
        sendLine(source, "ai.ai_assisted.help.keybind");
        sendLine(source, "ai.ai_assisted.help.help");
        sendLine(source, "ai.ai_assisted.help.status");
        sendLine(source, "ai.ai_assisted.help.provider");
        sendLine(source, "ai.ai_assisted.help.baseurl");
        sendLine(source, "ai.ai_assisted.help.model");
        sendLine(source, "ai.ai_assisted.help.key");
        sendLine(source, "ai.ai_assisted.help.debug");
        sendLine(source, "ai.ai_assisted.help.reload");
        return 1;
    }

    // ===== 玩家可用：身份自查与危险级指令确认（T002）=====

    /**
     * {@code /ai whoami}：显示自己的权限等级，以及两个门槛对自己的开放情况。
     *
     * <p>存在的意义是<b>可核对</b>：玩家能一眼看到"AI 眼里的我是什么身份"，
     * 于是"AI 说我没权限"这件事不再需要靠猜。
     */
    private static int whoami(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiConfig config = AiRuntime.get().config();
        int level = AiPermissions.highestLevelFor(player);
        int editLevel = AiPermissions.configuredLevel();

        context.getSource().sendSuccess(() -> Component.translatable("ai.ai_assisted.whoami.level",
                level, Component.translatable(levelKey(level))), false);
        context.getSource().sendSuccess(() -> Component.translatable("ai.ai_assisted.whoami.config",
                editLevel, verdict(level >= editLevel)), false);
        context.getSource().sendSuccess(() -> Component.translatable("ai.ai_assisted.whoami.tools",
                config.toolAdminLevel(), verdict(level >= config.toolAdminLevel()),
                Component.translatable(config.dangerousToolsEnabled()
                        ? "ai.ai_assisted.whoami.danger_on"
                        : "ai.ai_assisted.whoami.danger_off")), false);
        context.getSource().sendSuccess(() -> Component.translatable("ai.ai_assisted.whoami.build",
                verdict(level >= config.toolAdminLevel()),
                Component.translatable(config.buildEnabled()
                        ? "ai.ai_assisted.whoami.build_on"
                        : "ai.ai_assisted.whoami.build_off")), false);
        sendLine(context.getSource(), "ai.ai_assisted.whoami.hint");
        return 1;
    }

    /**
     * {@code /ai confirm}：执行自己那条待确认的指令。
     *
     * <p><b>这里是真正的授权点</b>：工具只负责"提议"，模型无法自己走到这一步。
     * 所以门禁要再查一遍 —— 提议之后玩家可能被降权，管理员也可能把危险级开关关掉了。
     */
    private static int confirmPending(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiRuntime runtime = AiRuntime.get();
        long now = System.currentTimeMillis();
        AiConfig config = runtime.config();
        int level = AiPermissions.highestLevelFor(player);

        // ===== 先看有没有待确认的建造（T001-6）=====
        // 建造比一条指令更"重"（会真的改一片世界），所以优先处理它。
        Optional<PendingBuildStore.Pending> pendingBuild =
                runtime.pendingBuilds().take(player.getUUID(), now);
        if (pendingBuild.isPresent()) {
            return startPendingBuild(context, runtime, config, level, pendingBuild.get());
        }

        Optional<PendingCommandStore.Pending> pending =
                runtime.pendingCommands().take(player.getUUID(), now);
        if (pending.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.command.none"));
            return 0;
        }
        PendingCommandStore.Pending record = pending.get();

        if (!config.dangerousToolsEnabled()) {
            audit(player, level, record.command(), "拒绝：危险级开关已关闭");
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.command.disabled"));
            return 0;
        }
        if (level < config.toolAdminLevel()) {
            audit(player, level, record.command(), "拒绝：权限不足");
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.command.low_level",
                    level, config.toolAdminLevel()));
            return 0;
        }

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.error.no_server"));
            return 0;
        }

        // 执行：用玩家自己的权限集 + 一个收集输出的 source。
        // 与玩家自己敲这条指令走的是同一条 dispatcher 路径 —— 我们不绕过任何权限或保护插件。
        CommandOutputCollector collector = new CommandOutputCollector();
        CommandSourceStack source = player.createCommandSourceStack()
                .withSource(collector)
                .withPermission(AiPermissions.highestLevelFor(player));
        server.getCommands().performPrefixedCommand(source, record.command());

        String output = collector.isEmpty()
                ? Component.translatable("ai.ai_assisted.command.no_output").getString()
                : collector.text();
        // 存成上下文事实：确认后我们刻意不再自动调一次模型，结果只能靠下一轮对话带回去
        runtime.lastCommands().record(player.getUUID(), record.command(), output, now);
        audit(player, level, record.command(), "已执行");

        context.getSource().sendSuccess(() -> Component.translatable(
                "ai.ai_assisted.command.executed", record.command()), false);
        // 换行在 MC 聊天里是硬换行（与 /ai debug context 的处理一致），整块一条消息发出去
        context.getSource().sendSuccess(() -> Component.literal(output), false);
        if (collector.truncated()) {
            sendLine(context.getSource(), "ai.ai_assisted.command.truncated");
        }
        return 1;
    }

    /** {@code /ai cancel}：丢掉自己那条待确认指令与待确认建造（两者互斥，但一起清更省事）。 */
    private static int cancelPending(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        long now = System.currentTimeMillis();
        AiRuntime runtime = AiRuntime.get();

        Optional<PendingBuildStore.Pending> build = runtime.pendingBuilds().take(player.getUUID(), now);
        Optional<PendingCommandStore.Pending> pending = runtime.pendingCommands().take(player.getUUID(), now);
        if (build.isEmpty() && pending.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.command.none"));
            return 0;
        }
        build.ifPresent(found -> audit(player, AiPermissions.highestLevelFor(player),
                "建造「" + found.name() + "」", "玩家取消"));
        pending.ifPresent(found -> audit(player, AiPermissions.highestLevelFor(player),
                found.command(), "玩家取消"));
        context.getSource().sendSuccess(() -> Component.translatable("ai.ai_assisted.command.cancelled_all"), false);
        return 1;
    }

    /**
     * {@code /ai undo}：撤销最近一次建造（T001-6）。
     *
     * <p><b>门禁只看 {@code ai.tool.adminLevel}，不看两个总开关</b>：开关管的是"制造新的改动"，
     * 而撤销只是回退我们自己刚才做的改动 —— 关掉开关不该把玩家唯一的补救路径也锁死。
     */
    private static int undoBuild(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Component reply = AiRuntime.get().builds().requestUndo(player);
        context.getSource().sendSuccess(() -> reply, false);
        return 1;
    }

    /**
     * 开始一场待确认的建造（{@code /ai confirm} 的建造分支）。
     *
     * <p>这里的每一步都是<b>授权点</b>：提议之后玩家可能被降权、管理员可能关掉开关、
     * 世界也可能变了（有人在目标位置放了东西）—— 所以门禁与覆盖检查都要重查一遍。
     */
    private static int startPendingBuild(CommandContext<CommandSourceStack> context, AiRuntime runtime,
                                         AiConfig config, int level, PendingBuildStore.Pending pending)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();

        if (!config.buildEnabled()) {
            audit(player, level, "建造「" + pending.name() + "」", "拒绝：建造开关已关闭");
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.build.closed"));
            return 0;
        }
        if (level < config.toolAdminLevel()) {
            audit(player, level, "建造「" + pending.name() + "」", "拒绝：权限不足");
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.command.low_level",
                    level, config.toolAdminLevel()));
            return 0;
        }

        // 覆盖复查：提议到现在这段时间里，目标位置可能被人放了东西
        CoveragePlanner.Result coverage = CoveragePlanner.check(
                pending.plan(), pending.placement(), new McTerrainProbe((ServerLevel) player.level()), config.buildAllowNonNaturalTerrain());
        if (coverage.unloadedChunk()) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.build.abort_unloaded"));
            return 0;
        }
        if (coverage.firstViolation() != null) {
            CoveragePlanner.Violation violation = coverage.firstViolation();
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.build.blocked",
                    violation.x() + " " + violation.y() + " " + violation.z(),
                    violation.currentBlockId()));
            if (!config.buildAllowNonNaturalTerrain()) {
                context.getSource().sendFailure(Component.translatable("ai.ai_assisted.build.blocked_hint"));
            }
            return 0;
        }

        audit(player, level, "建造「" + pending.name() + "」", "确认开始");
        Component reply = runtime.builds().start(player, pending.plan(), pending.placement(),
                config.buildBlocksPerTick());
        context.getSource().sendSuccess(() -> reply, false);
        return 1;
    }

    /**
     * 危险级操作的审计日志。
     *
     * <p>「不可撤销」的能力必须留下痕迹：谁提议的、谁确认的、执行了什么、结果如何。
     * 这是"任意指令"这一档唯一能事后追责的手段。
     */
    private static void audit(ServerPlayer player, int level, String command, String outcome) {
        LOGGER.info("[AI][危险] {}（等级 {}）{} /{}", player.getGameProfile().getName(), level, outcome, command);
    }

    private static Component verdict(boolean allowed) {
        return Component.translatable(allowed ? "ai.ai_assisted.whoami.allowed" : "ai.ai_assisted.whoami.denied");
    }

    /**
     * 建造/撤销命令的门槛判定。
     *
     * <p>与 {@link AiPermissions#allows} 的区别：那个判的是"能不能改 AI 配置"
     * （门槛 {@code ai.permission.adminLevel}），这个判的是"能不能用管理员级工具/建造"
     * （门槛 {@code ai.tool.adminLevel}）—— 两件事的门槛刻意不同，不能共用。
     */
    private static boolean canUseBuildTools(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            // 建造/撤销都是"某个玩家自己的状态"，控制台没有可撤销的东西
            return false;
        }
        return AiPermissions.highestLevelFor(player)
                >= AiRuntime.get().config().toolAdminLevel();
    }

    /**
     * 等级 → 文案键。
     *
     * <p>注意：{@code ContextRenderer} 里另有一份写死的中文等级名，那一份是给<b>模型</b>看的
     * （零 MC 依赖层用不了语言文件），这一份是给<b>玩家</b>看的。两处内容必须保持一致。
     */
    private static String levelKey(int level) {
        return level >= 0 && level <= 4 ? "ai.ai_assisted.level." + level : "ai.ai_assisted.level.unknown";
    }

    // ===== 管理类（门槛见配置 ai.permission.adminLevel）=====

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        AiRuntime runtime = AiRuntime.get();
        AiConfig config = runtime.config();

        sendLine(source, "ai.ai_assisted.status.header");
        source.sendSuccess(() -> Component.translatable("ai.ai_assisted.status.provider",
                config.provider().isBlank() ? "(空)" : config.provider(),
                runtime.effectiveProviderId()), false);
        source.sendSuccess(() -> Component.translatable("ai.ai_assisted.status.base_url", config.baseUrl()), false);
        source.sendSuccess(() -> Component.translatable("ai.ai_assisted.status.model",
                config.hasModel() ? config.model() : Component.translatable("ai.ai_assisted.status.model_unset")), false);

        KeyStore.Source keySource = runtime.keyStore().source();
        source.sendSuccess(() -> Component.translatable("ai.ai_assisted.status.key_source",
                Component.translatable(keySourceKey(keySource))), false);
        source.sendSuccess(() -> Component.translatable("ai.ai_assisted.status.key_file",
                runtime.keyStore().file().toString()), false);

        source.sendSuccess(() -> Component.translatable(config.chatPrefixEnabled()
                        ? "ai.ai_assisted.status.prefix_enabled"
                        : "ai.ai_assisted.status.prefix_disabled",
                config.chatPrefix()), false);
        source.sendSuccess(() -> Component.translatable(config.structureEnabled()
                        ? "ai.ai_assisted.status.structure_enabled"
                        : "ai.ai_assisted.status.structure_disabled",
                config.structureRadiusChunks()), false);
        source.sendSuccess(() -> Component.translatable("ai.ai_assisted.status.dangerous",
                Component.translatable(config.dangerousToolsEnabled()
                        ? "ai.ai_assisted.whoami.danger_on"
                        : "ai.ai_assisted.whoami.danger_off"),
                config.toolAdminLevel()), false);
        source.sendSuccess(() -> Component.translatable("ai.ai_assisted.status.build",
                Component.translatable(config.buildEnabled()
                        ? "ai.ai_assisted.whoami.build_on"
                        : "ai.ai_assisted.whoami.build_off"),
                config.buildMaxBlocks()), false);
        sendLine(source, "ai.ai_assisted.status.history_memory_only");
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        AiRuntime.get().reload();
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.reload.done"), true);
        return 1;
    }

    private static int setModel(CommandContext<CommandSourceStack> context) {
        String model = StringArgumentType.getString(context, "name").strip();
        // 写回 ModConfigSpec 并落盘（NFR-03：支持游戏内命令修改配置），同时立即生效
        if (!Config.setAiModel(model)) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.config.write_failed"));
            return 0;
        }
        AiRuntime.get().reload();
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.model.set", model), true);
        return 1;
    }

    private static int setProvider(CommandContext<CommandSourceStack> context) {
        String provider = StringArgumentType.getString(context, "id").strip();

        // 这里刻意不"静默回退到 mock"：配置被手改错了确实应当容错，
        // 但用户在命令里主动敲错拼写时，最好当场告诉他合法取值，别让他对着 mock 的回显发懵
        if (!ProviderRegistry.isKnown(provider)) {
            context.getSource().sendFailure(Component.translatable(
                    "ai.ai_assisted.provider.unknown",
                    provider,
                    String.join(" / ", ProviderRegistry.knownIds())));
            return 0;
        }
        if (!Config.setAiProvider(provider)) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.config.write_failed"));
            return 0;
        }
        AiRuntime.get().reload();
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.provider.set", provider), true);
        return 1;
    }

    private static int setBaseUrl(CommandContext<CommandSourceStack> context) {
        String url = StringArgumentType.getString(context, "url").strip();

        // 只做最基本的 scheme 校验：最常见的错误是只写了域名，
        // 那样会在发请求时才炸成一句难懂的"HTTP 请求构造失败"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.baseurl.invalid", url));
            return 0;
        }
        if (!Config.setAiBaseUrl(url)) {
            context.getSource().sendFailure(Component.translatable("ai.ai_assisted.config.write_failed"));
            return 0;
        }
        AiRuntime.get().reload();
        // 回显归一化之后的值，让用户看到末尾斜杠已被去掉
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.baseurl.set", AiRuntime.get().config().baseUrl()), true);
        return 1;
    }

    /**
     * 把本轮 {@code /ai chat} 会用的那份上下文原样打印给管理员。
     *
     * <p>存在的意义：mock 的回显看不出上下文内容，而「AI 说附近没箱子」这类问题的第一嫌疑
     * 就是上下文没采到。这个命令走的是与真实请求完全相同的采集路径，看到什么就是模型看到什么。
     */
    private static int debugContext(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ContextSnapshot snapshot = AiRuntime.get().collectContextFor(player);
        // 连当前目标一起渲染：/ai chat 真正发出去的就是「目标块 + 上下文块」，这里必须一致，
        // 否则排查「为什么它没按我的目标办事」时会看到一个与真实请求不同的东西
        String rendered = ContextRenderer.render(snapshot, AiRuntime.get().goalOf(player.getUUID()));

        sendLine(context.getSource(), "ai.ai_assisted.debug.context_header");
        // 换行在 MC 聊天里是硬换行（StringSplitter 对 '\n' 单独处理），整块可以一条消息发出去
        context.getSource().sendSuccess(() -> Component.literal(rendered), false);
        return 1;
    }

    private static int setKey(CommandContext<CommandSourceStack> context) {
        String apiKey = StringArgumentType.getString(context, "key");
        KeyStore keyStore = AiRuntime.get().keyStore();

        boolean persisted = keyStore.store(apiKey);
        if (!persisted) {
            // 目录只读等情况：退到内存，避免用户完全无法使用
            keyStore.setSessionOnly(apiKey);
        }
        AiRuntime.get().reload();

        context.getSource().sendSuccess(
                () -> Component.translatable(persisted
                        ? "ai.ai_assisted.key.saved"
                        : "ai.ai_assisted.key.session_only"), false);
        // 密钥本身绝不回显，只给一条「更安全的方式」提示
        sendLine(context.getSource(), "ai.ai_assisted.key.env_hint");
        return 1;
    }

    private static int clearKey(CommandContext<CommandSourceStack> context) {
        boolean deleted = AiRuntime.get().keyStore().clear();
        AiRuntime.get().reload();
        // deleted 是布尔值，直接当参数会渲染成 true/false，这里先翻成一句人话
        Component result = Component.translatable(deleted
                ? "ai.ai_assisted.key.cleared.file"
                : "ai.ai_assisted.key.cleared.none");
        context.getSource().sendSuccess(
                () -> Component.translatable("ai.ai_assisted.key.cleared", result), true);
        return 1;
    }

    // ===== 小工具 =====

    private static String keySourceKey(KeyStore.Source source) {
        return "ai.ai_assisted.key_source." + source.name().toLowerCase(Locale.ROOT);
    }

    private static void sendLine(CommandSourceStack source, String translationKey) {
        source.sendSuccess(() -> Component.translatable(translationKey), false);
    }
}

