package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.build.analysis.BuildingClusterDetector;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.BuildPlan;
import org.gwfx.aiassisted.core.build.BuildingComponentClassifier;
import org.gwfx.aiassisted.core.build.BuildingComponentClassifier.ComponentType;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.gwfx.aiassisted.platform.RegistryLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具 {@code propose_dismantle_component}：提议拆除或替换面前建筑的指定构件（屋顶/立柱/门窗/墙体等）。
 *
 * <p>基于拓扑特征与材质分类器，智能定位建筑的指定部位（如整个屋顶或全部玻璃窗）。
 * 同样必须经过 {@code /ai confirm} 确认，并可通过 {@code /ai undo} 撤销还原。
 */
public final class ProposeDismantleTool {

    public static final String NAME = "propose_dismantle_component";

    private ProposeDismantleTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME,
                        "提议拆除或替换面前建筑的某个详细部位构件。你只能**提议**：真正拆除由玩家在游戏内输入 /ai confirm 确认。"
                                + "支持识别拆除：roof（屋顶）、pillar（立柱/横梁）、window（门窗/玻璃）、wall（主墙体）、floor（地坪）、decoration（装饰物）。"
                                + "action 可选 remove（拆除为空气，默认）或 replace（替换为指定方块）。")
                .stringParam("component_type", "要拆除的构件类型：roof、pillar、window、wall、floor、decoration 或 all", true)
                .stringParam("action", "操作类型：remove（拆除为空气，默认）或 replace（替换为指定方块）", false)
                .stringParam("replacement_block", "当 action 为 replace 时替换的目标方块 ID，如 minecraft:glass", false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config,
                              PendingBuildStore pendingBuilds, PendingCommandStore pendingCommands,
                              JsonObject args) {
        if (!config.buildEnabled()) {
            return ToolOutcome.error("本服务器未开启「AI 建造」（ai.build.enabled=false），你无法提议拆除构件。");
        }
        int level = AiPermissions.highestLevelFor(player);
        if (level < config.toolAdminLevel()) {
            return ToolOutcome.error("你的权限等级不足（需要管理等级 " + config.toolAdminLevel() + "），无法提议拆除建筑构件。");
        }
        if (!config.buildAllowNonNaturalTerrain()) {
            return ToolOutcome.error("本服务器未开启「放权建筑操作」（ai.build.allowNonNaturalTerrain=false，默认关闭）。由于拆除或修改建筑涉及人造方块，请告知玩家在 AI 配置界面的「更多设置 · AI 建造」中开启「放权建筑操作」。");
        }

        String rawType = ToolArgs.string(args, "component_type").orElse("roof").strip();
        ComponentType targetType = ComponentType.parse(rawType);
        String action = ToolArgs.string(args, "action").orElse("remove").strip().toLowerCase();
        String replaceWith = ToolArgs.string(args, "replacement_block").orElse("minecraft:air").strip();
        if ("remove".equals(action) || replaceWith.isEmpty()) {
            replaceWith = "minecraft:air";
        }

        if (!BuildPlan.isAir(replaceWith)) {
            ResourceLocation replaceId = ResourceLocation.tryParse(replaceWith);
            if (replaceId == null || !RegistryLookup.hasBlock(replaceId)) {
                return ToolOutcome.error("目标替换方块不存在或未注册：" + replaceWith);
            }
        }

        Optional<BuildingClusterDetector.BuildingStructure> buildingOpt =
                BuildingClusterDetector.detectTargetedBuilding(player, BuildingClusterDetector.DEFAULT_MAX_DISTANCE, config.buildMaxBlocks());
        if (buildingOpt.isEmpty()) {
            return ToolOutcome.error("未在你的视线前方（48 格内）探测到实体建筑。请靠近并对准你想拆除构件的建筑。");
        }

        BuildingClusterDetector.BuildingStructure building = buildingOpt.get();
        List<BuildingComponentClassifier.Voxel> voxels = new ArrayList<>(building.blockCount());
        for (Map.Entry<BlockPos, String> entry : building.blockIds().entrySet()) {
            BlockPos p = entry.getKey();
            voxels.add(new BuildingComponentClassifier.Voxel(p.getX(), p.getY(), p.getZ(), entry.getValue()));
        }

        Map<ComponentType, List<BuildingComponentClassifier.Voxel>> classified =
                BuildingComponentClassifier.classify(voxels);

        List<BuildingComponentClassifier.Voxel> targetVoxels = classified.get(targetType);
        if (targetVoxels == null || targetVoxels.isEmpty()) {
            return ToolOutcome.error("在面前建筑中未能识别出明显的【" + targetType.label() + "】构件。");
        }

        if (targetVoxels.size() > config.buildMaxBlocks()) {
            return ToolOutcome.error("构件包含的方块数（" + targetVoxels.size() + "）超过单次上限（"
                    + config.buildMaxBlocks() + " 块）。");
        }

        // 拆除时从上到下处理
        List<BuildingComponentClassifier.Voxel> sorted = new ArrayList<>(targetVoxels);
        sorted.sort((a, b) -> Integer.compare(b.y(), a.y()));

        List<BuildPlan.LocalBlock> localBlocks = new ArrayList<>(sorted.size());
        Map<String, Integer> foundCounts = new LinkedHashMap<>();

        for (BuildingComponentClassifier.Voxel v : sorted) {
            localBlocks.add(new BuildPlan.LocalBlock(v.x(), v.y(), v.z(), replaceWith));
            foundCounts.merge(v.blockId(), 1, Integer::sum);
        }

        boolean isRemove = BuildPlan.isAir(replaceWith);
        String actionName = isRemove ? "拆除" : ("替换为 " + replaceWith);
        String planName = "拆除构件【" + targetType.label() + "】（" + localBlocks.size() + " 块）";

        BuildPlan plan = new BuildPlan(planName, 0, 0, 0, localBlocks, Map.of(replaceWith, localBlocks.size()));

        pendingBuilds.propose(player.getUUID(), plan, BuildPlacement.absolute(), System.currentTimeMillis());
        pendingCommands.clear(player.getUUID());

        StringBuilder sb = new StringBuilder();
        sb.append("已识别并准备").append(actionName).append("建筑部位：【").append(targetType.label()).append("】\n");
        sb.append("- 涉及方块总量：").append(localBlocks.size()).append(" 块\n");
        sb.append("- 构件包含的方块组成：\n");
        foundCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(6)
                .forEach(e -> sb.append("  • ").append(e.getKey()).append(": ").append(e.getValue()).append(" 块\n"));
        sb.append("\n⚠️ 请在游戏内输入 /ai confirm 执行").append(isRemove ? "拆除" : "替换")
          .append("，输入 /ai cancel 取消。执行后可随时使用 /ai undo 撤销恢复。");

        return ToolOutcome.ok(sb.toString());
    }
}


