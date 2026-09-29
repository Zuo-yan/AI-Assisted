package org.gwfx.aiassisted.core.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClearFilterTest {

    @Test
    void matchesExactIds() {
        ClearFilter filter = new ClearFilter("minecraft:cobblestone, minecraft:dirt");
        assertTrue(filter.matches("minecraft:cobblestone"));
        assertTrue(filter.matches("minecraft:dirt"));
        assertFalse(filter.matches("minecraft:stone"));
    }

    @Test
    void prefixesNamespaceAutomatically() {
        ClearFilter filter = new ClearFilter("cobblestone, oak_planks");
        assertTrue(filter.matches("minecraft:cobblestone"));
        assertTrue(filter.matches("minecraft:oak_planks"));
        assertFalse(filter.matches("minecraft:dirt"));
    }

    @Test
    void patternAliasMatching() {
        ClearFilter filter = new ClearFilter("leaves");
        assertTrue(filter.matches("minecraft:oak_leaves"));
        assertTrue(filter.matches("minecraft:birch_leaves"));
        assertTrue(filter.matches("minecraft:cherry_leaves"));
        assertFalse(filter.matches("minecraft:oak_log"));
    }

    @Test
    void matchAllExpression() {
        ClearFilter filter1 = new ClearFilter("all");
        ClearFilter filter2 = new ClearFilter("*");
        ClearFilter filter3 = new ClearFilter("");

        assertTrue(filter1.isMatchAll());
        assertTrue(filter1.matches("minecraft:stone"));
        assertTrue(filter2.isMatchAll());
        assertTrue(filter3.isMatchAll());
    }
}
