package org.gwfx.aiassisted.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSpecTest {

    @Test
    void buildsSchemaWithPropertyTypesAndRequired() {
        ToolSpec spec = ToolSpec.builder("search_blocks", "搜索附近方块")
                .stringParam("block_id", "方块注册表 id", true)
                .integerParam("radius", "搜索半径", 1, 16, false)
                .build();

        assertEquals("search_blocks", spec.name());
        assertEquals("搜索附近方块", spec.description());

        JsonObject schema = spec.parametersSchema();
        assertEquals("object", schema.get("type").getAsString());

        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject blockId = properties.getAsJsonObject("block_id");
        assertEquals("string", blockId.get("type").getAsString());
        assertEquals("方块注册表 id", blockId.get("description").getAsString());

        JsonObject radius = properties.getAsJsonObject("radius");
        assertEquals("integer", radius.get("type").getAsString());
        assertEquals(1, radius.get("minimum").getAsInt());
        assertEquals(16, radius.get("maximum").getAsInt());

        JsonArray required = schema.getAsJsonArray("required");
        assertEquals(1, required.size());
        assertEquals("block_id", required.get(0).getAsString());
    }

    @Test
    void keepsRequiredArrayEvenWhenEmpty() {
        // Schema 结构固定：模型对稳定结构的利用率更高，别因为没必填项就把 required 省掉
        JsonObject schema = ToolSpec.builder("t", "d").build().parametersSchema();

        assertTrue(schema.has("required"));
        assertTrue(schema.getAsJsonArray("required").isEmpty());
        assertTrue(schema.getAsJsonObject("properties").isEmpty());
    }

    @Test
    void nullSchemaFallsBackToEmptyObjectSchema() {
        JsonObject schema = new ToolSpec("t", "d", null).parametersSchema();

        assertEquals("object", schema.get("type").getAsString());
        assertTrue(schema.getAsJsonObject("properties").isEmpty());
    }

    @Test
    void toJsonArrayProducesFunctionWrapper() {
        JsonArray array = ToolSpec.toJsonArray(List.of(
                ToolSpec.builder("a", "A").stringParam("x", "X", true).build()));

        assertEquals(1, array.size());
        JsonObject wrapper = array.get(0).getAsJsonObject();
        assertEquals("function", wrapper.get("type").getAsString());
        JsonObject function = wrapper.getAsJsonObject("function");
        assertEquals("a", function.get("name").getAsString());
        assertEquals("A", function.get("description").getAsString());
        assertTrue(function.get("parameters").isJsonObject());
    }
}
