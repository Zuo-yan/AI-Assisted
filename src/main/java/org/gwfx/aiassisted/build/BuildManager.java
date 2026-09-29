package org.gwfx.aiassisted.build;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.BuildPlan;
import org.gwfx.aiassisted.core.build.UndoLog;
import org.gwfx.aiassisted.core.build.UndoStore;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建造/撤销的作业队列与分 tick 推进（T001-6）。
 *
 * <p><b>为什么必须分 tick</b>：一次建造最多几千个方块。一口气放完会让服务端在这一 tick 里
 * 做几千次方块更新（含邻居更新与发包），玩家看到的是"整座建筑闪现 + 卡一下"。
 * 按预算摊到多个 tick 后，既有"从地里长出来"的观感，也不会把 TPS 打穿。
 *
 * <p><b>失败一律"停止并保留"而不是回滚</b>：区块卸载、方块不可用这类中断发生后，
 * 已经放下的部分<b>留在原地</b>，撤销日志照样入库 —— 玩家可以用 {@code /ai undo} 自己决定
 * 是留还是拆。自动回滚要额外实现一套"回滚失败怎么办"，收益远小于复杂度。
 *
 * <p>必须是服务端主线程（{@link #tick} 由 {@code ServerTickEvent}（END 阶段）调）。
 */
public final class BuildManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每隔多少 tick 扫一次过期的撤销记录（见 {@link UndoStore#sweep}）。 */
    private static final int SWEEP_INTERVAL_TICKS = 200;

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final UndoStore<BlockState> undos = new UndoStore<>();
    private int sweepCounter;

    /** 一场进行中的作业（放置或撤销）。两者的推进循环一样，只是每一步做的事不同。 */
    private static final class Job {

        private final UUID playerId;
        private final ServerLevel level;
        private final boolean restore;
        private final String name;
        private final BuildPlacement placement;
        private final List<BuildPlan.LocalBlock> blocks;
        private final List<UndoLog.Entry<BlockState>> entries;
        private final Map<String, BlockState> states;
        private final UndoLog<BlockState> log = new UndoLog<>();

        private int index;
        private int touched;
        private int skipped;
        private String abortReason;

        private Job(UUID playerId, ServerLevel level, boolean restore, String name, BuildPlacement placement,
                    List<BuildPlan.LocalBlock> blocks, List<UndoLog.Entry<BlockState>> entries,
                    Map<String, BlockState> states) {
            this.playerId = playerId;
            this.level = level;
            this.restore = restore;
            this.name = name;
            this.placement = placement;
            this.blocks = blocks;
            this.entries = entries;
            this.states = states;
        }

        private int total() {
            return this.restore ? this.entries.size() : this.blocks.size();
        }

        private boolean done() {
            return this.index >= total();
        }
    }

    /** 是否已有作业在跑（同一玩家同时只允许一场，避免两场作业抢同一片方块）。 */
    public boolean isBusy(UUID playerId) {
        return playerId != null && this.jobs.containsKey(playerId);
    }

    /**
     * 开始一场建造。
     *
     * @param blocksPerTick 每 tick 预算（来自配置）：只用于回执里的耗时估算 ——
     *                      改了 {@code ai.build.blocksPerTick} 之后这个时间必须跟着变，
     *                      否则玩家会以为配置没生效
     * @return 给玩家的回执（成功或拒绝都在里面）
     */
    public Component start(ServerPlayer player, BuildPlan plan, BuildPlacement placement, int blocksPerTick) {
        if (plan == null || plan.isEmpty()) {
            return Component.translatable("ai.ai_assisted.build.empty");
        }
        if (isBusy(player.getUUID())) {
            return Component.translatable("ai.ai_assisted.build.busy");
        }

        // 方块状态一次性解析：放在这里有三个好处 —— 失败能在"还没动任何方块"时暴露、
        // 每个方块的注册表查询只做一次、后面每 tick 的推进循环里没有任何注册表访问
        Map<String, BlockState> states = new LinkedHashMap<>();
        for (String blockId : plan.materialCounts().keySet()) {
            BlockState state = BuildExecutor.stateFor(blockId);
            if (state == null) {
                return Component.translatable("ai.ai_assisted.build.unknown_block", blockId);
            }
            states.put(blockId, state);
        }

        ServerLevel level = (ServerLevel) player.level();
        Job job = new Job(player.getUUID(), level, false, plan.name(), placement,
                plan.blocks(), List.of(), states);
        this.jobs.put(player.getUUID(), job);

        LOGGER.info("[AI][建造] {} 开始建造「{}」：{} 块", player.getGameProfile().getName(),
                job.name, plan.blockCount());
        return Component.translatable("ai.ai_assisted.build.started",
                job.name, plan.blockCount(), estimateSeconds(plan.blockCount(), blocksPerTick));
    }

    /**
     * 开始一场撤销（回退最近一次建造）。
     *
     * <p><b>门禁不在这里</b>：调用方（{@code /ai undo}）已按 {@code ai.tool.adminLevel} 判过。
     * 这里只负责"有没有可撤销的、能不能起作业"。
     */
    public Component requestUndo(ServerPlayer player) {
        if (isBusy(player.getUUID())) {
            return Component.translatable("ai.ai_assisted.build.busy");
        }
        Optional<UndoStore.Record<BlockState>> record =
                this.undos.take(player.getUUID(), System.currentTimeMillis());
        if (record.isEmpty()) {
            return Component.translatable("ai.ai_assisted.build.undo_none");
        }
        UndoStore.Record<BlockState> found = record.get();
        ServerLevel level = (ServerLevel) player.level();
        Job job = new Job(player.getUUID(), level, true, found.name(), null,
                List.of(), reverseForRestore(found.log().reversed()), Map.of());
        this.jobs.put(player.getUUID(), job);

        LOGGER.info("[AI][建造] {} 开始撤销「{}」：{} 格", player.getGameProfile().getName(),
                job.name, job.total());
        return Component.translatable("ai.ai_assisted.build.undo_started", job.name, job.total());
    }

    /** 1.20.1 目标是 Java 17：没有 {@code List#reversed()}，显式倒序拷贝（恢复要从新到旧）。 */
    private static <T> List<T> reverseForRestore(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /**
     * 每 tick 推进（由 {@code AiServerEvents} 的 {@code ServerTickEvent}（END 阶段）调用）。
     *
     * @param blocksPerTick 每 tick 的预算，来自配置
     */
    public void tick(MinecraftServer server, int blocksPerTick) {
        if (++this.sweepCounter >= SWEEP_INTERVAL_TICKS) {
            this.sweepCounter = 0;
            this.undos.sweep(System.currentTimeMillis());
        }
        if (this.jobs.isEmpty()) {
            return;
        }
        int budget = Math.max(1, blocksPerTick);
        for (Job job : new ArrayList<>(this.jobs.values())) {
            step(server, job, budget);
        }
    }

    /** 服务端停机：丢作业、只记日志。刻意不尝试回滚（见类注释）。 */
    public void abortAll(String reason) {
        for (Job job : this.jobs.values()) {
            LOGGER.info("[AI][建造] 停机丢弃作业「{}」（{}）：已改动 {} 格",
                    job.name, reason, job.touched);
        }
        this.jobs.clear();
    }

    // ===== 内部推进 =====

    private void step(MinecraftServer server, Job job, int budget) {
        for (int i = 0; i < budget && !job.done() && job.abortReason == null; i++) {
            if (job.restore) {
                stepRestore(job);
            } else {
                stepPlace(job);
            }
        }
        if (job.abortReason == null && !job.done()) {
            return;
        }
        finish(server, job);
    }

    private void stepPlace(Job job) {
        BuildPlan.LocalBlock block = job.blocks.get(job.index++);
        BlockState target = job.states.get(block.blockId());
        BuildPlacement.Position position = job.placement.toWorld(block.x(), block.y(), block.z());
        switch (BuildExecutor.placeOne(job.level, position, target, block.blockId(), job.log)) {
            case PLACED -> job.touched++;
            case UNCHANGED -> {
                // 已经是目标方块：不算改动、也不记撤销
            }
            case UNLOADED -> job.abortReason = Component.translatable(
                    "ai.ai_assisted.build.abort_unloaded").getString();
            case UNAVAILABLE -> job.abortReason = Component.translatable(
                    "ai.ai_assisted.build.abort_rejected").getString();
        }
    }

    private void stepRestore(Job job) {
        UndoLog.Entry<BlockState> entry = job.entries.get(job.index++);
        switch (BuildExecutor.restoreOne(job.level, entry)) {
            case RESTORED -> job.touched++;
            case SKIPPED_CHANGED -> job.skipped++;
            case SKIPPED_UNLOADED -> job.abortReason = Component.translatable(
                    "ai.ai_assisted.build.abort_unloaded").getString();
        }
    }

    /** 收尾：入库撤销记录、回执、审计日志。 */
    private void finish(MinecraftServer server, Job job) {
        this.jobs.remove(job.playerId);

        if (!job.restore) {
            // 中止时也入库：已经放下的部分必须能被撤销，否则玩家只能手动拆
            this.undos.put(job.playerId, job.log, job.name, System.currentTimeMillis());
        }

        ServerPlayer player = server.getPlayerList().getPlayer(job.playerId);
        if (player == null || player.hasDisconnected()) {
            LOGGER.info("[AI][建造] 「{}」结束（玩家已离线）：改动 {} 格{}",
                    job.name, job.touched, job.abortReason == null ? "" : "，原因：" + job.abortReason);
            return;
        }

        if (job.abortReason != null) {
            player.sendSystemMessage(Component.translatable(job.restore
                    ? "ai.ai_assisted.build.undo_aborted"
                    : "ai.ai_assisted.build.aborted", job.name, job.abortReason, job.touched));
            return;
        }
        if (job.restore) {
            player.sendSystemMessage(Component.translatable("ai.ai_assisted.build.undo_done",
                    job.name, job.touched, job.skipped));
            return;
        }
        player.sendSystemMessage(Component.translatable("ai.ai_assisted.build.done", job.name, job.touched));
        LOGGER.info("[AI][建造] {} 建造「{}」完成：{} 格", player.getGameProfile().getName(),
                job.name, job.touched);
    }

    /**
     * 估算耗时（秒）。
     *
     * <p>刻意按"每 tick 预算"算而不是拍一个数：改了 {@code ai.build.blocksPerTick} 之后，
     * 回执里的时间必须跟着变，否则玩家会以为配置没生效。
     */
    private static int estimateSeconds(int blockCount, int blocksPerTick) {
        int perTick = Math.max(1, blocksPerTick);
        return Math.max(1, (int) Math.ceil(blockCount / (double) perTick / 20.0D));
    }
}
