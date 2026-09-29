package org.gwfx.aiassisted.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言文件与字段表 / 界面用键的一致性。
 *
 * <p><b>为什么需要这个测试</b>：语言文件是纯资源，编译器不会替我们检查"表里加了字段、
 * 文案忘了写"。而那种错误的症状是界面上直接显示 {@code ai.ai_assisted.gui.field.ai.foo} 这种键名 ——
 * 玩家看不懂，也不会来报告，只会觉得这个界面是半成品。
 *
 * <p>所以这里逐个字段核对"标签 + 说明"在两种语言里都存在，再核一遍界面直接写死的那些键。
 */
class AiLangKeysTest {

    private static final List<String> LANGUAGES = List.of("zh_cn.json", "en_us.json");

    /** 界面直接引用的键（不经字段表），改了界面就容易忘，所以在这里钉住。 */
    private static final List<String> SCREEN_KEYS = List.of(
            "ai.ai_assisted.gui.title",
            "ai.ai_assisted.gui.test_connection",
            "ai.ai_assisted.gui.testing",
            "ai.ai_assisted.gui.test_success",
            "ai.ai_assisted.gui.test_failed",
            "ai.ai_assisted.gui.loading",
            "ai.ai_assisted.gui.on",
            "ai.ai_assisted.gui.off",
            "ai.ai_assisted.gui.apply",
            "ai.ai_assisted.gui.done",
            "ai.ai_assisted.gui.more",
            "ai.ai_assisted.gui.back",
            "ai.ai_assisted.gui.page",
            "ai.ai_assisted.gui.range",
            "ai.ai_assisted.gui.read_only",
            "ai.ai_assisted.gui.applying",
            "ai.ai_assisted.gui.api_key",
            "ai.ai_assisted.gui.api_key_desc",
            "ai.ai_assisted.gui.key_source",
            "ai.ai_assisted.gui.key_source_file",
            "ai.ai_assisted.gui.key_file",
            "ai.ai_assisted.gui.effective_provider",
            "ai.ai_assisted.gui.advanced.title",
            "ai.ai_assisted.gui.advanced.header",
            "ai.ai_assisted.gui.status.applied",
            "ai.ai_assisted.gui.status.adjusted",
            "ai.ai_assisted.gui.status.rejected",
            "ai.ai_assisted.gui.status.failed",
            "ai.ai_assisted.gui.status.nothing_to_apply",
            AiConfigEdits.ERROR_NO_PERMISSION,
            AiConfigEdits.ERROR_INVALID,
            AiConfigEdits.ERROR_NUMBER,
            AiConfigEdits.ERROR_UNKNOWN_FIELD,
            AiConfigEdits.ERROR_NO_FIELDS,
            AiConfigEdits.ERROR_PROVIDER,
            AiConfigEdits.ERROR_BASE_URL_EMPTY,
            AiConfigEdits.ERROR_BASE_URL_SCHEME,
            AiConfigEdits.ERROR_TEMPERATURE,
            AiConfigEdits.ERROR_MAX_TOKENS,
            AiConfigEdits.ERROR_PREFIX,
            // 空回复的两种可诊断形态（T001-6 排查建造时的空回复）
            "ai.ai_assisted.error.empty_reply_reason",
            "ai.ai_assisted.error.truncated_reply",
            // T002：身份自查 + 危险级指令的确认流程
            "ai.ai_assisted.level.0",
            "ai.ai_assisted.level.1",
            "ai.ai_assisted.level.2",
            "ai.ai_assisted.level.3",
            "ai.ai_assisted.level.4",
            "ai.ai_assisted.level.unknown",
            "ai.ai_assisted.whoami.level",
            "ai.ai_assisted.whoami.config",
            "ai.ai_assisted.whoami.tools",
            "ai.ai_assisted.whoami.allowed",
            "ai.ai_assisted.whoami.denied",
            "ai.ai_assisted.whoami.danger_on",
            "ai.ai_assisted.whoami.danger_off",
            "ai.ai_assisted.whoami.hint",
            "ai.ai_assisted.command.proposed",
            "ai.ai_assisted.command.none",
            "ai.ai_assisted.command.disabled",
            "ai.ai_assisted.command.low_level",
            "ai.ai_assisted.command.executed",
            "ai.ai_assisted.command.no_output",
            "ai.ai_assisted.command.truncated",
            "ai.ai_assisted.command.cancelled",
            "ai.ai_assisted.status.dangerous",
            "ai.ai_assisted.help.whoami",
            "ai.ai_assisted.help.confirm",
            "ai.ai_assisted.help.cancel",
            // T001-6：AI 建造
            "ai.ai_assisted.help.undo",
            "ai.ai_assisted.command.cancelled_all",
            "ai.ai_assisted.whoami.build",
            "ai.ai_assisted.whoami.build_on",
            "ai.ai_assisted.whoami.build_off",
            "ai.ai_assisted.status.build",
            "ai.ai_assisted.build.proposed",
            "ai.ai_assisted.build.materials",
            "ai.ai_assisted.build.preview_too_big",
            "ai.ai_assisted.build.confirm_hint",
            "ai.ai_assisted.build.started",
            "ai.ai_assisted.build.done",
            "ai.ai_assisted.build.aborted",
            "ai.ai_assisted.build.empty",
            "ai.ai_assisted.build.unknown_block",
            "ai.ai_assisted.build.closed",
            "ai.ai_assisted.build.blocked",
            "ai.ai_assisted.build.abort_unloaded",
            "ai.ai_assisted.build.abort_rejected",
            "ai.ai_assisted.build.busy",
            "ai.ai_assisted.build.undo_none",
            "ai.ai_assisted.build.undo_started",
            "ai.ai_assisted.build.undo_done",
            "ai.ai_assisted.build.undo_aborted");

    @Test
    void everyFieldHasLabelAndDescriptionInEveryLanguage() throws IOException {
        for (String language : LANGUAGES) {
            JsonObject lang = load(language);
            for (AiConfigFields.Field field : AiConfigFields.all()) {
                assertTrue(lang.has(field.labelKey()),
                        () -> language + " 缺少标签：" + field.labelKey());
                assertTrue(lang.has(field.descriptionKey()),
                        () -> language + " 缺少说明：" + field.descriptionKey());
            }
        }
    }

    @Test
    void everyGroupHasATitleInEveryLanguage() throws IOException {
        for (String language : LANGUAGES) {
            JsonObject lang = load(language);
            for (AiConfigFields.Group group : AiConfigFields.Group.values()) {
                assertTrue(lang.has(group.labelKey()),
                        () -> language + " 缺少分组标题：" + group.labelKey());
            }
        }
    }

    @Test
    void everyScreenKeyExistsInEveryLanguage() throws IOException {
        for (String language : LANGUAGES) {
            JsonObject lang = load(language);
            for (String key : SCREEN_KEYS) {
                assertTrue(lang.has(key), () -> language + " 缺少界面文案：" + key);
            }
        }
    }

    /**
     * 标签必须装得进标签列。
     *
     * <p>标签是和控件左右并排画的，标签列宽固定（{@code AiConfigFormScreen.LABEL_W}，78px）。
     * 名字太长就会直接压到输入框上 —— 这是已经踩过的坑，所以在这里按"全角 9px / 半角 6px"
     * 的保守估算卡一道；宽度取的都是上限，宁可估宽也不要漏。
     */
    @Test
    void labelsFitInTheLabelColumn() throws IOException {
        int labelColumnWidth = 78;
        for (String language : LANGUAGES) {
            JsonObject lang = load(language);
            for (AiConfigFields.Field field : AiConfigFields.all()) {
                String label = lang.get(field.labelKey()).getAsString();
                int width = estimateWidth(label);
                assertTrue(width <= labelColumnWidth,
                        () -> language + " 的标签太宽（约 " + width + "px > " + labelColumnWidth + "px）：" + label);
            }
            // 主界面固定行的标签也走同一条列宽，一并卡住
            String apiKeyLabel = lang.get("ai.ai_assisted.gui.api_key").getAsString();
            assertTrue(estimateWidth(apiKeyLabel) <= labelColumnWidth,
                    () -> language + " 的「API Key」标签太宽：" + apiKeyLabel);
        }
    }

    /** 粗略宽度估算：全角按 9px、半角按 6px，只取上界比较。 */
    private static int estimateWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 0x2E80 ? 9 : 6;
        }
        return width;
    }

    private static JsonObject load(String fileName) throws IOException {
        Path path = Path.of("src", "main", "resources", "assets", "ai_assisted", "lang", fileName);
        assertTrue(Files.isRegularFile(path),
                "找不到语言文件（测试工作目录应为本项目根目录）：" + path.toAbsolutePath());
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}

