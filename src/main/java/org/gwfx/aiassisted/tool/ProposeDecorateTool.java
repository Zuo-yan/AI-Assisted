package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.build.analysis.BuildingClusterDetector;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.BuildPlan;
import org.gwfx.aiassisted.core.build.BuildingComponentClassifier;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.gwfx.aiassisted.platform.RegistryLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 工具 {@code propose_decorate}：提议为面前建筑进行增量细节装饰、材质混搭做旧或环境照明。
 *
 * <p>支持：
 * <ul>
 *   <li><b>weathering</b>：墙面材质混搭做旧（如石砖墙混入苔石砖、裂纹石砖；木板墙混入去皮木）</li>
 *   <li><b>lighting</b>：在外墙和入口悬挂照明灯笼</li>
 *   <li><b>detail</b>：外立面丰富与窗台/倒角修饰</li>
 * </ul>
 * 同样必须经过 {@code /ai confirm} 确认，并可通过 {@code /ai undo} 撤销还原。
 */
public final class ProposeDecorateTool {

    public static final String NAME = "propose_decorate";

    private ProposeDecorateTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME,
                        "提议为面前建筑进行增量修饰、外墙做旧混搭或添加氛围照明。你只能**提议**：真正操作由玩家在游戏内输入 /ai confirm 确认。"
                                + "style 可选：weathering（材质混搭做旧，将单调墙面混入裂纹/苔藓变种）、lighting（外立面悬挂灯笼照明）、detail（门窗倒角与层次细节）。")
                .stringParam("style", "修饰风格：weathering（默认）、lighting 或 detail", false)
                .stringParam("density", "装饰密度比例：low（约15%）、medium（约30%，默认）、high（约50%）", false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config,
                              PendingBuildStore pendingBuilds, PendingCommandStore pendingCommands,
                              JsonObject args) {
        if (!config.buildEnabled()) {
            return ToolOutcome.error("本服务器未开启「AI 建造」（ai.build.enabled=false），你无法提议建筑装饰。");
        }
        int level = AiPermissions.highestLevelFor(player);
        if (level < config.toolAdminLevel()) {
            return ToolOutcome.error("你的权限等级不足（需要管理等级 " + config.toolAdminLevel() + "），无法提议建筑装饰。");
        }
        if (!config.buildAllowNonNaturalTerrain()) {
            return ToolOutcome.error("本服务器未开启「放权建筑操作」（ai.build.allowNonNaturalTerrain=false，默认关闭）。由于建筑修饰涉及修改人造方块，请告知玩家在 AI 配置界面的「更多设置 · AI 建造」中开启「放权建筑操作」。");
        }

        String style = ToolArgs.string(args, "style").orElse("weathering").strip().toLowerCase();
        String densityStr = ToolArgs.string(args, "density").orElse("medium").strip().toLowerCase();
        double ratio = switch (densityStr) {
            case "low" -> 0.15;
            case "high" -> 0.50;
            default -> 0.30;
        };

        Optional<BuildingClusterDetector.BuildingStructure> buildingOpt =
                BuildingClusterDetector.detectTargetedBuilding(player, BuildingClusterDetector.DEFAULT_MAX_DISTANCE, config.buildMaxBlocks());
        if (buildingOpt.isEmpty()) {
            return ToolOutcome.error("未在你的视线前方（48 格内）探测到实体建筑。请靠近并对准你想修饰的建筑。");
        }

        BuildingClusterDetector.BuildingStructure building = buildingOpt.get();
        ServerLevel levelObj = (ServerLevel) player.level();
        Random random = new Random(building.seedPos().hashCode());
        List<BuildPlan.LocalBlock> placements = new ArrayList<>();
        Map<String, Integer> materialCounts = new LinkedHashMap<>();

        if ("lighting".equals(style)) {
            // 在外墙距离地面 2~4 格高、且外面是空气的位置悬挂灯笼
            for (Map.Entry<BlockPos, String> entry : building.blockIds().entrySet()) {
                BlockPos pos = entry.getKey();
                int relY = pos.getY() - building.minPos().getY();
                if (relY >= 2 && relY <= 4 && random.nextDouble() < (ratio * 0.4)) {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos out = pos.relative(dir);
                        if (levelObj.hasChunkAt(out) && levelObj.getBlockState(out).isAir()) {
                            // 下方也是空气时可以放灯笼
                            placements.add(new BuildPlan.LocalBlock(out.getX(), out.getY(), out.getZ(), "minecraft:lantern"));
                            materialCounts.merge("minecraft:lantern", 1, Integer::sum);
                            break;
                        }
                    }
                }
            }
        } else {
            // weathering 或 detail 默认做旧混搭
            for (Map.Entry<BlockPos, String> entry : building.blockIds().entrySet()) {
                BlockPos pos = entry.getKey();
                String currentId = entry.getValue();

                // 只修饰暴露在空气中的表面方块
                boolean exposed = false;
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = pos.relative(dir);
                    if (levelObj.hasChunkAt(neighbor) && levelObj.getBlockState(neighbor).isAir()) {
                        exposed = true;
                        break;
                    }
                }
                if (!exposed) {
                    continue;
                }

                if (random.nextDouble() < ratio) {
                    String replacement = getVariant(currentId, random);
                    if (replacement != null && !replacement.equals(currentId)) {
                        placements.add(new BuildPlan.LocalBlock(pos.getX(), pos.getY(), pos.getZ(), replacement));
                        materialCounts.merge(replacement, 1, Integer::sum);
                    }
                }
            }
        }

        if (placements.isEmpty()) {
            return ToolOutcome.error("未能找到合适的装饰点位，面前建筑的外立面可能未包含可识别的材质（如石砖、深板岩砖等）。");
        }

        String planName = "建筑修饰【" + style + "】（" + placements.size() + " 处）";
        BuildPlan plan = new BuildPlan(planName, 0, 0, 0, placements, materialCounts);

        pendingBuilds.propose(player.getUUID(), plan, BuildPlacement.absolute(), System.currentTimeMillis());
        pendingCommands.clear(player.getUUID());

        StringBuilder sb = new StringBuilder();
        sb.append("已准备建筑修饰方案（风格：").append(style).append("，密度：").append(densityStr).append("）：\n");
        sb.append("- 装饰与置换点位：").append(placements.size()).append(" 处\n");
        sb.append("- 涉及的新增与混搭材质：\n");
        materialCounts.forEach((mat, count) -> sb.append("  • ").append(mat).append(": ").append(count).append(" 块\n"));
        sb.append("\n⚠️ 请在游戏内输入 /ai confirm 执行装饰，输入 /ai cancel 取消。执行后可随时使用 /ai undo 撤销恢复。");

        return ToolOutcome.ok(sb.toString());
    }

    private static String getVariant(String blockId, Random rand) {
        if (blockId == null) return null;
        if (blockId.contains("stone_bricks")) {
            int roll = rand.nextInt(3);
            return switch (roll) {
                case 0 -> "minecraft:cracked_stone_bricks";
                case 1 -> "minecraft:mossy_stone_bricks";
                default -> "minecraft:cobblestone";
            };
        }
        if (blockId.contains("deepslate_bricks")) {
            return rand.nextBoolean() ? "minecraft:cracked_deepslate_bricks" : "minecraft:cobbled_deepslate";
        }
        if (blockId.contains("oak_planks")) {
            return rand.nextBoolean() ? "minecraft:stripped_oak_wood" : "minecraft:spruce_planks";
        }
        if (blockId.contains("cobblestone")) {
            return "minecraft:mossy_cobblestone";
        }
        return null;
    }
}


