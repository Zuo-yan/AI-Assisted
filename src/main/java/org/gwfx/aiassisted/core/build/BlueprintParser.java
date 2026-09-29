package org.gwfx.aiassisted.core.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把模型给的 {@code palette} / {@code layers} 解析成 {@link BuildBlueprint}（零 MC 依赖，可单测）。
 *
 * <p><b>这一步只管"结构自不自洽"</b>：字符能否对上、每行等长、层数一致。
 * 方块 id 是否存在、是否在黑名单、总量是否超限属于 {@link BlueprintValidator}。
 * 分开是因为两类错误给模型的提示完全不同 —— "你这张图画歪了" vs "你不许用基岩"。
 *
 * <p><b>三条硬规则</b>（都在这里拦下，且都给出"该怎么改"的原话）：
 * <ol>
 *   <li>行里<b>不许有空格</b>：空格最容易是模型"顺手对齐"出来的，一旦允许，宽度就会变得不可信。
 *       要空气请写 {@code .}。</li>
 *   <li>层内多行用 {@code ;} 分隔（不依赖 JSON 字符串里的 {@code \n} 转义 —— 那正是最容易写坏的地方）。</li>
 *   <li>所有行必须等长、每层行数必须相同：这是"分层字符画"能成立的前提。</li>
 * </ol>
 */
public final class BlueprintParser {

    /** 单层行分隔符。 */
    public static final char ROW_SEPARATOR = ';';

    /** palette 条目里的分隔符，形如 {@code W=minecraft:oak_planks}。 */
    public static final char PALETTE_SEPARATOR = '=';

    private BlueprintParser() {
    }

    /**
     * 解析结果：{@code blueprint} 与 {@code error} 二选一。
     *
     * @param error 给模型看的一句话（说明错在哪、该怎么改），成功时为空串
     */
    public record Result(BuildBlueprint blueprint, String error) {

        public Result {
            error = error == null ? "" : error;
        }

        public boolean ok() {
            return this.blueprint != null && this.error.isEmpty();
        }

        static Result failed(String error) {
            return new Result(null, error);
        }
    }

    /**
     * @param name           建筑名（可空）
     * @param paletteEntries 形如 {@code W=minecraft:oak_planks} 的条目
     * @param layerSpecs     每项是一层，层内多行用 {@code ;} 分隔（顺序：先地基后屋顶）
     */
    public static Result parse(String name, List<String> paletteEntries, List<String> layerSpecs) {
        if (paletteEntries == null || paletteEntries.isEmpty()) {
            return Result.failed("palette 不能为空：请至少给出每个字符对应的方块 id，例如 W=minecraft:oak_planks");
        }
        if (layerSpecs == null || layerSpecs.isEmpty()) {
            return Result.failed("layers 不能为空：每项是一层，层内的行用 " + ROW_SEPARATOR + " 分隔");
        }

        Map<Character, String> palette = new LinkedHashMap<>();
        for (String entry : paletteEntries) {
            if (entry == null || entry.isBlank()) {
                return Result.failed("palette 里有空条目：每项都要写成 字符=方块id");
            }
            String trimmed = entry.strip();
            int separator = trimmed.indexOf(PALETTE_SEPARATOR);
            if (separator <= 0 || separator == trimmed.length() - 1) {
                return Result.failed("palette 条目「" + trimmed + "」格式不对：应写成 字符=方块id，例如 W=minecraft:oak_planks");
            }
            String keyText = trimmed.substring(0, separator).strip();
            String blockId = trimmed.substring(separator + 1).strip();
            if (keyText.length() != 1) {
                return Result.failed("palette 里的键「" + keyText + "」必须是单个字符");
            }
            char key = keyText.charAt(0);
            if (key == ROW_SEPARATOR || key == PALETTE_SEPARATOR || key == ' ') {
                return Result.failed("字符「" + key + "」不能作为 palette 的键（它是分隔符或空格）");
            }
            if (blockId.isEmpty()) {
                return Result.failed("palette 里「" + key + "」没有对应的方块 id");
            }
            String previous = palette.putIfAbsent(key, blockId);
            if (previous != null) {
                return Result.failed("palette 里字符「" + key + "」出现了两次（" + previous + " 与 " + blockId + "）");
            }
        }

        List<List<String>> layers = new ArrayList<>(layerSpecs.size());
        int width = -1;
        int depth = -1;

        for (int y = 0; y < layerSpecs.size(); y++) {
            String spec = layerSpecs.get(y);
            if (spec == null || spec.isBlank()) {
                return Result.failed("第 " + (y + 1) + " 层是空的");
            }
            String[] rows = splitRows(spec);
            if (depth == -1) {
                depth = rows.length;
            } else if (rows.length != depth) {
                return Result.failed("第 " + (y + 1) + " 层有 " + rows.length + " 行，前一层是 " + depth
                        + " 行：每层的行数必须相同（它决定建筑的深度）");
            }

            List<String> layerRows = new ArrayList<>(rows.length);
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z];
                if (row.indexOf(' ') >= 0) {
                    return Result.failed("第 " + (y + 1) + " 层第 " + (z + 1) + " 行里有空格："
                            + "空格会让宽度变得不可信，空气请用 . 表示");
                }
                if (width == -1) {
                    width = row.length();
                } else if (row.length() != width) {
                    return Result.failed("第 " + (y + 1) + " 层第 " + (z + 1) + " 行有 " + row.length()
                            + " 个字符，应为 " + width + " 个：每行必须等长");
                }
                layerRows.add(row);
            }
            layers.add(List.copyOf(layerRows));
        }

        if (width <= 0 || depth <= 0) {
            return Result.failed("蓝图尺寸不合法：宽 " + width + "，深 " + depth);
        }

        BuildBlueprint blueprint = new BuildBlueprint(name, width, layers.size(), depth, palette, layers);

        // 字符必须在 palette 里有对应方块 —— 少一个字符就是一整片区域没有定义
        for (int y = 0; y < blueprint.height(); y++) {
            for (int z = 0; z < blueprint.depth(); z++) {
                String row = blueprint.layers().get(y).get(z);
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (!palette.containsKey(c)) {
                        return Result.failed("字符「" + c + "」没有出现在 palette 里（第 " + (y + 1)
                                + " 层第 " + (z + 1) + " 行第 " + (x + 1) + " 列）");
                    }
                }
            }
        }
        return new Result(blueprint, "");
    }

    /**
     * 按 {@code ;} 切行；同时把行首尾的空白去掉（但内部不行 —— 内部空格在上面报错）。
     *
     * <p>刻意丢掉<b>末尾</b>多出来的空行：模型常在最后多写一个分号，切出来就是一个空串。
     * 它没有内容可丢，因此宽容处理；而<b>中间</b>的空行照旧当错误（丢掉它会静默改变深度，
     * 那才是真正会让建筑变形的事）。
     */
    private static String[] splitRows(String layerSpec) {
        String[] parts = layerSpec.replace("\r", "").replace("\n", "").split(String.valueOf(ROW_SEPARATOR), -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].strip();
        }
        int end = parts.length;
        while (end > 0 && parts[end - 1].isEmpty()) {
            end--;
        }
        return end == parts.length ? parts : java.util.Arrays.copyOf(parts, end);
    }
}
