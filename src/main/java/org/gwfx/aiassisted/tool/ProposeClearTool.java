package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.build.McTerrainProbe;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.build.analysis.BuildingClusterDetector;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.BuildPlan;
import org.gwfx.aiassisted.core.build.ClearFilter;
import org.gwfx.aiassisted.core.build.CoveragePlanner;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.gwfx.aiassisted.platform.RegistryLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具 {@code propose_clear}：提议定向清除或替换方块（危险级）。
 *
 * <p>支持清除周围半径范围内的特定方块，或者只清除面前所指建筑中的特定方块。
 * 同样必须经过 {@code /ai confirm} 确认，并可通过 {@code /ai undo} 撤销还原。
 */
public final class ProposeClearTool {

    public static final String NAME = "propose_clear";

    private ProposeClearTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME,
                        "提议清除或替换指定范围内的方块。你只能**提议**：真正操作由玩家在游戏内输入 /ai confirm 确认。"
                                + "支持定向清除面前建筑内的方块，或清除玩家周围的某种方块（如杂草、泥土、圆石、水源等）。"
                                + "清除人造方块（圆石/木板等）需要服务器开启「放权建筑操作」，否则本工具会拒绝。"
                                + "replace_with 默认为 minecraft:air（即直接清除）。")
                .stringParam("scope", "范围模式：targeted_building（面前所指的建筑，推荐）或 around_player（玩家周围）", true)
                .stringParam("filter_blocks", "匹配的方块，支持逗号分隔或别名，例如 minecraft:cobblestone, dirt, leaves，或 all", true)
                .stringParam("replace_with", "替换为的目标方块 ID，留空或 minecraft:air 表示清除为空气", false)
                .integerParam("radius", "当 scope 为 around_player 时的半径（格，1~32，默认 10）", 1, 32, false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config,
                              PendingBuildStore pendingBuilds, PendingCommandStore pendingCommands,
                              JsonObject args) {
        if (!config.buildEnabled()) {
            return ToolOutcome.error("本服务器未开启「AI 建造」（ai.build.enabled=false），你无法提议修改世界方块。");
        }
        int level = AiPermissions.highestLevelFor(player);
        if (level < config.toolAdminLevel()) {
            return ToolOutcome.error("你的权限等级不足（需要管理等级 " + config.toolAdminLevel() + "），无法提议修改世界方块。");
        }

        String scope = ToolArgs.string(args, "scope").orElse("targeted_building").strip().toLowerCase();
        String filterExpr = ToolArgs.string(args, "filter_blocks").orElse("all");
        String replaceWith = ToolArgs.string(args, "replace_with").orElse("minecraft:air").strip();
        if (replaceWith.isEmpty()) {
            replaceWith = "minecraft:air";
        }
        int radius = ToolArgs.integer(args, "radius", 10, 1, 32);

        // 校验目标方块是否存在
        if (!BuildPlan.isAir(replaceWith)) {
            ResourceLocation replaceId = ResourceLocation.tryParse(replaceWith);
            if (replaceId == null || !RegistryLookup.hasBlock(replaceId)) {
                return ToolOutcome.error("目标替换方块不存在或未注册：" + replaceWith);
            }
        }

        ClearFilter filter = new ClearFilter(filterExpr);
        List<BlockPos> targetPositions = new ArrayList<>();
        Map<String, Integer> foundCounts = new LinkedHashMap<>();
        ServerLevel levelObj = (ServerLevel) player.level();

        if ("targeted_building".equals(scope)) {
            Optional<BuildingClusterDetector.BuildingStructure> buildingOpt =
                    BuildingClusterDetector.detectTargetedBuilding(player, BuildingClusterDetector.DEFAULT_MAX_DISTANCE, config.buildMaxBlocks());
            if (buildingOpt.isEmpty()) {
                return ToolOutcome.error("未在你的视线前方（48 格内）探测到实体建筑。请靠近并对准你想修改的建筑。");
            }
            BuildingClusterDetector.BuildingStructure building = buildingOpt.get();
            for (Map.Entry<BlockPos, String> entry : building.blockIds().entrySet()) {
                String id = entry.getValue();
                if (filter.matches(id)) {
                    targetPositions.add(entry.getKey());
                    foundCounts.merge(id, 1, Integer::sum);
                }
            }
        } else {
            // around_player
            BlockPos center = player.blockPosition();
            int maxBlocks = config.buildMaxBlocks();
            for (int dy = -radius; dy <= radius && targetPositions.size() < maxBlocks; dy++) {
                for (int dz = -radius; dz <= radius && targetPositions.size() < maxBlocks; dz++) {
                    for (int dx = -radius; dx <= radius && targetPositions.size() < maxBlocks; dx++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (!levelObj.hasChunkAt(pos)) {
                            continue;
                        }
                        BlockState state = levelObj.getBlockState(pos);
                        if (state.isAir() && BuildPlan.isAir(replaceWith)) {
                            continue;
                        }
                        String id = RegistryLookup.blockId(state.getBlock());
                        if (filter.matches(id)) {
                            targetPositions.add(pos);
                            foundCounts.merge(id, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        if (targetPositions.isEmpty()) {
            return ToolOutcome.error("在指定范围（" + scope + "）内未匹配到符合过滤条件（" + filterExpr + "）的方块。");
        }

        if (targetPositions.size() > config.buildMaxBlocks()) {
            return ToolOutcome.error("匹配到的方块数量（" + targetPositions.size() + "）超过单次上限（"
                    + config.buildMaxBlocks() + " 块）。请缩小半径或指定更精确的过滤词。");
        }

        // 按从上到下（清除）或原序构建清单
        targetPositions.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        List<BuildPlan.LocalBlock> localBlocks = new ArrayList<>(targetPositions.size());
        for (BlockPos pos : targetPositions) {
            localBlocks.add(new BuildPlan.LocalBlock(pos.getX(), pos.getY(), pos.getZ(), replaceWith));
        }

        boolean isRemove = BuildPlan.isAir(replaceWith);
        String actionName = isRemove ? "清除" : ("替换为 " + replaceWith);
        String planName = "方块" + (isRemove ? "清除" : "置换") + "（" + targetPositions.size() + " 块）";

        BuildPlan plan = new BuildPlan(planName, 0, 0, 0, localBlocks, Map.of(replaceWith, localBlocks.size()));

        // 覆盖检查与 /ai confirm 走的是同一套：清除/替换人造方块需要「放权建筑操作」。
        // 提前在这里拦下，玩家才不会等到确认时才收到一句莫名的"目标位置存在阻挡方块"
        CoveragePlanner.Result coverage = CoveragePlanner.check(plan, BuildPlacement.absolute(),
                new McTerrainProbe(levelObj), config.buildAllowNonNaturalTerrain());
        if (coverage.unloadedChunk()) {
            return ToolOutcome.error("目标区域的区块没有加载（你可能站在区块边缘），请让玩家走近一点再试。");
        }
        if (!coverage.ok() && coverage.firstViolation() != null) {
            CoveragePlanner.Violation violation = coverage.firstViolation();
            return ToolOutcome.error("清除/替换会改动非自然方块（人造建筑），当前未开启「放权建筑操作」，已拒绝：位置 "
                    + "(" + violation.x() + " " + violation.y() + " " + violation.z() + ") 上是 "
                    + violation.currentBlockId() + "。请让玩家在 AI 配置界面的「更多设置 · AI 建造」中开启"
                    + "「放权建筑操作」（ai.build.allowNonNaturalTerrain），或只清除自然方块（杂草/泥土/树叶等）。");
        }

        pendingBuilds.propose(player.getUUID(), plan, BuildPlacement.absolute(), System.currentTimeMillis());
        pendingCommands.clear(player.getUUID());

        StringBuilder sb = new LinkedHashMap<String, String>().isEmpty() ? new StringBuilder() : new StringBuilder();
        sb.append("已准备").append(actionName).append("方案：\n");
        sb.append("- 涉及方块总量：").append(targetPositions.size()).append(" 块\n");
        sb.append("- 匹配的主要方块：\n");
        foundCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(6)
                .forEach(e -> sb.append("  • ").append(e.getKey()).append(": ").append(e.getValue()).append(" 块\n"));
        sb.append("\n⚠️ 请在游戏内输入 /ai confirm 执行").append(isRemove ? "清除" : "替换")
          .append("，输入 /ai cancel 取消。执行后可随时使用 /ai undo 撤销恢复。");

        return ToolOutcome.ok(sb.toString());
    }
}

