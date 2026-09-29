package org.gwfx.aiassisted.core.build;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 蓝图的语义校验（零 MC 依赖，可单测）：方块 id 形状、黑名单、体积上限。
 *
 * <p><b>为什么黑名单必须在这里（而不是只在 MC 侧）</b>：core 层拿到的是纯字符串，
 * 能在最前面就拒掉；MC 侧再按 {@code Block} 实例复查一遍是为了防"id 字符串绕过"
 * （大小写变体、别名）。两道都要有。
 *
 * <p><b>黑名单钉的是什么</b>：会造成不可逆后果或绕过全部校验的方块 ——
 * 命令方块/结构方块/拼图方块能绕过本模组的任何限制；基岩/屏障/光环方块会留下无法正常破坏的残留；
 * 传送门类会生成新的世界状态；刷怪笼/试炼刷怪笼/宝库会持续产出。**TNT 与火**一并封掉，
 * 因为"建完自己炸了"对玩家没有任何好处。
 */
public final class BlueprintValidator {

    /** 禁止出现在蓝图里的方块（带命名空间的完整 id）。 */
    public static final Set<String> FORBIDDEN_BLOCKS = Set.of(
            "minecraft:bedrock",
            "minecraft:barrier",
            "minecraft:light",
            "minecraft:structure_block",
            "minecraft:structure_void",
            "minecraft:jigsaw",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:end_portal",
            "minecraft:end_portal_frame",
            "minecraft:end_gateway",
            "minecraft:nether_portal",
            "minecraft:spawner",
            "minecraft:trial_spawner",
            "minecraft:vault",
            "minecraft:fire",
            "minecraft:soul_fire",
            "minecraft:lava",
            "minecraft:tnt",
            "minecraft:budding_amethyst");

    /**
     * 方块 id 形状：{@code namespace:path}。
     *
     * <p>刻意<b>不接受</b>省略命名空间的写法（{@code oak_planks}）：形状检查是"确定性地拒"
     * 而不是"猜他想写什么"，拒的时候把正确写法写在报错里，模型下一轮就能改对。
     */
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private BlueprintValidator() {
    }

    /**
     * 校验蓝图。
     *
     * @param maxBlocks 体积上限（按<b>包围盒格子数</b>算，含空气 —— 它同时约束了覆盖检查的代价）
     * @return 通过时返回空；不通过时返回给模型看的一句话
     */
    public static Optional<String> validate(BuildBlueprint blueprint, int maxBlocks) {
        if (blueprint == null) {
            return Optional.of("蓝图为空。");
        }
        if (blueprint.totalCells() > maxBlocks) {
            return Optional.of("方块总数 " + blueprint.totalCells() + "（" + blueprint.sizeText()
                    + "）超过单次上限 " + maxBlocks + "：请把建筑改小，或让管理员调大 ai.build.maxBlocks。");
        }

        // 用 LinkedHashSet 保持出现顺序，报错时提到的是蓝图里真正出现的那个 id
        Set<String> ids = new LinkedHashSet<>(blueprint.palette().values());
        for (String blockId : ids) {
            if (!BLOCK_ID.matcher(blockId).matches()) {
                return Optional.of("方块 id「" + blockId + "」写法不对：必须是形如 minecraft:oak_planks 的完整 id");
            }
            if (FORBIDDEN_BLOCKS.contains(blockId)) {
                return Optional.of("方块「" + blockId + "」被禁止使用（会绕过权限或造成不可逆后果），请换一种材料");
            }
        }
        return Optional.empty();
    }
}
