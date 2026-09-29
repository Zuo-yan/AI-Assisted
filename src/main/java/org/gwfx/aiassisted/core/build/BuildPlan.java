package org.gwfx.aiassisted.core.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 展开后的施工计划（零 MC 依赖，可单测）：把字符画摊平成"相对坐标 + 方块 id"的清单。
 *
 * <p>坐标一律是<b>相对坐标</b>（0..width-1, 0..height-1, 0..depth-1）；物理位置由
 * {@link BuildPlacement} 按玩家站位与朝向映射 —— 模型永远拿不到也不该拿到绝对坐标，
 * 否则它就能把房子盖到别人家。
 *
 * <p>顺序刻意固定为 <b>y 升序 → z 升序 → x 升序</b>（地基开始、逐行逐层），原因有二：
 * 门/楼梯/栅栏这类方块先有支撑再成形；也让"建筑长出来"的过程看起来是从地里长上来的。
 */
public record BuildPlan(String name,
                        int width,
                        int height,
                        int depth,
                        List<LocalBlock> blocks,
                        Map<String, Integer> materialCounts) {

    /** 一个待放置的方块（相对坐标）。 */
    public record LocalBlock(int x, int y, int z, String blockId) {
    }

    public BuildPlan {
        name = name == null ? "" : name.strip();
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        materialCounts = materialCounts == null ? Map.of() : Map.copyOf(materialCounts);
    }

    /** 空气不参与放置（也不计入耗材），所以它不算"方块数"。 */
    public boolean isEmpty() {
        return this.blocks.isEmpty();
    }

    public int blockCount() {
        return this.blocks.size();
    }

    /** 空气的三种写法都算空气（洞穴空气/虚空空气在建筑里与空气等价）。 */
    public static boolean isAir(String blockId) {
        return "minecraft:air".equals(blockId)
                || "minecraft:cave_air".equals(blockId)
                || "minecraft:void_air".equals(blockId);
    }

    /** 由蓝图展开：跳过空气，按固定顺序摊平，并顺手统计每种方块用多少。 */
    public static BuildPlan from(BuildBlueprint blueprint) {
        if (blueprint == null) {
            return new BuildPlan("", 0, 0, 0, List.of(), Map.of());
        }
        List<LocalBlock> blocks = new ArrayList<>(blueprint.totalCells());
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (int y = 0; y < blueprint.height(); y++) {
            for (int z = 0; z < blueprint.depth(); z++) {
                for (int x = 0; x < blueprint.width(); x++) {
                    String blockId = blueprint.blockIdAt(x, y, z);
                    if (blockId.isEmpty() || isAir(blockId)) {
                        continue;
                    }
                    blocks.add(new LocalBlock(x, y, z, blockId));
                    counts.merge(blockId, 1, Integer::sum);
                }
            }
        }
        return new BuildPlan(blueprint.name(), blueprint.width(), blueprint.height(), blueprint.depth(),
                blocks, sortByCountDesc(counts));
    }

    /** 耗材清单按数量降序 —— 玩家最关心"用得最多的那几种"够不够。 */
    private static Map<String, Integer> sortByCountDesc(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        entries.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }
}
