package org.gwfx.aiassisted.core.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextPagerTest {

    @Test
    void keepsShortTextAsSinglePage() {
        List<String> pages = TextPager.paginate("你好，这是一句很短的话", 200);

        assertEquals(1, pages.size());
        assertEquals("你好，这是一句很短的话", pages.get(0));
    }

    @Test
    void returnsEmptyListForNullOrEmptyText() {
        assertTrue(TextPager.paginate(null, 100).isEmpty());
        assertTrue(TextPager.paginate("", 100).isEmpty());
    }

    @Test
    void neverExceedsPageSize() {
        String text = "字".repeat(1000);

        List<String> pages = TextPager.paginate(text, 120);

        assertTrue(pages.size() > 1, "长文本必须被切成多页");
        for (String page : pages) {
            assertTrue(page.length() <= 120, "页长度超限: " + page.length());
        }
    }

    @Test
    void losesNoContentWhenSplitting() {
        String text = "第一段内容。\n第二段内容。\n第三段内容，稍微长一点点来触发分页逻辑。";

        List<String> pages = TextPager.paginate(text, 20);

        assertEquals(withoutWhitespace(text), withoutWhitespace(String.join("", pages)));
    }

    @Test
    void prefersBreakingAtNewline() {
        // 换行落在页尾窗口内（20 页长的后一半），因此应在换行处切开，
        // 而不是硬切到 20 个字符把 "BBBBBBBB" 带上
        String text = "AAAAAAAAAAAA\nBBBBBBBBBBBBBBBBBBBB";

        List<String> pages = TextPager.paginate(text, 20);

        assertEquals("AAAAAAAAAAAA", pages.get(0));
        assertEquals("BBBBBBBBBBBBBBBBBBBB", pages.get(1));
    }

    @Test
    void doesNotBreakSoEarlyThatPagesBecomeFragments() {
        // 换行在很远的前面（第 5 个字符），超出页尾窗口：
        // 宁可硬切到页长上限，也不该产出只有 4 个字的碎片页
        String text = "AAAA\nBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

        List<String> pages = TextPager.paginate(text, 20);

        assertTrue(pages.get(0).length() >= 20,
                "页长窗口外的换行不该被当成切点，实际页长 " + pages.get(0).length());
    }

    @Test
    void prefersBreakingAtWhitespaceWhenNoNewline() {
        String text = "hello world this is a longer sentence";

        List<String> pages = TextPager.paginate(text, 12);

        // 每个切点都应落在空格上，因此不该出现把单词劈成两半的情况
        assertEquals("hello world", pages.get(0));
    }

    @Test
    void handlesTinyPageSizeWithoutLoopingForever() {
        List<String> pages = TextPager.paginate("abcdef", 1);

        assertEquals(6, pages.size());
        assertEquals("a", pages.get(0));
        assertEquals("f", pages.get(5));
    }

    @Test
    void treatsNonPositivePageSizeAsOne() {
        List<String> pages = TextPager.paginate("abc", 0);
        assertEquals(3, pages.size());
    }

    @Test
    void trailingWhitespaceProducesNoBlankPage() {
        List<String> pages = TextPager.paginate("abc      ", 100);

        assertEquals(1, pages.size());
        assertEquals("abc", pages.get(0));
    }

    private static String withoutWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }
}
