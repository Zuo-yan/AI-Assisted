package org.gwfx.aiassisted.command;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.build.McTerrainProbe;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.build.CoveragePlanner;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * 确认执行与取消待办操作的业务处理器（供 /ai confirm 命令与语音自然对话共用）。
 */
public final class AiConfirmHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record Result(boolean success, Component chatMessage, String voiceFeedback) {
    }

    private AiConfirmHandler() {
    }

    /**
     * 检查玩家当前是否有正在等待确认的操作（建造或指令）。
     */
    public static boolean hasPending(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        AiRuntime runtime = AiRuntime.get();
        long now = System.currentTimeMillis();
        return runtime.pendingBuilds().peek(player.getUUID(), now).isPresent()
                || runtime.pendingCommands().peek(player.getUUID(), now).isPresent();
    }

    /**
     * 执行确认操作。
     */
    public static Result confirm(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return new Result(false, Component.translatable("ai.ai_assisted.error.generic"), "");
        }

        AiRuntime runtime = AiRuntime.get();
        long now = System.currentTimeMillis();
        AiConfig config = runtime.config();
        int level = AiPermissions.highestLevelFor(player.permissions());

        // 1. 优先处理建造方案确认
        Optional<PendingBuildStore.Pending> pendingBuild =
                runtime.pendingBuilds().take(player.getUUID(), now);
        if (pendingBuild.isPresent()) {
            return startBuild(player, runtime, config, level, pendingBuild.get());
        }

        // 2. 处理管理员指令确认
        Optional<PendingCommandStore.Pending> pendingCommand =
                runtime.pendingCommands().take(player.getUUID(), now);
        if (pendingCommand.isPresent()) {
            return executeCommand(player, runtime, config, level, pendingCommand.get(), now);
        }

        return new Result(false, Component.translatable("ai.ai_assisted.command.none"), "当前没有待确认的操作。");
    }

    /**
     * 执行取消操作。
     */
    public static Result cancel(ServerPlayer player) {
        if (player == null) {
            return new Result(false, Component.translatable("ai.ai_assisted.error.generic"), "");
        }

        AiRuntime runtime = AiRuntime.get();
        long now = System.currentTimeMillis();
        int level = AiPermissions.highestLevelFor(player.permissions());

        Optional<PendingBuildStore.Pending> build = runtime.pendingBuilds().take(player.getUUID(), now);
        Optional<PendingCommandStore.Pending> pending = runtime.pendingCommands().take(player.getUUID(), now);

        if (build.isEmpty() && pending.isEmpty()) {
            return new Result(false, Component.translatable("ai.ai_assisted.command.none"), "当前没有待取消的操作。");
        }

        build.ifPresent(b -> audit(player, level, "建造「" + b.name() + "」", "已取消"));
        pending.ifPresent(c -> audit(player, level, c.command(), "已取消"));

        return new Result(true, Component.translatable("ai.ai_assisted.command.cancelled_all"), "已为您取消该操作。");
    }

    private static Result startBuild(ServerPlayer player, AiRuntime runtime, AiConfig config,
                                     int level, PendingBuildStore.Pending pending) {
        if (!config.buildEnabled()) {
            audit(player, level, "建造「" + pending.name() + "」", "拒绝：建造开关已关闭");
            return new Result(false, Component.translatable("ai.ai_assisted.build.closed"), "建造功能开关已关闭。");
        }
        if (level < config.toolAdminLevel()) {
            audit(player, level, "建造「" + pending.name() + "」", "拒绝：权限不足");
            return new Result(false, Component.translatable("ai.ai_assisted.command.low_level",
                    level, config.toolAdminLevel()), "权限不足，无法执行建造。");
        }

        CoveragePlanner.Result coverage = CoveragePlanner.check(
                pending.plan(), pending.placement(), new McTerrainProbe(player.level()),
                config.buildAllowNonNaturalTerrain());

        if (coverage.unloadedChunk()) {
            return new Result(false, Component.translatable("ai.ai_assisted.build.abort_unloaded"), "目标区域区块未加载，建造已中止。");
        }
        if (coverage.firstViolation() != null) {
            CoveragePlanner.Violation violation = coverage.firstViolation();
            Component blocked = Component.translatable("ai.ai_assisted.build.blocked",
                    violation.x() + " " + violation.y() + " " + violation.z(),
                    violation.currentBlockId());
            return new Result(false, blocked, "目标位置存在阻挡方块，建造无法开始。");
        }

        audit(player, level, "建造「" + pending.name() + "」", "确认开始");
        Component reply = runtime.builds().start(player, pending.plan(), pending.placement(),
                config.buildBlocksPerTick());

        return new Result(true, reply, "已确认，开始建造" + pending.name() + "！");
    }

    private static Result executeCommand(ServerPlayer player, AiRuntime runtime, AiConfig config,
                                         int level, PendingCommandStore.Pending record, long now) {
        if (!config.dangerousToolsEnabled()) {
            audit(player, level, record.command(), "拒绝：危险级开关已关闭");
            return new Result(false, Component.translatable("ai.ai_assisted.command.disabled"), "危险级指令开关已关闭。");
        }
        if (level < config.toolAdminLevel()) {
            audit(player, level, record.command(), "拒绝：权限不足");
            return new Result(false, Component.translatable("ai.ai_assisted.command.low_level",
                    level, config.toolAdminLevel()), "权限不足，无法执行该指令。");
        }

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return new Result(false, Component.translatable("ai.ai_assisted.error.no_server"), "服务器不可用。");
        }

        CommandOutputCollector collector = new CommandOutputCollector();
        CommandSourceStack source = player.createCommandSourceStack()
                .withSource(collector)
                .withPermission(player.permissions());

        server.getCommands().performPrefixedCommand(source, record.command());

        String output = collector.isEmpty()
                ? Component.translatable("ai.ai_assisted.command.no_output").getString()
                : collector.text();

        runtime.lastCommands().record(player.getUUID(), record.command(), output, now);
        audit(player, level, record.command(), "已执行");

        Component msg = Component.translatable("ai.ai_assisted.command.executed", record.command());
        player.sendSystemMessage(msg);
        player.sendSystemMessage(Component.literal(output));

        return new Result(true, msg, "已为您确认执行指令！");
    }

    private static void audit(ServerPlayer player, int level, String command, String outcome) {
        LOGGER.info("[AI][危险] {}（等级 {}）{} /{}", player.getGameProfile().name(), level, outcome, command);
    }
}
