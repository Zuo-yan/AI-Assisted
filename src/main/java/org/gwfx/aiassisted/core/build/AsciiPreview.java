package org.gwfx.aiassisted.core.build;

import java.util.ArrayList;
import java.util.List;

/**
 * 把施工图渲染成聊天里能看的字符画（零 MC 依赖，可单测）。
 *
 * <p><b>它为什么必须存在</b>：本方案刻意不做客户端可视化预览（那要新网络包 + 客户端渲染 + 分块下发）。
 * 那么"玩家凭什么判断 AI 要建的东西能不能接受"？答案就是这张图 ——
 * 它是玩家按 {@code /ai confirm} 前唯一能看到的形态，所以必须短、必须能看懂。
 *
 * <p>因此有三道裁剪：层数上限、每行宽度上限、以及超限时的明确省略提示
 * （绝不静默丢掉一半图 —— 那会让玩家以为"就这么多"）。
 */
public final class AsciiPreview {

    private AsciiPreview() {
    }

    /**
     * @param maxLayers 最多渲染几层（从地基开始）
     * @param maxWidth  每行最多几个字符（超宽截断并加 …）
     * @return 可直接逐行发出的文本行；层头用 {@code [ y=N ]} 标注（语言无关）
     */
    public static List<String> render(BuildBlueprint blueprint, int maxLayers, int maxWidth) {
        if (blueprint == null || blueprint.height() == 0) {
            return List.of();
        }
        int layers = Math.max(1, maxLayers);
        int width = Math.max(1, maxWidth);
        boolean truncated = blueprint.height() > layers;

        List<String> lines = new ArrayList<>();
        lines.add(header(blueprint));
        for (int y = 0; y < Math.min(layers, blueprint.height()); y++) {
            lines.add("[ y=" + (y + 1) + " ]");
            for (String row : blueprint.layers().get(y)) {
                lines.add(row.length() > width ? row.substring(0, width) + "…" : row);
            }
        }
        if (truncated) {
            lines.add("…（上面是地基起的 " + layers + " 层，共 " + blueprint.height() + " 层，其余省略）");
        }
        return List.copyOf(lines);
    }

    /** 图例：把用到的方块 id 列出来（字符画本身只有字母，没有它没人知道 W 是什么）。 */
    public static List<String> legend(BuildBlueprint blueprint) {
        if (blueprint == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("图例:");
        blueprint.palette().forEach((key, blockId) ->
                lines.add("  " + key + " = " + blockId + (BuildPlan.isAir(blockId) ? "（空气）" : "")));
        return List.copyOf(lines);
    }

    private static String header(BuildBlueprint blueprint) {
        String name = blueprint.name().isEmpty() ? "未命名建筑" : blueprint.name();
        return "「" + name + "」 " + blueprint.sizeText() + "（宽x高x深），共 " + blueprint.height() + " 层";
    }
}
