package org.gwfx.aiassisted.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 危险级指令文本的校验（T002）。
 *
 * <p>这段文本来自模型输出、最终会被交给 Brigadier 执行，所以每一条规则都要有测试钉住 ——
 * 尤其是"拒绝换行"这一条：它既是保护 {@code <context>} 结构，也是防止把两条指令伪装成一条。
 */
class CommandTextTest {

    @Test
    void stripsLeadingSlashes() {
        assertEquals("list", CommandText.normalize("/list").command());
        assertEquals("list", CommandText.normalize("//list").command());
        // 玩家习惯在斜杠后加空格，也要能认
        assertEquals("list", CommandText.normalize(" / list ").command());
        assertEquals("time query daytime", CommandText.normalize("  time query daytime  ").command());
    }

    @Test
    void rejectsBlankInput() {
        assertFalse(CommandText.normalize(null).ok());
        assertFalse(CommandText.normalize("   ").ok());
        // 只写一个斜杠等于什么都没说
        assertFalse(CommandText.normalize("/").ok());
        assertEquals("指令不能为空", CommandText.normalize("  ").error());
    }

    @Test
    void rejectsControlCharactersAndNewlines() {
        CommandText.Result result = CommandText.normalize("list\ngive @a bedrock");

        assertFalse(result.ok());
        assertTrue(result.error().contains("换行"), result.error());
        assertFalse(CommandText.normalize("list\u0000").ok());
        assertFalse(CommandText.normalize("list\ttime").ok());
    }

    @Test
    void rejectsOverlongCommand() {
        String tooLong = "say " + "x".repeat(CommandText.MAX_COMMAND_CHARS);

        CommandText.Result result = CommandText.normalize(tooLong);

        assertFalse(result.ok());
        assertTrue(result.error().contains("太长"), result.error());
        // 刚好到上限要放行（边界不能被"多一个字符"的写法判错）
        String atLimit = "x".repeat(CommandText.MAX_COMMAND_CHARS);
        assertTrue(CommandText.normalize(atLimit).ok());
    }

    @Test
    void reasonIsCleanedNotRejected() {
        // 理由只是展示给玩家看的，缺了/脏了都不该拦住执行
        assertEquals("", CommandText.normalizeReason(null));
        assertEquals("", CommandText.normalizeReason("  "));
        assertEquals("a b", CommandText.normalizeReason("a\nb"));
        assertEquals("x".repeat(CommandText.MAX_REASON_CHARS),
                CommandText.normalizeReason("x".repeat(CommandText.MAX_REASON_CHARS + 50)));
    }
}
