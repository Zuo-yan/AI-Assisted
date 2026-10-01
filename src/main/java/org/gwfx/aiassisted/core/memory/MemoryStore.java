package org.gwfx.aiassisted.core.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gwfx.aiassisted.core.config.AiConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每玩家一份的<b>长期记忆</b>（T001-8）：AI 自主维护、跨会话持久的备忘录。
 *
 * <p><b>它存什么</b>：关于「这位玩家」的持久事实与偏好（喜欢什么风格、基地在哪、有什么约定），
 * 由模型通过 memory_write / memory_forget 自主增删，每轮对话把最近的几条注入系统提示词。
 * 它<b>不存</b>一次性状态（坐标、时间、当轮任务）——那些是 {@code <context>} 的职责。
 *
 * <p><b>持久化方式</b>：每玩家一个 JSON 文件（{@code <root>/<uuid>.json}），首次访问惰性加载，
 * 每次变更即落盘。与 {@code chat/} 下几个仅内存的 Store 刻意不同 —— 记忆的全部价值就在于跨会话。
 * 文件损坏按「空记忆」降级（与 KeyStore 同一立场），绝不因读盘失败打断对话。
 *
 * <p><b>线程约定</b>：所有读写都发生在服务端主线程（请求入口与工具分发都经 {@code server.execute}），
 * 仍按保守风格对每个玩家的文件状态加锁，写盘同步进行（文件极小、写入频率极低）。
 *
 * <p>纯 Java、零 MC 依赖（根路径由外部注入），可直接单测。
 */
public final class MemoryStore {

    /** 单条记忆的文本上限。入库即截断：一条记忆本该是一个自包含的事实，不是一篇日记。 */
    public static final int ENTRY_MAX_CHARS = 500;

    /** 注入提示词的记忆块总字符上限（与 ContextRenderer 对 {@code <context>} 的预算同一量级）。 */
    public static final int MAX_PROMPT_CHARS = 1200;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path root;
    private final Map<UUID, PlayerMemory> files = new ConcurrentHashMap<>();

    public MemoryStore(Path root) {
        this.root = root == null ? Path.of(".") : root;
    }

    // ===== 写 =====

    /**
     * 写入一条记忆；空白输入视为无效，返回 {@code null}。
     *
     * <p>条数达到 {@code ai.memory.maxEntries} 时<b>挤掉最旧的一条</b>而不是拒绝写入：
     * 记忆的价值随时间衰减，最旧的要么已经过时、要么玩家会用 memory_forget 主动清理。
     * 被挤掉的条目随结果返回，由工具调用方告知模型（它需要知道自己「忘了什么」）。
     */
    public AddResult write(UUID player, String rawText, AiConfig config) {
        if (player == null || config == null) {
            return null;
        }
        String text = normalize(rawText);
        if (text.isEmpty()) {
            return null;
        }
        PlayerMemory file = fileOf(player);
        synchronized (file) {
            Entry evicted = null;
            long id = file.nextId++;
            long now = System.currentTimeMillis();
            file.entries.add(new Entry(id, text, now, now));
            while (file.entries.size() > config.memoryMaxEntries()) {
                evicted = file.entries.remove(0);
            }
            save(player, file);
            return new AddResult(file.entries.get(file.entries.size() - 1), evicted);
        }
    }

    /** 按编号删除一条记忆。返回是否真的删掉了（模型需要区分「删了」和「本来就没有」）。 */
    public boolean forget(UUID player, long id) {
        if (player == null) {
            return false;
        }
        PlayerMemory file = fileOf(player);
        synchronized (file) {
            boolean removed = file.entries.removeIf(entry -> entry.id() == id);
            if (removed) {
                save(player, file);
            }
            return removed;
        }
    }

    /** 清空并删除记忆文件（{@code /ai memory clear} 的隐私出口）。 */
    public void clear(UUID player) {
        if (player == null) {
            return;
        }
        PlayerMemory file = this.files.remove(player);
        if (file != null) {
            synchronized (file) {
                file.entries.clear();
                file.nextId = 1;
                file.name = "";
            }
        }
        try {
            Files.deleteIfExists(fileFor(player));
        } catch (IOException e) {
            // 删不掉就留在磁盘上：内存里已清空，下次加载是空文件覆盖，不影响任何行为
        }
    }

    /**
     * 记下玩家最新使用的名字（元数据，不是一条记忆）。
     *
     * <p>记忆文件按 UUID 定位，但模型与玩家打交道用的是名字 —— 没有它，AI 连「叫出名字」
     * 都做不到。名字变化才写盘；它是服务端本就公开的信息，只影响注入提示词的第一行。
     */
    public void rememberName(UUID player, String name) {
        if (player == null || name == null || name.isBlank()) {
            return;
        }
        PlayerMemory file = fileOf(player);
        synchronized (file) {
            if (name.equals(file.name)) {
                return;
            }
            file.name = name;
            save(player, file);
        }
    }

    /** 玩家最新使用的名字；从未记录过返回空串。 */
    public String nameOf(UUID player) {
        if (player == null) {
            return "";
        }
        PlayerMemory file = fileOf(player);
        synchronized (file) {
            return file.name;
        }
    }

    // ===== 读 =====

    /**
     * 搜索记忆：大小写不敏感的子串匹配；query 空白时按<b>时间倒序</b>返回最近的条目。
     *
     * <p>没有嵌入向量、没有语义检索 —— 子串加时间序对「几百条以内的个人备忘」完全够用，
     * 且零 API 成本、零额外依赖。每条记忆入库时已截断，这里的 limit 再兜一道底。
     */
    public List<Entry> search(UUID player, String query, int limit) {
        if (player == null || limit <= 0) {
            return List.of();
        }
        PlayerMemory file = fileOf(player);
        synchronized (file) {
            // 从最新往回找：无论搜索还是列举，最近的记忆总是最可能被引用的
            List<Entry> matches = new ArrayList<>();
            for (int i = file.entries.size() - 1; i >= 0 && matches.size() < limit; i--) {
                Entry entry = file.entries.get(i);
                if (matchesQuery(entry, query)) {
                    matches.add(entry);
                }
            }
            return List.copyOf(matches);
        }
    }

    private static boolean matchesQuery(Entry entry, String query) {
        // Locale.ROOT：土耳其等 locale 下 'I'.toLowerCase() 会变成 'ı'，匹配会莫名失败
        String keyword = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (keyword.isEmpty()) {
            return true;
        }
        return entry.text().toLowerCase(Locale.ROOT).contains(keyword);
    }

    // ===== 注入提示词 =====

    /**
     * 渲染 {@code <memory>} 块：最近的 {@code ai.memory.injectCount} 条、整块 1200 字符封顶，
     * 块内按<b>时间正序</b>排列（与对话的自然时序一致，也更省 token）。
     * 功能关闭或没有记忆时返回空串 —— 不留空壳，与「可选块不渲染」的既有立场一致。
     */
    public String renderForPrompt(UUID player, AiConfig config) {
        if (player == null || config == null || !config.memoryEnabled() || config.memoryInjectCount() <= 0) {
            return "";
        }
        List<Entry> recent = search(player, "", config.memoryInjectCount());
        if (recent.isEmpty()) {
            return "";
        }
        // recent 是最新在前；在预算内尽量多带，超出预算的更旧条目直接放弃
        List<Entry> kept = new ArrayList<>(recent.size());
        int used = 0;
        for (Entry entry : recent) {
            String line = renderLine(entry);
            if (used + line.length() > MAX_PROMPT_CHARS) {
                break;
            }
            used += line.length();
            kept.add(entry);
        }
        StringBuilder out = new StringBuilder(used + 64);
        out.append("<memory>\n");
        // 名字是文件元数据：模型知道「在跟谁说话」，问候与称呼才有落点
        String name = nameOf(player);
        if (!name.isEmpty()) {
            out.append("玩家名: ").append(name).append('\n');
        }
        for (int i = kept.size() - 1; i >= 0; i--) {
            out.append(renderLine(kept.get(i))).append('\n');
        }
        out.append("</memory>");
        return out.toString();
    }

    private static String renderLine(Entry entry) {
        return "- [#" + entry.id() + "] " + entry.text()
                + "（" + formatDate(entry.createdAt()) + "）";
    }

    // ===== 落盘 =====

    private PlayerMemory fileOf(UUID player) {
        return this.files.computeIfAbsent(player, key -> load(player));
    }

    private PlayerMemory load(UUID player) {
        PlayerMemory file = new PlayerMemory();
        Path path = fileFor(player);
        if (!Files.isRegularFile(path)) {
            return file;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return file;
            }
            JsonObject root = parsed.getAsJsonObject();
            file.nextId = asLong(root.get("nextId"), 0L);
            file.name = asText(root.get("name"));
            JsonElement entriesElement = root.get("entries");
            if (entriesElement != null && entriesElement.isJsonArray()) {
                JsonArray entries = entriesElement.getAsJsonArray();
                for (JsonElement element : entries) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject item = element.getAsJsonObject();
                    // 逐字段宽容读取：单条损坏跳过那一条，不弃整个文件
                    long id = asLong(item.get("id"), -1L);
                    String text = asText(item.get("text"));
                    if (id < 0 || text.isEmpty()) {
                        continue;
                    }
                    file.entries.add(new Entry(id, text,
                            asLong(item.get("createdAt"), 0L),
                            asLong(item.get("updatedAt"), 0L)));
                    file.nextId = Math.max(file.nextId, id + 1);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 损坏/不可读 → 空记忆起步。写盘时覆盖，等于自愈；不向对话链路抛任何异常
            return new PlayerMemory();
        }
        return file;
    }

    private static String asText(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return "";
        }
        return element.getAsString();
    }

    /** 宽容读一个 long：字段缺失、类型不对或不是数字时返回兜底值（绝不因单条损坏抛异常）。 */
    private static long asLong(JsonElement element, long fallback) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void save(UUID player, PlayerMemory file) {
        try {
            Files.createDirectories(this.root);
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("nextId", file.nextId);
            if (!file.name.isEmpty()) {
                root.addProperty("name", file.name);
            }
            JsonArray entries = new JsonArray();
            for (Entry entry : file.entries) {
                JsonObject item = new JsonObject();
                item.addProperty("id", entry.id());
                item.addProperty("text", entry.text());
                item.addProperty("createdAt", entry.createdAt());
                item.addProperty("updatedAt", entry.updatedAt());
                entries.add(item);
            }
            root.add("entries", entries);
            Files.writeString(fileFor(player), root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写盘失败只影响持久性：内存里这条记忆本轮对话仍然可用，下次变更会再试
        }
    }

    private Path fileFor(UUID player) {
        return this.root.resolve(player.toString() + ".json");
    }

    private static String normalize(String rawText) {
        if (rawText == null) {
            return "";
        }
        // 与 ContextRenderer 对进块文本的处理同源：换行/尖括号都可能破坏 <memory> 结构，
        // 在入库这一刻就清掉 —— 保证「存进去的」和「发出去的」永远一致
        StringBuilder cleaned = new StringBuilder(rawText.length());
        for (int i = 0; i < rawText.length() && cleaned.length() < ENTRY_MAX_CHARS; i++) {
            char c = rawText.charAt(i);
            if (c == '<' || c == '>' || c == '\n' || c == '\r' || c == '\t' || Character.isISOControl(c)) {
                cleaned.append(' ');
            } else {
                cleaned.append(c);
            }
        }
        return cleaned.toString().strip();
    }

    private static String formatDate(long epochMillis) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** 一次写入的结果：新建的条目 + 可能被挤出上限的最旧条目（无驱逐时为 null）。 */
    public record AddResult(Entry entry, Entry evicted) {
    }

    /** 一条记忆。id 是每玩家文件内单调递增的编号（memory_forget 与渲染都靠它定位）。 */
    public record Entry(long id, String text, long createdAt, long updatedAt) {
        public Entry {
            text = text == null ? "" : text;
        }
    }

    /** 单个玩家的文件状态。所有访问都应持本对象锁（见各类方法的 synchronized 块）。 */
    private static final class PlayerMemory {
        private final List<Entry> entries = new ArrayList<>();
        private long nextId = 1;
        /** 玩家最新使用的名字（元数据，由服务端自动维护，模型不写）。 */
        private String name = "";
    }
}
