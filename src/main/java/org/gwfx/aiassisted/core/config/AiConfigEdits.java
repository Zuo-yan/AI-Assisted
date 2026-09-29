package org.gwfx.aiassisted.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.gwfx.aiassisted.core.llm.ProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 图形化配置界面的「更新请求」解析与校验（零 MC 依赖，可直接单测）。
 *
 * <p><b>为什么这层单独存在</b>：界面把用户输入当文本发过来，服务端必须把它变成合法值或
 * 一句可读的拒绝理由。这段逻辑既不该写在 Screen 里（客户端说了不算），也不该散落在
 * 网络处理里（不可测）。放在 {@code ai.core} 就同时满足了「服务端权威」与「可单测」。
 *
 * <p><b>字段从哪来</b>：全部来自 {@link AiConfigFields} 那张表，这里不再逐个字段写代码。
 * 加一项配置只需改表 + 语言文件 —— 这正是把 30 多项配置都搬进界面之后仍然可维护的前提。
 *
 * <p><b>「出现即改写」语义</b>：包里出现哪个键就校验并写入哪个键，没出现的键保持原样。
 * 界面现在分了两页（主界面 + 更多设置），一页只提交自己那一页的字段，因此不能用
 * 「一次必须凑齐所有字段」的老规矩。代价是"拼错的键会被静默忽略"，所以补了两条护栏：
 * <b>未知键直接报错</b>，以及<b>一个字段都没有的包整体报错</b>。
 *
 * <p><b>校验的松紧尺度</b>：只拦「确定是配错了」的情况（provider 拼错、地址缺 scheme、
 * 数字解析不出来、开了前缀触发却把前缀留空）。数值越界<b>不报错而是夹紧</b>，
 * 并把 {@code adjusted} 标记带回客户端 —— 界面回包后会用权威值刷新控件，
 * 所以玩家看到的就是真正生效的值，不会出现「以为改了其实没改」。
 */
public final class AiConfigEdits {

    // ===== 线字段名（= TOML 键）=====

    public static final String KEY_ENABLED = "ai.enabled";
    public static final String KEY_PROVIDER = "ai.provider";
    public static final String KEY_BASE_URL = "ai.baseUrl";
    public static final String KEY_MODEL = "ai.model";
    public static final String KEY_TEMPERATURE = "ai.temperature";
    public static final String KEY_MAX_TOKENS = "ai.maxTokens";
    public static final String KEY_CHAT_PREFIX_ENABLED = "ai.chatPrefixEnabled";
    public static final String KEY_CHAT_PREFIX = "ai.chatPrefix";
    public static final String KEY_TOOL_CALLING_ENABLED = "ai.toolCallingEnabled";
    public static final String KEY_CONTAINERS_READ_CONTENTS = "ai.tool.containersReadContents";

    /**
     * API Key 的线字段名。
     *
     * <p><b>它刻意不是 TOML 键</b>，也不在 {@link AiConfigFields} 的表里：密钥统一由
     * {@code ai.secret.KeyStore}（环境变量优先，其次运行目录下的加密文件）保管，
     * 绝不能进 ModConfigSpec —— 那会把明文写进 {@code zuoyanmod-common.toml}，每次启动还被重写一遍。
     *
     * <p>因此它只作为<b>一次性输入</b>随更新包上行：服务端取出后写进 KeyStore，
     * 且<b>不回传</b>任何形式的密钥内容。这里既不校验也不放进 {@link Result#values()}。
     */
    public static final String KEY_API_KEY = "ai.api_key";

    // ===== 校验失败时回给界面的本地化键（界面显示在对应字段旁）=====

    /** 字段缺失或 JSON 类型不符（不该出现，只可能来自版本错配或伪造包）。 */
    public static final String ERROR_INVALID = "ai.ai_assisted.gui.error.invalid";
    /** 服务端复核权限失败（客户端控件的可用状态不构成授权）。 */
    public static final String ERROR_NO_PERMISSION = "ai.ai_assisted.gui.error.no_permission";
    /** 数值字段解析不出数字。 */
    public static final String ERROR_NUMBER = "ai.ai_assisted.gui.error.number";
    /** 包里出现了字段表里没有的键 —— 多半是界面与插件版本不一致，或有人在伪造。 */
    public static final String ERROR_UNKNOWN_FIELD = "ai.ai_assisted.gui.error.unknown_field";
    /** 整个包一个可写字段都没有。 */
    public static final String ERROR_NO_FIELDS = "ai.ai_assisted.gui.error.no_fields";
    public static final String ERROR_PROVIDER = "ai.ai_assisted.gui.error.provider";
    public static final String ERROR_BASE_URL_EMPTY = "ai.ai_assisted.gui.error.base_url_empty";
    public static final String ERROR_BASE_URL_SCHEME = "ai.ai_assisted.gui.error.base_url_scheme";
    public static final String ERROR_TEMPERATURE = "ai.ai_assisted.gui.error.temperature";
    public static final String ERROR_MAX_TOKENS = "ai.ai_assisted.gui.error.max_tokens";
    public static final String ERROR_PREFIX = "ai.ai_assisted.gui.error.prefix";

    /**
     * 校验结果。
     *
     * @param values   校验通过后的规范值（键 = TOML 键，值 = 规范文本）；有错误时调用方<b>不得</b>使用它写盘
     * @param errors   字段名 → 本地化键；非空即整体拒绝
     * @param adjusted 是否有值被规范化或夹紧（尾斜杠、越界），供界面提示「已按允许范围调整」
     * @param error    整体性错误（包是空的等），优先于逐字段提示；无错时为空串
     */
    public record Result(Map<String, String> values, Map<String, String> errors,
                         boolean adjusted, String error) {

        public Result {
            values = values == null ? Map.of() : Map.copyOf(values);
            errors = errors == null ? Map.of() : Map.copyOf(errors);
            error = error == null ? "" : error;
        }

        public boolean ok() {
            return errors.isEmpty() && error.isEmpty();
        }
    }

    private AiConfigEdits() {
    }

    /**
     * 宽松地把字符串解析成 JSON 对象。
     *
     * <p>非法输入返回空对象而不是抛异常：调用方（更新校验）会把「字段全缺」逐项报成错误，
     * 这比让一个畸形包把网络线程炸掉合适得多。
     */
    public static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (com.google.gson.JsonSyntaxException | IllegalStateException e) {
            return new JsonObject();
        }
    }

    /** 打开界面时的初值：全表投影成「字段键 → 规范文本」。 */
    public static Map<String, String> of(AiConfig config) {
        return AiConfigFields.toTextMap(config);
    }

    /**
     * 取出更新请求里的 API Key。
     *
     * <p><b>空串/缺失 = 本次不改密钥</b>（界面留空即保持原值），而不是"把密钥清掉" ——
     * 界面整表提交，若把空串当成清除，玩家改个温度就会顺手把自己的 Key 删了。
     * 要清除请用 {@code /ai key clear}。
     *
     * <p>不做格式校验：各家密钥的形态没有公共规范（长度、前缀、字符集都不同），
     * 编一套规则只会把合法密钥判成非法。唯一的处理是去首尾空白 ——
     * 从网页复制密钥时尾部常带空格或换行，那会让服务端收到 401。
     */
    public static String apiKey(JsonObject raw) {
        return text(raw, KEY_API_KEY).orElse("").strip();
    }

    /** 解析并校验一次更新请求。 */
    public static Result parse(JsonObject raw) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        boolean adjusted = false;

        if (raw != null) {
            for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
                String key = entry.getKey();
                if (KEY_API_KEY.equals(key)) {
                    // 密钥不进字段表：由更新包单独取出写进 KeyStore，这里既不校验也不回传
                    continue;
                }
                Optional<AiConfigFields.Field> field = AiConfigFields.byKey(key);
                if (field.isEmpty()) {
                    errors.put(key, ERROR_UNKNOWN_FIELD);
                    continue;
                }
                Outcome outcome = read(field.get(), entry.getValue());
                if (outcome.error() != null) {
                    errors.put(key, outcome.error());
                } else {
                    values.put(key, outcome.value());
                    adjusted |= outcome.adjusted();
                }
            }
        }

        checkPrefixConsistency(values, errors);

        // 一个可写字段都没有：说明这不是界面发出来的（伪造包、版本错配、或 JSON 被截断）。
        // 明确报错，比"回一句已应用但什么都没改"好排查。
        String error = values.isEmpty() && errors.isEmpty() ? ERROR_NO_FIELDS : "";
        return new Result(values, errors, adjusted, error);
    }

    // ===== 逐字段解析 =====

    /** 单字段结果：{@code value}/{@code error} 二选一。 */
    private record Outcome(String value, String error, boolean adjusted) {

        static Outcome ok(String value, boolean adjusted) {
            return new Outcome(value, null, adjusted);
        }

        static Outcome fail(String errorKey) {
            return new Outcome(null, errorKey, false);
        }
    }

    private static Outcome read(AiConfigFields.Field field, JsonElement element) {
        return switch (field.kind()) {
            case BOOL -> readBool(field, element);
            case TEXT -> readText(field, element);
            case INT -> readNumber(field, element, true);
            case DOUBLE -> readNumber(field, element, false);
        };
    }

    private static Outcome readBool(AiConfigFields.Field field, JsonElement element) {
        Optional<Boolean> parsed = bool(element);
        return parsed.map(value -> Outcome.ok(Boolean.toString(value), false))
                .orElseGet(() -> Outcome.fail(field.parseErrorKey()));
    }

    private static Outcome readText(AiConfigFields.Field field, JsonElement element) {
        Optional<String> parsed = string(element);
        if (parsed.isEmpty()) {
            return Outcome.fail(field.parseErrorKey());
        }
        String raw = parsed.get();
        return switch (field.key()) {
            case KEY_PROVIDER -> {
                String provider = raw.strip();
                // 与 /ai provider 同源：拼错当场报错，而不是静默回退成 mock 让玩家对着回显发懵
                yield ProviderRegistry.isKnown(provider)
                        ? Outcome.ok(provider, false)
                        : Outcome.fail(ERROR_PROVIDER);
            }
            case KEY_BASE_URL -> {
                String stripped = raw.strip();
                String normalized = AiConfig.normalizeBaseUrl(stripped);
                if (normalized.isEmpty()) {
                    yield Outcome.fail(ERROR_BASE_URL_EMPTY);
                }
                if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                    yield Outcome.fail(ERROR_BASE_URL_SCHEME);
                }
                // 末尾斜杠被去掉时标记 adjusted，界面据此提示"已按允许范围调整"
                yield Outcome.ok(normalized, !normalized.equals(stripped));
            }
            case KEY_MODEL, KEY_CHAT_PREFIX -> Outcome.ok(raw.strip(), false);
            // 其余文本（目前只有系统提示词）刻意不 strip：多行提示词的首尾空白可能是有意的
            default -> Outcome.ok(raw, false);
        };
    }

    /**
     * @param integer true 走 int 语义（小数截断），false 走 double
     */
    private static Outcome readNumber(AiConfigFields.Field field, JsonElement element, boolean integer) {
        Optional<Double> parsed = number(element);
        if (parsed.isEmpty()) {
            // JSON null 归到"缺失/格式不符"，其余归到字段自己的"要填数字"提示
            boolean nullish = element == null || element.isJsonNull();
            return Outcome.fail(nullish ? ERROR_INVALID : field.parseErrorKey());
        }

        double value = parsed.get();
        if (integer) {
            int min = (int) field.min();
            int max = (int) field.max();
            int raw = (int) value;
            int clamped = Math.max(min, Math.min(max, raw));
            return Outcome.ok(Integer.toString(clamped), clamped != raw);
        }
        double safe = Double.isNaN(value) ? field.min() : value;
        double clamped = Math.max(field.min(), Math.min(field.max(), safe));
        return Outcome.ok(Double.toString(clamped), clamped != safe);
    }

    /**
     * 「开了前缀触发却把前缀留空」是一个"看起来开着、其实永远不触发"的配置，玩家会以为 AI 聊天坏了。
     *
     * <p>只有<b>两个字段本次都被提交</b>时才判定：分页提交的包里可能只有其中一个，
     * 此时无法知道另一个的现值，硬判会误伤。
     */
    private static void checkPrefixConsistency(Map<String, String> values, Map<String, String> errors) {
        String enabled = values.get(KEY_CHAT_PREFIX_ENABLED);
        String prefix = values.get(KEY_CHAT_PREFIX);
        if (enabled == null || prefix == null) {
            return;
        }
        if (Boolean.parseBoolean(enabled) && prefix.isEmpty()) {
            errors.put(KEY_CHAT_PREFIX, ERROR_PREFIX);
            // 被拒的字段不留在"待写入"集合里，避免调用方误用这份 values
            values.remove(KEY_CHAT_PREFIX);
        }
    }

    // ===== JSON 取值助手（同包的 AiConfigSnapshot 也复用，避免写两遍）=====

    /** 取字符串：必须真的是 JSON 字符串。缺失、null、数字、布尔都返回 empty。 */
    static Optional<String> string(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        // 必须查 isString：JsonPrimitive 也覆盖数字与布尔，不查就会把 123 当成 "123"
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? Optional.of(primitive.getAsString()) : Optional.empty();
    }

    /** 取布尔：只认 JSON 布尔。字符串 "true" 不算 —— 那多半是前端类型用错了。 */
    static Optional<Boolean> bool(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isBoolean() ? Optional.of(primitive.getAsBoolean()) : Optional.empty();
    }

    /**
     * 取数字。刻意同时接受「字符串形式的数字」与「JSON 数字」：
     * 界面把输入框内容当文本发过来（字符串），而将来若有别的前端发 JSON 数字，
     * 也不该因为类型不同就报一句难懂的「不是合法数字」。
     */
    static Optional<Double> number(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return Optional.of(primitive.getAsDouble());
        }
        if (!primitive.isString()) {
            return Optional.empty();
        }
        String trimmed = primitive.getAsString().strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(trimmed));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<String> text(JsonObject raw, String key) {
        return string(raw == null ? null : raw.get(key));
    }

    static String stringOr(JsonObject raw, String key, String fallback) {
        return text(raw, key).orElse(fallback);
    }

    static boolean boolOr(JsonObject raw, String key, boolean fallback) {
        JsonElement element = raw == null ? null : raw.get(key);
        return bool(element).orElse(fallback);
    }
}
