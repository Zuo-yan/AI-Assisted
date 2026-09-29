package org.gwfx.aiassisted.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 为 T001-6 新加的两个工具能力：数组参数（{@link ToolSpec.Builder#arrayParam}）与它的解析
 * （{@link ToolArgs#stringArray}）。
 *
 * <p>为什么单独一个文件：它们不属于"工具声明"或"入参夹紧"的任何既有测试主题，
 * 而是"蓝图字段怎么过 wire"这一件事的两半，放一起更好读。
 */
class ToolArgsArrayTest {

    @Test
    void arrayParamProducesArrayOfStringSchema() {
        ToolSpec spec = ToolSpec.builder("t", "d")
                .arrayParam("layers", "每项是一层", true)
                .build();

        JsonObject properties = spec.parametersSchema().getAsJsonObject("properties");
        JsonObject layers = properties.getAsJsonObject("layers");

        assertEquals("array", layers.get("type").getAsString());
        assertEquals("string", layers.getAsJsonObject("items").get("type").getAsString());
        JsonArray required = spec.parametersSchema().getAsJsonArray("required");
        assertEquals("layers", required.get(0).getAsString());
    }

    @Test
    void stringArrayReadsAStringArray() {
        JsonObject args = JsonParser.parseString("{\"layers\":[\"WW\",\"W.\"]}").getAsJsonObject();

        Optional<List<String>> values = ToolArgs.stringArray(args, "layers");

        assertTrue(values.isPresent());
        assertEquals(List.of("WW", "W."), values.get());
    }

    @Test
    void stringArrayRejectsAnythingThatIsNotAnArrayOfStrings() {
        // 少一行都会让建筑变形，所以只要有一项不是字符串就整体拒绝，绝不"跳过坏项"
        assertTrue(ToolArgs.stringArray(JsonParser.parseString("{\"layers\":\"WW\"}").getAsJsonObject(), "layers").isEmpty());
        assertTrue(ToolArgs.stringArray(JsonParser.parseString("{\"layers\":[\"WW\",7]}").getAsJsonObject(), "layers").isEmpty());
        assertTrue(ToolArgs.stringArray(JsonParser.parseString("{\"layers\":[null]}").getAsJsonObject(), "layers").isEmpty());
        assertTrue(ToolArgs.stringArray(JsonParser.parseString("{}").getAsJsonObject(), "layers").isEmpty());
        assertTrue(ToolArgs.stringArray(null, "layers").isEmpty());
    }

    @Test
    void stringArrayAcceptsEmptyArray() {
        // 空数组是"合法但没内容"，由蓝图解析层去报"layers 不能为空"—— 职责不重叠
        Optional<List<String>> values = ToolArgs.stringArray(
                JsonParser.parseString("{\"layers\":[]}").getAsJsonObject(), "layers");

        assertTrue(values.isPresent());
        assertTrue(values.get().isEmpty());
        assertFalse(ToolArgs.stringArray(JsonParser.parseString("{\"layers\":[]}").getAsJsonObject(), "other").isPresent());
    }
}
