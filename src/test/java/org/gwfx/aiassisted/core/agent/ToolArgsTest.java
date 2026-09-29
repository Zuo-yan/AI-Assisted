package org.gwfx.aiassisted.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具入参校验：模型输出是不可信数据，这里验证「坏输入只降级、不抛异常」。
 */
class ToolArgsTest {

    @Test
    void parseReturnsEmptyObjectForBlankOrNonObjectInput() {
        assertTrue(ToolArgs.parse(null).isEmpty());
        assertTrue(ToolArgs.parse("   ").isEmpty());
        assertTrue(ToolArgs.parse("not json").isEmpty());
        // 合法 JSON 但不是对象：模型偶尔会发数组/裸字符串
        assertTrue(ToolArgs.parse("[1,2]").isEmpty());
        assertTrue(ToolArgs.parse("\"text\"").isEmpty());
    }

    @Test
    void parseKeepsObjectContent() {
        JsonObject args = ToolArgs.parse("{\"block_id\":\"minecraft:stone\"}");

        assertEquals("minecraft:stone", args.get("block_id").getAsString());
    }

    @Test
    void stringRejectsMissingBlankAndNonStringValues() {
        JsonObject args = JsonParser.parseString(
                "{\"blank\":\"  \",\"number\":7,\"object\":{},\"nul\":null,\"ok\":\" minecraft:stone \"}")
                .getAsJsonObject();

        assertTrue(ToolArgs.string(args, "absent").isEmpty());
        assertTrue(ToolArgs.string(args, "blank").isEmpty());
        assertTrue(ToolArgs.string(args, "number").isEmpty());
        assertTrue(ToolArgs.string(args, "object").isEmpty());
        assertTrue(ToolArgs.string(args, "nul").isEmpty());
        // 命中时自动去掉首尾空白，避免 " minecraft:stone " 这种值走到注册表查询
        assertEquals("minecraft:stone", ToolArgs.string(args, "ok").orElseThrow());
    }

    @Test
    void stringToleratesNullArgsObject() {
        assertTrue(ToolArgs.string(null, "x").isEmpty());
    }

    @Test
    void integerClampsOutOfRangeAndFallsBackOnNonNumbers() {
        JsonObject args = JsonParser.parseString(
                "{\"low\":-5,\"high\":999,\"ok\":8,\"text\":\"abc\",\"bool\":true}").getAsJsonObject();

        assertEquals(1, ToolArgs.integer(args, "low", 8, 1, 16));
        assertEquals(16, ToolArgs.integer(args, "high", 8, 1, 16));
        assertEquals(8, ToolArgs.integer(args, "ok", 1, 1, 16));
        // 非数字（字符串/布尔）与缺字段都回退到 fallback
        assertEquals(8, ToolArgs.integer(args, "text", 8, 1, 16));
        assertEquals(8, ToolArgs.integer(args, "bool", 8, 1, 16));
        assertEquals(8, ToolArgs.integer(args, "absent", 8, 1, 16));
        // fallback 本身也要夹紧
        assertEquals(16, ToolArgs.integer(args, "absent", 999, 1, 16));
    }
}
