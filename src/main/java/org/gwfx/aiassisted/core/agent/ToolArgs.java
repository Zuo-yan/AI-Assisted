package org.gwfx.aiassisted.core.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工具入参的解析与夹紧。
 *
 * <p><b>为什么单独抽一个类</b>：入参来自模型输出，属于不可信数据 ——
 * 类型不对、越界、缺字段、JSON 里塞了个字符串而不是数字，都是常态。
 * 这些都不能抛异常（一抛就打断整轮对话），而要变成「取不到 / 夹到边界」的确定性结果。
 * 把这套规则集中在一处，既好测，也避免每个工具各写一遍。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    /** 解析模型给的 arguments 字符串；非法 JSON 或非对象一律返回空对象，绝不抛异常。 */
    public static JsonObject parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(argumentsJson);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return new JsonObject();
        }
    }

    /** 取非空字符串参数；缺失、空白或类型不是字符串时返回 empty。 */
    public static Optional<String> string(JsonObject args, String key) {
        JsonElement element = args == null ? null : args.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        // 必须再查 isString()：JsonPrimitive 也覆盖数字与布尔，
        // 如果不查，「block_id: 123」会被当成字符串 "123" 一路带到注册表查询
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            return Optional.empty();
        }
        String value = primitive.getAsString().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /**
     * 取整数参数并夹紧到 {@code [min,max]}。
     *
     * <p>缺失、非数字、布尔值时回退到 {@code fallback}（同样夹紧）——
     * 越界不报错而是夹紧，因为「放大一点半径」这类偏差不该让整次查询失败。
     */
    public static int integer(JsonObject args, String key, int fallback, int min, int max) {
        JsonElement element = args == null ? null : args.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return clamp(fallback, min, max);
        }
        try {
            return clamp(element.getAsInt(), min, max);
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return clamp(fallback, min, max);
        }
    }

    /**
     * 取字符串数组参数。
     *
     * <p><b>任何一项不是字符串就整体返回 empty</b>，而不是"跳过坏项只留好的"：
     * 数组参数目前只用于蓝图（分层字符画），少一行或多一行都会让建筑变形 ——
     * 静默吞掉坏项等于让玩家拿到一座与预览不一致的建筑，那比直接失败糟糕得多。
     */
    public static Optional<List<String>> stringArray(JsonObject args, String key) {
        JsonElement element = args == null ? null : args.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull() || !item.isJsonPrimitive()) {
                return Optional.empty();
            }
            JsonPrimitive primitive = item.getAsJsonPrimitive();
            if (!primitive.isString()) {
                return Optional.empty();
            }
            values.add(primitive.getAsString());
        }
        return Optional.of(values);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
