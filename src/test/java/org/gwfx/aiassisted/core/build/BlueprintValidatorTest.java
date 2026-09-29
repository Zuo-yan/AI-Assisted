package org.gwfx.aiassisted.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 蓝图语义校验（T001-6）：黑名单、体积上限、方块 id 形状。 */
class BlueprintValidatorTest {

    private static BuildBlueprint blueprint(String blockId) {
        BlueprintParser.Result parsed = BlueprintParser.parse("t",
                List.of("W=" + blockId), List.of("WW;WW"));
        assertTrue(parsed.ok(), parsed::error);
        return parsed.blueprint();
    }

    @Test
    void acceptsOrdinaryBuildingMaterials() {
        assertTrue(BlueprintValidator.validate(blueprint("minecraft:oak_planks"), 4096).isEmpty());
        assertTrue(BlueprintValidator.validate(blueprint("minecraft:glass"), 4096).isEmpty());
        assertTrue(BlueprintValidator.validate(blueprint("minecraft:stone_bricks"), 4096).isEmpty());
    }

    @Test
    void rejectsEveryForbiddenBlock() {
        // 参数化地过一遍黑名单：名单是"会绕过权限或留下残留"的那批方块，
        // 任何一个漏掉都等于开了后门，所以逐个钉住
        for (String forbidden : BlueprintValidator.FORBIDDEN_BLOCKS) {
            Optional<String> rejected = BlueprintValidator.validate(blueprint(forbidden), 4096);
            assertTrue(rejected.isPresent(), () -> "应当拒绝：" + forbidden);
            assertTrue(rejected.get().contains("禁止"), rejected.get());
        }
    }

    @Test
    void rejectsBlockIdWithoutNamespace() {
        // 刻意不接受省略命名空间：判错方向必须是"拒"，并把正确写法写在报错里
        Optional<String> rejected = BlueprintValidator.validate(blueprint("oak_planks"), 4096);

        assertTrue(rejected.isPresent());
        assertTrue(rejected.get().contains("minecraft:"), rejected.get());
    }

    @Test
    void rejectsOversizedBlueprint() {
        BuildBlueprint four = blueprint("minecraft:stone");   // 2x1x2 = 4 格

        assertTrue(BlueprintValidator.validate(four, 4).isEmpty());
        assertTrue(BlueprintValidator.validate(four, 3).orElse("").contains("超过"));
    }

    @Test
    void rejectsNullBlueprint() {
        assertTrue(BlueprintValidator.validate(null, 4096).isPresent());
    }
}
