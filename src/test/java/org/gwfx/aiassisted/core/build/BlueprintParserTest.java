package org.gwfx.aiassisted.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蓝图解析（T001-6）：模型给的字符画必须被"确定性地"接受或拒绝。
 *
 * <p>每条失败用例都对应模型真实会犯的错（层行数不齐、行里有空格、字符没在 palette 里），
 * 所以断言的不仅是"失败了"，还包括"报错里说清了该怎么改"。
 */
class BlueprintParserTest {

    private static final List<String> PALETTE = List.of("W=minecraft:oak_planks", "L=minecraft:oak_log", ".=minecraft:air");

    @Test
    void parsesASimpleTwoLayerShed() {
        BlueprintParser.Result result = BlueprintParser.parse("小屋", PALETTE,
                List.of("LLL;L.L;LLL", "WWW;W.W;WWW"));

        assertTrue(result.ok(), result::error);
        BuildBlueprint blueprint = result.blueprint();
        assertEquals(3, blueprint.width());
        assertEquals(2, blueprint.height());
        assertEquals(3, blueprint.depth());
        assertEquals("minecraft:oak_log", blueprint.blockIdAt(0, 0, 0));
        assertEquals("minecraft:air", blueprint.blockIdAt(1, 0, 1));
        assertEquals("3x2x3", blueprint.sizeText());
        assertEquals(18, blueprint.totalCells());
    }

    @Test
    void rejectsRowsOfDifferentLength() {
        BlueprintParser.Result result = BlueprintParser.parse("x", PALETTE, List.of("LLL;LL"));

        assertFalse(result.ok());
        assertTrue(result.error().contains("等长"), result.error());
    }

    @Test
    void rejectsLayersWithDifferentRowCounts() {
        BlueprintParser.Result result = BlueprintParser.parse("x", PALETTE, List.of("LLL;L.L;LLL", "WWW;WWW"));

        assertFalse(result.ok());
        assertTrue(result.error().contains("行数"), result.error());
    }

    @Test
    void rejectsSpacesInsideRows() {
        // 空格最容易是模型"顺手对齐"出来的；一旦允许，宽度就不可信了
        BlueprintParser.Result result = BlueprintParser.parse("x", PALETTE, List.of("LLL;L L;LLL"));

        assertFalse(result.ok());
        assertTrue(result.error().contains("空格"), result.error());
    }

    @Test
    void rejectsCharacterMissingFromPalette() {
        BlueprintParser.Result result = BlueprintParser.parse("x", PALETTE, List.of("LLL;LXL;LLL"));

        assertFalse(result.ok());
        assertTrue(result.error().contains("X"), result.error());
    }

    @Test
    void rejectsMalformedPaletteEntries() {
        assertFalse(BlueprintParser.parse("x", List.of("W"), PALETTE).ok());
        assertFalse(BlueprintParser.parse("x", List.of("=minecraft:stone"), PALETTE).ok());
        assertFalse(BlueprintParser.parse("x", List.of("WW=minecraft:stone"), PALETTE).ok());
        assertFalse(BlueprintParser.parse("x", List.of("W="), PALETTE).ok());
        // 同一个字符映射两个方块：必须报错，否则"图纸"的含义就取决于解析顺序了
        BlueprintParser.Result duplicate = BlueprintParser.parse("x",
                List.of("W=minecraft:stone", "W=minecraft:dirt"), List.of("W"));
        assertFalse(duplicate.ok());
        assertTrue(duplicate.error().contains("两次"), duplicate.error());
    }

    @Test
    void rejectsEmptyInputs() {
        assertFalse(BlueprintParser.parse("x", List.of(), List.of("W")).ok());
        assertFalse(BlueprintParser.parse("x", PALETTE, List.of()).ok());
        assertFalse(BlueprintParser.parse("x", PALETTE, List.of("   ")).ok());
    }

    @Test
    void toleratesTrailingSeparatorAndWhitespaceAroundRows() {
        // 模型常在末尾多写一个分号、或在行首尾带空白；这不该让整张图失败
        BlueprintParser.Result result = BlueprintParser.parse("x", PALETTE, List.of("LLL;L.L;LLL;"));

        assertTrue(result.ok(), result::error);
        assertEquals(3, result.blueprint().depth());
    }
}
