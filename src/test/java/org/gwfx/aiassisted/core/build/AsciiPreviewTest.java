package org.gwfx.aiassisted.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 施工图预览（T001-6）：玩家在确认前唯一能看到的东西，必须短、必须带省略提示。 */
class AsciiPreviewTest {

    private static BuildBlueprint blueprint() {
        BlueprintParser.Result parsed = BlueprintParser.parse("小屋",
                List.of("W=minecraft:oak_planks", ".=minecraft:air"),
                List.of("WWW;W.W;WWW", "WWW;W.W;WWW", "WWW;WWW;WWW"));
        assertTrue(parsed.ok(), parsed::error);
        return parsed.blueprint();
    }

    @Test
    void rendersHeaderAndEveryLayer() {
        List<String> lines = AsciiPreview.render(blueprint(), 10, 32);
        String joined = String.join("\n", lines);

        assertTrue(joined.contains("小屋"), joined);
        assertTrue(joined.contains("3x3x3"), joined);
        assertTrue(joined.contains("[ y=1 ]"), joined);
        assertTrue(joined.contains("[ y=3 ]"), joined);
        assertTrue(joined.contains("W.W"), joined);
    }

    @Test
    void tellsTheReaderWhenLayersAreOmitted() {
        // 绝不静默丢掉一半图：否则玩家会以为"就这么几层"
        String joined = String.join("\n", AsciiPreview.render(blueprint(), 1, 32));

        assertTrue(joined.contains("[ y=1 ]"), joined);
        assertTrue(joined.contains("其余省略"), joined);
        assertTrue(!joined.contains("[ y=3 ]"), joined);
    }

    @Test
    void truncatesOverlongRows() {
        String joined = String.join("\n", AsciiPreview.render(blueprint(), 10, 2));

        assertTrue(joined.contains("WW…"), joined);
    }

    @Test
    void legendMapsCharactersBackToBlocks() {
        String joined = String.join("\n", AsciiPreview.legend(blueprint()));

        assertTrue(joined.contains("W = minecraft:oak_planks"), joined);
        assertTrue(joined.contains("空气"), joined);
    }

    @Test
    void emptyBlueprintRendersNothing() {
        assertTrue(AsciiPreview.render(null, 4, 32).isEmpty());
    }
}
