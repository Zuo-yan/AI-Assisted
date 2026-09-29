package org.gwfx.aiassisted.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 一个工具的声明（喂给模型看的 JSON Schema），零 MC 依赖。
 *
 * <p><b>为什么用 builder 生成 Schema 而不是手写字符串</b>：JSON Schema 结构固定但嵌套三层
 * （{@code type/properties/required}），手写必错且错法隐蔽（少个逗号、required 写成字符串数组…）。
 * 这里只在 builder 里拼一次，两个 Provider 与所有工具共用同一份产出。
 *
 * <p>放在 {@code ai.core.llm} 而不是 {@code ai.core.agent}：{@code ChatRequest} 要持有它，
 * 而 {@code ai.core.agent} 依赖 {@code ai.core.llm}；反过来放会形成包依赖环。
 *
 * @param parametersSchema 入参 JSON Schema（一个对象，含 {@code type/properties/required}）
 */
public record ToolSpec(String name, String description, JsonObject parametersSchema) {

    public ToolSpec {
        name = name == null ? "" : name.strip();
        description = description == null ? "" : description;
        parametersSchema = parametersSchema == null ? emptySchema() : parametersSchema;
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    /**
     * 转成两家 Provider 共用的 {@code tools} 数组：
     * {@code [{"type":"function","function":{name,description,parameters}}]}。
     */
    public static JsonArray toJsonArray(List<ToolSpec> specs) {
        JsonArray array = new JsonArray();
        if (specs == null) {
            return array;
        }
        for (ToolSpec spec : specs) {
            JsonObject function = new JsonObject();
            function.addProperty("name", spec.name());
            function.addProperty("description", spec.description());
            function.add("parameters", spec.parametersSchema());

            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", function);
            array.add(wrapper);
        }
        return array;
    }

    private static JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    /** 流式拼 JSON Schema 的小 builder。 */
    public static final class Builder {

        private final String name;
        private final String description;
        private final JsonObject properties = new JsonObject();
        private final JsonArray required = new JsonArray();

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /** 字符串参数。 */
        public Builder stringParam(String param, String description, boolean required) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "string");
            property.addProperty("description", description);
            return add(param, property, required);
        }

        /** 整数参数，带上下界（提示模型取值范围；越界仍会在执行侧夹紧）。 */
        public Builder integerParam(String param, String description, int min, int max, boolean required) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "integer");
            property.addProperty("description", description);
            property.addProperty("minimum", min);
            property.addProperty("maximum", max);
            return add(param, property, required);
        }

        /**
         * 字符串数组参数。
         *
         * <p>存在意义见 T001-6：蓝图用"分层字符画 + palette"表达，两层结构天然是字符串数组。
         * 若不支持数组，就只能让模型把整个蓝图塞成一个 JSON 字符串 —— 那会让它多转义一层引号与换行，
         * 也正是最容易出错的地方。
         *
         * <p>刻意只支持 {@code array<string>}：多一层嵌套（对象数组）会让模型产出错误率明显上升，
         * 而目前所有工具的入参都用不上。
         */
        public Builder arrayParam(String param, String description, boolean required) {
            JsonObject items = new JsonObject();
            items.addProperty("type", "string");

            JsonObject property = new JsonObject();
            property.addProperty("type", "array");
            property.addProperty("description", description);
            property.add("items", items);
            return add(param, property, required);
        }

        public ToolSpec build() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", properties);
            // required 即使是空数组也保留：Schema 结构固定，模型对稳定结构利用率更高
            schema.add("required", required);
            return new ToolSpec(name, description, schema);
        }

        private Builder add(String param, JsonObject property, boolean required) {
            properties.add(param, property);
            if (required) {
                this.required.add(param);
            }
            return this;
        }
    }
}
