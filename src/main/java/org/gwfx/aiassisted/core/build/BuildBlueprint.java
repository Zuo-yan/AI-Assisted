package org.gwfx.aiassisted.core.build;

import java.util.List;
import java.util.Map;

/**
 * 一份<b>已解析</b>的施工图（T001-6，零 MC 依赖，可单测）。
 *
 * <p><b>为什么用"分层字符画 + palette"而不是逐个方块的坐标列表</b>：一座 9×7×5 的木屋用坐标列表
 * 要 300+ 条 JSON，模型既贵又容易写错；字符画约 315 个字符就能表达完，而且
 * <b>玩家在聊天里能直接看懂</b> —— 这正好补上"不做客户端可视化预览"的缺口。
 *
 * <p><b>为什么尺寸由内容推导，而不是让模型另外声明</b>：宽 = 每行字符数，深 = 每层行数，
 * 高 = 层数。这样从根上消灭了"size 与 layers 不一致"这一整类错误 ——
 * 少一个参数、少一种校验失败、少一轮重试。
 *
 * @param name   建筑名（可空，仅用于展示）
 * @param width  x 方向长度（每行字符数）
 * @param height y 方向层数
 * @param depth  z 方向每层的行数
 * @param palette 字符 → 方块 id
 * @param layers  {@code layers.get(y)} 是该层的 {@code depth} 行，每行 {@code width} 个字符
 */
public record BuildBlueprint(String name,
                             int width,
                             int height,
                             int depth,
                             Map<Character, String> palette,
                             List<List<String>> layers) {

    public BuildBlueprint {
        name = name == null ? "" : name.strip();
        palette = palette == null ? Map.of() : Map.copyOf(palette);
        layers = layers == null ? List.of() : List.copyOf(layers);
    }

    /** 总格子数（含空气）—— 校验上限用的是它，而不是"实际放置的方块数"。 */
    public int totalCells() {
        return this.width * this.height * this.depth;
    }

    /** 取某格的方块 id；越界或该字符没在 palette 里时返回空串。 */
    public String blockIdAt(int x, int y, int z) {
        if (x < 0 || x >= this.width || y < 0 || y >= this.height || z < 0 || z >= this.depth) {
            return "";
        }
        int rowIndex = z;
        int charIndex = x;
        String row = this.layers.get(y).get(rowIndex);
        return this.palette.getOrDefault(row.charAt(charIndex), "");
    }

    /** 尺寸的可读描述，例如 {@code 9x5x7}。 */
    public String sizeText() {
        return this.width + "x" + this.height + "x" + this.depth;
    }
}
