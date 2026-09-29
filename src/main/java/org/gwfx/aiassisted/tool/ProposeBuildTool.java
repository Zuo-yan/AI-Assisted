package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.build.McTerrainProbe;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.build.AsciiPreview;
import org.gwfx.aiassisted.core.build.BlueprintParser;
import org.gwfx.aiassisted.core.build.BlueprintValidator;
import org.gwfx.aiassisted.core.build.BuildBlueprint;
import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.BuildPlan;
import org.gwfx.aiassisted.core.build.CoveragePlanner;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.gwfx.aiassisted.platform.RegistryLookup;

import java.util.List;
import java.util.Optional;

/**
 * 工具 {@code propose_build}：<b>提议</b>建造一座由你设计的建筑（T001-6，危险级）。
 *
 * <p><b>它一个方块都不写</b>。调用本工具的全部结果是：校验 → 算落点 → 存一份待确认方案 →
 * 把分层字符画发给玩家。真正的放置要玩家敲 {@code /ai confirm}。
 * 名字里的 propose 是刻意的，和 {@code propose_command} 同族。
 *
 * <p><b>模型不能选坐标</b>：蓝图只有相对坐标，落点由"玩家站在哪、面朝哪"决定
 * （见 {@link BuildPlacement}）。否则模型可以把房子盖到别人家。
 *
 * <p><b>校验顺序</b>（任一步失败都整场拒绝，并给模型一句"该怎么改"）：
 * 结构自洽 → 黑名单与体积（core）→ 注册表存在性（MC）→ 覆盖检查（MC，白名单式自然地形）。
 *
 * <p>必须在服务端主线程调用（要读世界做覆盖检查）。
 */
public final class ProposeBuildTool {

    public static final String NAME = "propose_build";

    /** 聊天里最多渲染多少行字符画；超过就只发摘要与图例（否则会刷屏几十条消息）。 */
    private static final int MAX_PREVIEW_LINES = 40;

    /** 字符画每行最多显示多少字符。 */
    private static final int MAX_PREVIEW_WIDTH = 32;

    /** 原点沿面朝方向的偏移（格）：让玩家站在建筑外面，不用在建完后再把人推出去。 */
    private static final int ORIGIN_OFFSET = 2;

    private ProposeBuildTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME,
                        "设计并提议建造一座建筑。你只能**提议**：真正建造由玩家在游戏内用 /ai confirm 确认。"
                                + "用分层字符画描述：layers 的每一项是一层（先地基后屋顶），层内的行用 ; 分隔；"
                                + "所有行必须等长（=宽），每层行数相同（=深）。"
                                + "palette 把字符映射到完整方块 id（形如 minecraft:oak_planks），空气写 .。"
                                + "行里不能有空格。禁止使用命令方块/结构方块/基岩/屏障等方块。"
                                + "建筑会建在玩家面前并随他的朝向旋转，你不需要也不允许指定坐标。")
                .stringParam("name", "建筑名，会显示给玩家，例如「小木屋」", false)
                .arrayParam("palette", "字符到方块的映射，每项形如 W=minecraft:oak_planks，空气写 .=minecraft:air", true)
                .arrayParam("layers", "每项是一层，层内多行用 ; 分隔，所有行等长；先地基后屋顶", true)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config,
                              PendingBuildStore pendingBuilds, PendingCommandStore pendingCommands,
                              JsonObject args) {
        if (!config.buildEnabled()) {
            return ToolOutcome.error("本服务器未开启「AI 建造」（ai.build.enabled=false），你无法提议建造。");
        }
        int level = AiPermissions.highestLevelFor(player.permissions());
        if (level < config.toolAdminLevel()) {
            return ToolOutcome.error("发起者权限等级为 " + level + "，低于本功能要求的 "
                    + config.toolAdminLevel() + " 级，无法提议建造。");
        }
        if (pendingBuilds == null) {
            return ToolOutcome.error("建造服务不可用。");
        }

        Optional<List<String>> palette = ToolArgs.stringArray(args, "palette");
        Optional<List<String>> layers = ToolArgs.stringArray(args, "layers");
        if (palette.isEmpty() || layers.isEmpty()) {
            return ToolOutcome.error("缺少 palette 或 layers：两者都必须是字符串数组。");
        }
        String name = ToolArgs.string(args, "name").orElse("");

        // ① 结构自洽
        BlueprintParser.Result parsed = BlueprintParser.parse(name, palette.get(), layers.get());
        if (!parsed.ok()) {
            return ToolOutcome.error("施工图格式有问题：" + parsed.error());
        }
        BuildBlueprint blueprint = parsed.blueprint();

        // ② 黑名单与体积
        Optional<String> rejected = BlueprintValidator.validate(blueprint, config.buildMaxBlocks());
        if (rejected.isPresent()) {
            return ToolOutcome.error(rejected.get());
        }

        // ③ 注册表存在性（core 层只能看形状，能不能真的放下去要问注册表）
        for (String blockId : blueprint.palette().values()) {
            if (!BuildPlan.isAir(blockId) && !RegistryLookup.hasBlock(Identifier.tryParse(blockId))) {
                return ToolOutcome.error("方块「" + blockId + "」在本服务器上不存在，请换一种材料。");
            }
        }

        BuildPlan plan = BuildPlan.from(blueprint);
        if (plan.isEmpty()) {
            return ToolOutcome.error("这张图纸里除了空气什么都没有。");
        }

        // ④ 落点：玩家脚下沿面朝方向偏移 ORIGIN_OFFSET 格，蓝图向面朝方向展开
        BuildPlacement placement = BuildPlacement.inFrontOf(
                player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ(),
                facingOf(player), ORIGIN_OFFSET);

        // ⑤ 覆盖检查：只碰自然地形，判不准就当人工方块
        CoveragePlanner.Result coverage = CoveragePlanner.check(plan, placement, new McTerrainProbe(player.level()));
        if (coverage.unloadedChunk()) {
            return ToolOutcome.error("目标区域的区块没有加载（你可能站在区块边缘），请让玩家走近一点再试。");
        }
        if (!coverage.ok() && coverage.firstViolation() != null) {
            CoveragePlanner.Violation violation = coverage.firstViolation();
            return ToolOutcome.error("建造会覆盖非自然地形，已拒绝：位置 "
                    + "(" + violation.x() + " " + violation.y() + " " + violation.z() + ") 上是 "
                    + violation.currentBlockId() + "。请换一个地方，或让玩家先把那里清理出来。");
        }

        // ⑥ 与"待确认指令"互斥：否则 /ai confirm 可能执行到玩家没看过的那件事
        if (pendingCommands != null) {
            pendingCommands.clear(player.getUUID());
        }
        PendingBuildStore.Pending pending = pendingBuilds.propose(
                player.getUUID(), plan, placement, System.currentTimeMillis());

        sendPreview(player, blueprint, plan, placement, coverage);

        return ToolOutcome.ok("已提交待确认："
                + "「" + pending.name() + "」" + blueprint.sizeText() + "，共 " + plan.blockCount() + " 块"
                + "（已覆盖/占用的格子 " + coverage.checkedBlocks() + " 个）。"
                + "\n**尚未开始建造**：玩家要在游戏内敲 /ai confirm 才会逐 tick 放置"
                + "（" + (PendingBuildStore.TTL_MILLIS / 1000L) + " 秒内有效）。"
                + "建造完成后玩家可用 /ai undo 撤销。"
                + "\n如果玩家拒绝或提出修改，请按他的意思重新设计并再次调用本工具，不要反复提交同一张图。");
    }

    /**
     * 给玩家发预览。
     *
     * <p>这是玩家确认前<b>唯一</b>能看到的东西（本轮刻意不做客户端可视化预览），
     * 所以顺序很重要：先说"要建什么、多大、多少块、覆盖哪些坐标"，再给字符画与图例，
     * 最后才是确认提示。建造量太大时省掉字符画 —— 几十条消息刷屏反而让人没法判断。
     */
    private static void sendPreview(ServerPlayer player, BuildBlueprint blueprint,
                                    BuildPlan plan, BuildPlacement placement, CoveragePlanner.Result coverage) {
        BuildPlacement.Position origin = placement.origin();
        BuildPlacement.Position far = placement.toWorld(blueprint.width() - 1, blueprint.height() - 1, blueprint.depth() - 1);
        player.sendSystemMessage(Component.translatable("ai.ai_assisted.build.proposed",
                blueprint.name().isEmpty() ? "未命名建筑" : blueprint.name(),
                blueprint.sizeText(),
                plan.blockCount(),
                origin.x() + " " + origin.y() + " " + origin.z(),
                far.x() + " " + far.y() + " " + far.z()));

        int artLines = blueprint.height() * blueprint.depth() + blueprint.height();
        if (artLines <= MAX_PREVIEW_LINES) {
            player.sendSystemMessage(Component.literal(String.join("\n",
                    AsciiPreview.render(blueprint, blueprint.height(), MAX_PREVIEW_WIDTH))));
            player.sendSystemMessage(Component.literal(String.join("\n", AsciiPreview.legend(blueprint))));
        } else {
            player.sendSystemMessage(Component.translatable("ai.ai_assisted.build.preview_too_big",
                    blueprint.height(), blueprint.depth()));
            player.sendSystemMessage(Component.literal(String.join("\n", AsciiPreview.legend(blueprint))));
        }

        player.sendSystemMessage(Component.translatable("ai.ai_assisted.build.materials",
                describeMaterials(plan, coverage.checkedBlocks())));
        player.sendSystemMessage(Component.translatable("ai.ai_assisted.build.confirm_hint",
                PendingBuildStore.TTL_MILLIS / 1000L));
    }

    /** 用料清单：只列前几项，全列出来会刷屏（完整清单在图纸里）。 */
    private static String describeMaterials(BuildPlan plan, int checked) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (var entry : plan.materialCounts().entrySet()) {
            if (shown++ > 0) {
                out.append("、");
            }
            out.append(entry.getKey()).append(" x").append(entry.getValue());
            if (shown >= 5) {
                out.append("…");
                break;
            }
        }
        return out.toString();
    }

    /** 玩家的水平朝向：{@code getDirection()} 在抬头/低头时会给 UP/DOWN，所以按 yaw 取。 */
    private static BuildPlacement.Facing facingOf(ServerPlayer player) {
        return switch (Direction.fromYRot(player.getYRot())) {
            case NORTH -> BuildPlacement.Facing.NORTH;
            case SOUTH -> BuildPlacement.Facing.SOUTH;
            case WEST -> BuildPlacement.Facing.WEST;
            default -> BuildPlacement.Facing.EAST;
        };
    }
}
