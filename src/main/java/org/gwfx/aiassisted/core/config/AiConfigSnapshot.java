package org.gwfx.aiassisted.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图形化配置界面的「同步体」：服务端 → 客户端的完整视图（零 MC 依赖）。
 *
 * <p><b>为什么整包走一个 JSON 字符串而不是 StreamCodec.composite 逐个字段</b>：
 * 这里要带 30 多项配置值外加字段级错误表，远超 {@code composite} 的参数上限，
 * 硬凑只能嵌套拼接、很脆。走 JSON 的好处是<b>字段集只在一处定义</b>（{@link AiConfigFields}），
 * 加一项不必动编解码，而且这份 (反)序列化是纯逻辑、可以直接单测。
 *
 * <p><b>客户端只读展示</b>：它渲染什么完全由服务端决定；{@code canEdit} 也由服务端下发，
 * 客户端拿它控制控件是否可点，<b>不构成任何授权</b> —— 更新包处理时会再查一次权限。
 *
 * <p><b>密钥永不出现在这里</b>：包里只有「来源枚举名」与（有权限时）文件路径，
 * 没有任何可以还原出密钥内容的东西。
 *
 * @param canEdit     发起者是否有权修改（门槛见配置 {@code ai.permission.adminLevel}）。为 false 时界面只读
 * @param values      全部可编辑字段的<b>规范文本值</b>，键 = TOML 键，见 {@link AiConfigFields}
 * @param effectiveProvider 实际生效的 provider（未知值会被回退成 mock）；配置值见 {@code values}
 * @param keySource   API Key 来源枚举名（none/environment/file/session），客户端本地化
 * @param keyFilePath 密钥文件路径；<b>仅在有编辑权限时下发</b>（非管理员不必看到服务端文件布局）
 * @param fieldErrors 字段名 → 本地化键（校验失败时逐字段提示）
 * @param adjusted    是否有值被规范化/夹紧（提示「已按允许范围调整」）
 * @param error       整体性错误（无权限、写盘失败等），优先于逐字段提示
 */
public record AiConfigSnapshot(
        boolean canEdit,
        Map<String, String> values,
        String effectiveProvider,
        String keySource,
        String keyFilePath,
        Map<String, String> fieldErrors,
        boolean adjusted,
        String error) {

    public AiConfigSnapshot {
        values = values == null ? Map.of() : Map.copyOf(values);
        effectiveProvider = nullToEmpty(effectiveProvider);
        keySource = nullToEmpty(keySource);
        keyFilePath = nullToEmpty(keyFilePath);
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
        error = nullToEmpty(error);
    }

    /** 取某个字段的规范文本；缺字段返回空串（界面控件按空值渲染，不炸）。 */
    public String value(String key) {
        return values.getOrDefault(key, "");
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("canEdit", canEdit);

        JsonObject fieldValues = new JsonObject();
        values.forEach(fieldValues::addProperty);
        root.add("values", fieldValues);

        root.addProperty("effectiveProvider", effectiveProvider);
        root.addProperty("keySource", keySource);
        root.addProperty("keyFilePath", keyFilePath);
        root.addProperty("adjusted", adjusted);
        root.addProperty("error", error);

        JsonObject errors = new JsonObject();
        fieldErrors.forEach(errors::addProperty);
        root.add("fieldErrors", errors);
        return root;
    }

    /**
     * 反序列化。
     *
     * <p>缺字段一律降级成默认值而不是抛异常：这份数据只用于展示，
     * 因为少一个字段就让整个界面报错是不划算的。
     */
    public static AiConfigSnapshot fromJson(String json) {
        JsonObject root = AiConfigEdits.parseObject(json);

        Map<String, String> values = new LinkedHashMap<>();
        JsonElement rawValues = root.get("values");
        if (rawValues != null && rawValues.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : rawValues.getAsJsonObject().entrySet()) {
                AiConfigEdits.string(entry.getValue()).ifPresent(text -> values.put(entry.getKey(), text));
            }
        }

        Map<String, String> errors = new LinkedHashMap<>();
        JsonElement rawErrors = root.get("fieldErrors");
        if (rawErrors != null && rawErrors.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : rawErrors.getAsJsonObject().entrySet()) {
                AiConfigEdits.string(entry.getValue()).ifPresent(text -> errors.put(entry.getKey(), text));
            }
        }

        return new AiConfigSnapshot(
                AiConfigEdits.boolOr(root, "canEdit", false),
                values,
                AiConfigEdits.stringOr(root, "effectiveProvider", ""),
                AiConfigEdits.stringOr(root, "keySource", ""),
                AiConfigEdits.stringOr(root, "keyFilePath", ""),
                errors,
                AiConfigEdits.boolOr(root, "adjusted", false),
                AiConfigEdits.stringOr(root, "error", ""));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
