package org.gwfx.aiassisted.core.memory;

import org.gwfx.aiassisted.core.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @TempDir
    Path root;

    private final UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 一份「合理」的配置；测试只覆盖需要验证的记忆项。 */
    private static AiConfig config(boolean enabled, int maxEntries, int injectCount) {
        return new AiConfig(
                true, "openai-compatible", "https://api.example.com/v1", "m", 0.7D, 1024, Duration.ofSeconds(60), 1,
                "p", false, "ai:", 200, 10, 3, 4,
                16, 8, 16, 5, true, 32, 300, 8, 20, 8000,
                true, 4, 10, 32768, 120, true, 4, 2, false, false, false, 4096, 64,
                enabled, maxEntries, injectCount, true, 600);
    }

    // ===== 写入与持久化 =====

    @Test
    void survivesStoreRecreation() {
        MemoryStore store = new MemoryStore(root);
        store.write(player, "玩家喜欢中世纪风格建筑", config(true, 100, 15));
        store.write(player, "基地主堡在东南方向", config(true, 100, 15));

        // 新实例（模拟服务端重启）：文件重新加载，内容与编号都还在
        MemoryStore reloaded = new MemoryStore(root);
        List<MemoryStore.Entry> entries = reloaded.search(player, "", 10);

        assertEquals(2, entries.size());
        assertEquals("基地主堡在东南方向", entries.get(0).text());
        // 编号是每玩家单调递增的：重启后继续涨，不会从 1 重复
        MemoryStore.AddResult added = reloaded.write(player, "第三条", config(true, 100, 15));
        assertNotNull(added);
        assertEquals(entries.get(0).id() + 1, added.entry().id());
    }

    @Test
    void truncatesLongTextOnWrite() {
        MemoryStore store = new MemoryStore(root);
        store.write(player, "长".repeat(1000), config(true, 100, 15));

        List<MemoryStore.Entry> entries = store.search(player, "", 10);
        assertEquals(1, entries.size());
        assertEquals(MemoryStore.ENTRY_MAX_CHARS, entries.get(0).text().length());
    }

    @Test
    void rejectsBlankInput() {
        MemoryStore store = new MemoryStore(root);
        assertNull(store.write(player, "   ", config(true, 100, 15)));
        assertNull(store.write(player, null, config(true, 100, 15)));
        assertEquals(0, store.search(player, "", 10).size());
    }

    // ===== 条数上限与驱逐 =====

    @Test
    void evictsOldestWhenCapReached() {
        MemoryStore store = new MemoryStore(root);
        AiConfig config = config(true, 10, 15);
        for (int i = 1; i <= 11; i++) {
            store.write(player, "记忆 " + i, config);
        }

        List<MemoryStore.Entry> entries = store.search(player, "", 20);
        assertEquals(10, entries.size());
        // 最旧的 #1 被挤出，剩下的是 #2..#11
        assertTrue(entries.stream().noneMatch(entry -> entry.id() == 1));
        assertTrue(entries.stream().anyMatch(entry -> entry.id() == 11));

        // 驱逐事件随写入结果返回，工具调用方据此告知模型
        MemoryStore.AddResult result = store.write(player, "记忆 12", config);
        assertNotNull(result);
        assertNotNull(result.evicted());
        assertEquals(2, result.evicted().id());
    }

    // ===== 搜索与遗忘 =====

    @Test
    void searchMatchesCaseInsensitiveSubstring() {
        MemoryStore store = new MemoryStore(root);
        store.write(player, "玩家喜欢中世纪风格建筑", config(true, 100, 15));
        store.write(player, "Test Foo Bar", config(true, 100, 15));

        assertEquals(1, store.search(player, "中世纪", 10).size());
        assertEquals(1, store.search(player, "foo", 10).size());
        assertTrue(store.search(player, "不存在的关键词", 10).isEmpty());
    }

    @Test
    void listWithoutQueryReturnsNewestFirst() {
        MemoryStore store = new MemoryStore(root);
        for (int i = 1; i <= 3; i++) {
            store.write(player, "记忆 " + i, config(true, 100, 15));
        }

        List<MemoryStore.Entry> entries = store.search(player, "", 10);
        assertEquals(3, entries.size());
        assertEquals("记忆 3", entries.get(0).text());
        assertEquals("记忆 1", entries.get(2).text());
    }

    @Test
    void forgetRemovesOnlyTargetEntry() {
        MemoryStore store = new MemoryStore(root);
        MemoryStore.AddResult first = store.write(player, "第一条", config(true, 100, 15));
        MemoryStore.AddResult second = store.write(player, "第二条", config(true, 100, 15));
        assertNotNull(first);
        assertNotNull(second);

        assertTrue(store.forget(player, first.entry().id()));
        assertFalse(store.forget(player, first.entry().id()));

        List<MemoryStore.Entry> entries = store.search(player, "", 10);
        assertEquals(1, entries.size());
        assertEquals("第二条", entries.get(0).text());
    }

    // ===== 清空（/ai memory clear） =====

    @Test
    void clearRemovesFileAndMemory() throws Exception {
        MemoryStore store = new MemoryStore(root);
        store.write(player, "会被清掉的记忆", config(true, 100, 15));
        assertTrue(Files.isRegularFile(root.resolve(player + ".json")));

        store.clear(player);

        assertFalse(Files.exists(root.resolve(player + ".json")));
        assertTrue(store.search(player, "", 10).isEmpty());

        // 清空后再写入从 #1 重新编号，且文件重新生成
        MemoryStore.AddResult added = store.write(player, "新的开始", config(true, 100, 15));
        assertNotNull(added);
        assertEquals(1, added.entry().id());
        assertTrue(Files.isRegularFile(root.resolve(player + ".json")));
    }

    // ===== 注入渲染 =====

    @Test
    void renderOmitsBlockWhenDisabledOrEmpty() {
        MemoryStore store = new MemoryStore(root);
        assertEquals("", store.renderForPrompt(player, config(false, 100, 15)));

        store.write(player, "有一条", config(true, 100, 15));
        assertEquals("", store.renderForPrompt(player, config(false, 100, 15)));
        assertEquals("", store.renderForPrompt(player, config(true, 100, 0)));
        assertEquals("", store.renderForPrompt(null, config(true, 100, 15)));
    }

    @Test
    void renderIncludesEntriesOldestFirstWithIds() {
        MemoryStore store = new MemoryStore(root);
        for (int i = 1; i <= 3; i++) {
            store.write(player, "记忆 " + i, config(true, 100, 15));
        }

        String rendered = store.renderForPrompt(player, config(true, 100, 15));
        assertTrue(rendered.startsWith("<memory>\n"));
        assertTrue(rendered.endsWith("</memory>"));
        int first = rendered.indexOf("#1");
        int last = rendered.indexOf("#3");
        assertTrue(first > 0 && last > first, "块内应按时间正序（旧→新）排列");
        assertTrue(rendered.contains("记忆 2"));
    }

    @Test
    void renderCapsInjectedCountAndBudget() {
        MemoryStore store = new MemoryStore(root);
        AiConfig config = config(true, 500, 3);
        for (int i = 1; i <= 20; i++) {
            store.write(player, "记忆 " + i, config);
        }

        String rendered = store.renderForPrompt(player, config);
        // injectCount=3：20 条里只带最近 3 条
        assertTrue(rendered.contains("#20"));
        assertFalse(rendered.contains("#17"));

        // 总预算：条数给高、每条都接近上限时，整块不能超过硬预算（+ 标签壳的少量余量）
        AiConfig big = config(true, 500, 50);
        String longText = "内".repeat(MemoryStore.ENTRY_MAX_CHARS);
        for (int i = 0; i < 10; i++) {
            store.write(player, longText, big);
        }
        String budgeted = store.renderForPrompt(player, big);
        assertTrue(budgeted.length() < MemoryStore.MAX_PROMPT_CHARS + 64,
                "记忆块超预算：" + budgeted.length());
    }

    @Test
    void storedTextNeverBreaksMemoryTag() {
        MemoryStore store = new MemoryStore(root);
        store.write(player, "含<标签>与\n换行的文本", config(true, 100, 15));

        String rendered = store.renderForPrompt(player, config(true, 100, 15));
        // 换行与尖括号在入库时被替换：渲染出的记忆正文里不允许再出现破坏块结构的字符
        assertFalse(rendered.contains("<标签>"));
        assertEquals(1, countOccurrences(rendered, "<memory>"));
        assertEquals(1, countOccurrences(rendered, "</memory>"));
        assertTrue(rendered.contains("含 标签 与 换行的文本"));
    }

    // ===== 玩家名字元数据 =====

    @Test
    void remembersNameAndShowsItInRender() {
        MemoryStore store = new MemoryStore(root);
        store.write(player, "喜欢石砖", config(true, 100, 15));
        store.rememberName(player, "Steve");

        assertEquals("Steve", store.nameOf(player));
        String rendered = store.renderForPrompt(player, config(true, 100, 15));
        assertTrue(rendered.contains("玩家名: Steve"));
        assertTrue(rendered.contains("喜欢石砖"));
    }

    @Test
    void namePersistsAcrossRecreation() {
        MemoryStore store = new MemoryStore(root);
        store.rememberName(player, "Steve");
        store.write(player, "一条", config(true, 100, 15));

        MemoryStore reloaded = new MemoryStore(root);
        assertEquals("Steve", reloaded.nameOf(player));
    }

    @Test
    void nameChangesUpdateInPlace() {
        MemoryStore store = new MemoryStore(root);
        store.rememberName(player, "OldName");
        store.rememberName(player, "NewName");

        // 名字是元数据不是记忆条目：改名不产生条目，也只保留最新的一个
        assertEquals("NewName", store.nameOf(player));
        assertEquals(0, store.search(player, "", 10).size());
    }

    @Test
    void clearRemovesNameToo() {
        MemoryStore store = new MemoryStore(root);
        store.rememberName(player, "Steve");
        store.clear(player);

        assertEquals("", store.nameOf(player));
        store.rememberName(player, "Steve");
        assertEquals("Steve", store.nameOf(player));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
