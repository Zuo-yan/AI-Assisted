package org.gwfx.aiassisted.core.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图形化配置界面的更新校验：这是「不可信输入 → 可写入配置」的唯一通道，必须逐条验证。
 *
 * <p>校验语义是「<b>出现即改写</b>」：包里出现哪个键就校验并写入哪个键，没出现的保持原样。
 * 界面分了两页，一页只提交自己那一页的字段，所以不能再要求"一次凑齐所有字段"。
 */
class AiConfigEditsTest {

    /** 主界面那一页的合法输入，各用例只改需要验证的那一项。 */
    private static JsonObject valid() {
        JsonObject raw = new JsonObject();
        raw.addProperty(AiConfigEdits.KEY_ENABLED, true);
        raw.addProperty(AiConfigEdits.KEY_PROVIDER, "openai-compatible");
        raw.addProperty(AiConfigEdits.KEY_BASE_URL, "https://api.deepseek.com/v1");
        raw.addProperty(AiConfigEdits.KEY_MODEL, "deepseek-chat");
        raw.addProperty(AiConfigEdits.KEY_TEMPERATURE, "0.7");
        raw.addProperty(AiConfigEdits.KEY_MAX_TOKENS, "1024");
        raw.addProperty(AiConfigEdits.KEY_CHAT_PREFIX_ENABLED, false);
        raw.addProperty(AiConfigEdits.KEY_CHAT_PREFIX, "ai:");
        raw.addProperty(AiConfigEdits.KEY_TOOL_CALLING_ENABLED, true);
        raw.addProperty(AiConfigEdits.KEY_CONTAINERS_READ_CONTENTS, true);
        return raw;
    }

    @Test
    void acceptsAValidPayload() {
        AiConfigEdits.Result result = AiConfigEdits.parse(valid());

        assertTrue(result.ok(), () -> "不该有错误：" + result.errors());
        assertFalse(result.adjusted());
        assertEquals("", result.error());

        Map<String, String> values = result.values();
        assertEquals("openai-compatible", values.get(AiConfigEdits.KEY_PROVIDER));
        assertEquals("https://api.deepseek.com/v1", values.get(AiConfigEdits.KEY_BASE_URL));
        assertEquals("deepseek-chat", values.get(AiConfigEdits.KEY_MODEL));
        assertEquals("0.7", values.get(AiConfigEdits.KEY_TEMPERATURE));
        assertEquals("1024", values.get(AiConfigEdits.KEY_MAX_TOKENS));
        assertEquals("true", values.get(AiConfigEdits.KEY_TOOL_CALLING_ENABLED));
        assertEquals("true", values.get(AiConfigEdits.KEY_CONTAINERS_READ_CONTENTS));
        assertEquals(10, values.size());
    }

    // ===== 必须拦下的错误 =====

    @Test
    void rejectsUnknownProviderInsteadOfSilentlyFallingBack() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_PROVIDER, "Gemini");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        // 静默回退成 mock 会让玩家对着"没反应"发懵，必须当场报错
        assertFalse(result.ok());
        assertEquals(AiConfigEdits.ERROR_PROVIDER, result.errors().get(AiConfigEdits.KEY_PROVIDER));
    }

    @Test
    void rejectsBlankBaseUrl() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_BASE_URL, "   ");

        assertEquals(AiConfigEdits.ERROR_BASE_URL_EMPTY,
                AiConfigEdits.parse(raw).errors().get(AiConfigEdits.KEY_BASE_URL));
    }

    @Test
    void rejectsBaseUrlWithoutScheme() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_BASE_URL, "api.deepseek.com/v1");

        assertEquals(AiConfigEdits.ERROR_BASE_URL_SCHEME,
                AiConfigEdits.parse(raw).errors().get(AiConfigEdits.KEY_BASE_URL));
    }

    @Test
    void rejectsNonNumericTemperatureAndMaxTokens() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_TEMPERATURE, "热一点");
        raw.addProperty(AiConfigEdits.KEY_MAX_TOKENS, "很多");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        assertEquals(AiConfigEdits.ERROR_TEMPERATURE, result.errors().get(AiConfigEdits.KEY_TEMPERATURE));
        assertEquals(AiConfigEdits.ERROR_MAX_TOKENS, result.errors().get(AiConfigEdits.KEY_MAX_TOKENS));
    }

    @Test
    void reportsNumberErrorForOtherNumericFields() {
        JsonObject raw = new JsonObject();
        raw.addProperty("ai.toolMaxSteps", "很多步");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        assertEquals(AiConfigEdits.ERROR_NUMBER, result.errors().get("ai.toolMaxSteps"));
    }

    @Test
    void rejectsBlankPrefixWhileTriggerEnabled() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_CHAT_PREFIX_ENABLED, true);
        raw.addProperty(AiConfigEdits.KEY_CHAT_PREFIX, "   ");

        // 空前缀 + 开启触发 = 看起来开着但永远不触发，玩家会以为 AI 聊天坏了
        assertEquals(AiConfigEdits.ERROR_PREFIX,
                AiConfigEdits.parse(raw).errors().get(AiConfigEdits.KEY_CHAT_PREFIX));
    }

    @Test
    void allowsBlankPrefixWhenTriggerDisabled() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_CHAT_PREFIX_ENABLED, false);
        raw.addProperty(AiConfigEdits.KEY_CHAT_PREFIX, "");

        assertTrue(AiConfigEdits.parse(raw).ok());
    }

    @Test
    void skipsPrefixCheckWhenOnlyOneOfTheTwoFieldsIsSubmitted() {
        // 分页提交时可能只带前缀、不带开关；此时无法知道开关的现值，硬判会误伤合法输入
        JsonObject raw = new JsonObject();
        raw.addProperty(AiConfigEdits.KEY_CHAT_PREFIX, "");

        assertTrue(AiConfigEdits.parse(raw).ok());
    }

    @Test
    void rejectsUnknownKeyInsteadOfSilentlyIgnoringIt() {
        JsonObject raw = valid();
        raw.addProperty("ai.temperatureLimit", "0.5");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        // 拼错的键如果被静默忽略，玩家会以为改生效了 —— 必须报出来
        assertEquals(AiConfigEdits.ERROR_UNKNOWN_FIELD, result.errors().get("ai.temperatureLimit"));
    }

    @Test
    void rejectsPayloadWithoutAnyWritableField() {
        // 畸形/空 JSON 会解析成空对象。此时一个字段都不写，并明确报出来，
        // 而不是安静地"成功"了什么都没改
        AiConfigEdits.Result result = AiConfigEdits.parse(new JsonObject());

        assertFalse(result.ok());
        assertEquals(AiConfigEdits.ERROR_NO_FIELDS, result.error());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.values().isEmpty());
    }

    @Test
    void reportsInvalidForWrongJsonTypes() {
        JsonObject raw = valid();
        // 布尔字段收到字符串、字符串字段收到对象，都算格式不符
        raw.addProperty(AiConfigEdits.KEY_ENABLED, "true");
        raw.add(AiConfigEdits.KEY_PROVIDER, new JsonObject());

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        assertEquals(AiConfigEdits.ERROR_INVALID, result.errors().get(AiConfigEdits.KEY_ENABLED));
        assertEquals(AiConfigEdits.ERROR_INVALID, result.errors().get(AiConfigEdits.KEY_PROVIDER));
    }

    // ===== 分页提交：只写出现的字段 =====

    @Test
    void appliesOnlyTheKeysThatArePresent() {
        // 「更多设置」的某一页只提交自己那几项
        JsonObject raw = new JsonObject();
        raw.addProperty("ai.toolMaxSteps", "6");
        raw.addProperty("ai.history.maxMessages", "30");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        assertTrue(result.ok(), () -> "不该有错误：" + result.errors());
        assertEquals(2, result.values().size());
        assertEquals("6", result.values().get("ai.toolMaxSteps"));
        assertEquals("30", result.values().get("ai.history.maxMessages"));
    }

    @Test
    void ignoresApiKeySoItNeverEndsUpInConfigValues() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_API_KEY, "sk-secret");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        // 密钥不是 TOML 配置项：不进 values（也就不会被写进配置文件）
        assertTrue(result.ok());
        assertFalse(result.values().containsKey(AiConfigEdits.KEY_API_KEY));
        assertEquals("sk-secret", AiConfigEdits.apiKey(raw));
    }

    @Test
    void blankApiKeyMeansKeepTheCurrentOne() {
        assertEquals("", AiConfigEdits.apiKey(new JsonObject()));
        assertEquals("", AiConfigEdits.apiKey(AiConfigEdits.parseObject("{}")));

        JsonObject blank = new JsonObject();
        blank.addProperty(AiConfigEdits.KEY_API_KEY, "  ");
        assertEquals("", AiConfigEdits.apiKey(blank));

        JsonObject padded = new JsonObject();
        // 从网页复制密钥时常带尾随空格或换行，那会让服务端收到 401
        padded.addProperty(AiConfigEdits.KEY_API_KEY, " sk-x \n");
        assertEquals("sk-x", AiConfigEdits.apiKey(padded));
    }

    // ===== 规范化与夹紧：不报错，但要标记 adjusted =====

    @Test
    void stripsTrailingSlashAndFlagsAdjusted() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_BASE_URL, "https://api.example.com/v1/");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        assertTrue(result.ok());
        assertTrue(result.adjusted());
        assertEquals("https://api.example.com/v1", result.values().get(AiConfigEdits.KEY_BASE_URL));
    }

    @Test
    void clampsOutOfRangeNumbersAndFlagsAdjusted() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_TEMPERATURE, "5.0");
        raw.addProperty(AiConfigEdits.KEY_MAX_TOKENS, "999999");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        assertTrue(result.ok());
        assertTrue(result.adjusted());
        assertEquals(Integer.toString(AiConfig.MAX_MAX_TOKENS), result.values().get(AiConfigEdits.KEY_MAX_TOKENS));
        assertEquals(Double.toString(AiConfig.MAX_TEMPERATURE), result.values().get(AiConfigEdits.KEY_TEMPERATURE));
    }

    @Test
    void clampsEveryNumericFieldToItsDeclaredRange() {
        // 逐字段验证范围来自字段表：填个远超上界的值，必须落到表里写的上界
        for (AiConfigFields.Field field : AiConfigFields.all()) {
            if (!field.numeric()) {
                continue;
            }
            JsonObject raw = new JsonObject();
            raw.addProperty(field.key(), "999999999");

            AiConfigEdits.Result result = AiConfigEdits.parse(raw);

            assertTrue(result.ok(), () -> field.key() + " 不该报错：" + result.errors());
            assertEquals((long) field.max(),
                    (long) Double.parseDouble(result.values().get(field.key())),
                    () -> field.key() + " 应被夹到字段表里的上界");
        }
    }

    @Test
    void acceptsJsonNumbersForNumericFields() {
        // 界面发的是字符串，但别的前端发 JSON 数字也该被接受
        JsonObject raw = valid();
        raw.remove(AiConfigEdits.KEY_TEMPERATURE);
        raw.addProperty(AiConfigEdits.KEY_TEMPERATURE, 0.3D);

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        assertTrue(result.ok());
        assertEquals("0.3", result.values().get(AiConfigEdits.KEY_TEMPERATURE));
    }

    @Test
    void allowsEmptyModelBecauseItIsALegitimateState() {
        JsonObject raw = valid();
        raw.addProperty(AiConfigEdits.KEY_MODEL, "");

        AiConfigEdits.Result result = AiConfigEdits.parse(raw);

        // 模型留空表示"未设置"，/ai chat 会提示去配，不该当成错误
        assertTrue(result.ok());
        assertEquals("", result.values().get(AiConfigEdits.KEY_MODEL));
    }

    @Test
    void toleratesMalformedJsonString() {
        // 更新包的内容是字符串，非法 JSON 不能把网络线程炸掉，而要退化成"没有可写字段"
        AiConfigEdits.Result result = AiConfigEdits.parse(AiConfigEdits.parseObject("{ 这不是 JSON"));

        assertFalse(result.ok());
        assertEquals(AiConfigEdits.ERROR_NO_FIELDS, result.error());
    }

    // ===== 字段表 =====

    @Test
    void tableCoversEveryConfigKeyExactlyOnce() {
        for (AiConfigFields.Field field : AiConfigFields.all()) {
            assertEquals(field, AiConfigFields.byKey(field.key()).orElseThrow(),
                    () -> "键重复或查不到：" + field.key());
        }
        assertEquals(AiConfigFields.all().size(),
                AiConfigFields.all().stream().map(AiConfigFields.Field::key).distinct().count());
    }

    @Test
    void everyFieldHasATextProjection() {
        // 表里加了行却忘了在 textOf 里补投影，会在打开界面时炸；这里遍历全表把它测出来
        AiConfig config = new AiConfig(
                true, "openai-compatible", "https://api.example.com/v1", "m", 0.7D, 1024, java.time.Duration.ofSeconds(60), 1,
                "p", false, "ai:", 200, 10, 3, 4,
                16, 8, 16, 5, true, 32, 300, 8, 20, 8000,
                true, 4, 10, 32768, 120, true, 4, 2, false, false, false, 4096, 64,
                true, 100, 15, true, 600);

        Map<String, String> values = AiConfigFields.toTextMap(config);

        assertEquals(AiConfigFields.all().size(), values.size());
        for (AiConfigFields.Field field : AiConfigFields.all()) {
            assertFalse(values.get(field.key()).isEmpty(), () -> "投影为空：" + field.key());
        }

        // 未知键必须炸，而不是安静地返回空串（那会让界面显示成"配置丢了"）
        assertThrows(IllegalArgumentException.class, () -> AiConfigFields.textOf(config, "ai.not_a_field"));
    }

    @Test
    void groupsPartitionTheWholeTable() {
        int base = AiConfigFields.byGroup(AiConfigFields.Group.BASE).size();
        int advanced = AiConfigFields.advancedGroups().stream()
                .mapToInt(group -> AiConfigFields.byGroup(group).size())
                .sum();

        assertEquals(AiConfigFields.all().size(), base + advanced);
        assertTrue(base > 0 && advanced > 0);
    }

    // ===== 快照往返 =====

    @Test
    void snapshotSurvivesJsonRoundTrip() {
        AiConfigSnapshot original = new AiConfigSnapshot(
                true,
                Map.of(AiConfigEdits.KEY_MODEL, "deepseek-chat", "ai.permission.adminLevel", "4"),
                "openai-compatible",
                "ENVIRONMENT",
                "config/zuoyanmod/ai-secret.dat",
                Map.of(AiConfigEdits.KEY_MODEL, "x"),
                true,
                "");

        AiConfigSnapshot restored = AiConfigSnapshot.fromJson(original.toJson().toString());

        assertEquals(original, restored);
    }

    @Test
    void snapshotToleratesGarbage() {
        // 客户端只用于展示，缺字段不该整屏报错
        AiConfigSnapshot restored = AiConfigSnapshot.fromJson("不是 JSON");

        assertFalse(restored.canEdit());
        assertTrue(restored.fieldErrors().isEmpty());
        assertTrue(restored.values().isEmpty());
        assertEquals("", restored.value(AiConfigEdits.KEY_MODEL));
    }
}

