package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.gwfx.aiassisted.core.memory.MemoryStore;

import java.util.List;

/**
 * 长期记忆三件套（T001-8）：{@code memory_write} / {@code memory_search} / {@code memory_forget}。
 *
 * <p><b>为什么三个工具合在一个类</b>：它们共享同一份 {@link MemoryStore}、同一套语义
 * （「一条记忆 = 一个自包含的事实」）与同一种结果措辞，拆成三个文件只会多三份样板。
 * 与其它工具一样是无状态静态工具类，玩家与配置在注册时由闭包捕获。
 *
 * <p><b>权限立场</b>：这三个工具只读写<b>发起对话玩家自己的</b>记忆文件，不碰世界、不碰指令、
 * 不影响他人 —— 因此不设等级门槛（全员可用），也没有危险级开关。风险上限是「玩家污染自己的
 * 记忆」，由条数上限与入库截断兜底。
 *
 * <p>必须在服务端主线程调用（记忆文件的首读与落盘都在这里发生）。
 */
public final class MemoryTools {

    public static final String NAME_WRITE = "memory_write";
    public static final String NAME_SEARCH = "memory_search";
    public static final String NAME_FORGET = "memory_forget";

    private MemoryTools() {
    }

    public static ToolSpec writeSpec() {
        return ToolSpec.builder(NAME_WRITE, "把需要长期记住的信息写进你与该玩家共享的长期记忆。"
                        + "只写持久的事实与偏好（喜欢的建筑风格、重要约定、长期项目背景），"
                        + "一条只写一个事实且内容自包含；不要写临时状态（坐标/时间/当轮任务）。")
                .stringParam("text", "要记住的内容，一句话、自包含（例如：玩家喜欢中世纪风格的建筑）", true)
                .build();
    }

    public static ToolSpec searchSpec() {
        return ToolSpec.builder(NAME_SEARCH, "在长期记忆里查找。query 为空时列出最近的记忆。"
                        + "回答前不确定自己是否记得某件事时先查这里。")
                .stringParam("query", "关键词（子串匹配）；留空或省略则按时间倒序列出最近的记忆", false)
                .build();
    }

    public static ToolSpec forgetSpec() {
        return ToolSpec.builder(NAME_FORGET, "按编号删除一条过时或错误的记忆。"
                        + "编号来自 memory_search / memory_write 的返回结果。")
                .stringParam("id", "要删除的记忆编号（如 \"7\"）", true)
                .build();
    }

    public static ToolOutcome invokeWrite(ServerPlayer player, MemoryStore memories, AiConfig config, JsonObject args) {
        if (!config.memoryEnabled()) {
            return ToolOutcome.error("长期记忆功能未开启（ai.memory.enabled）。");
        }
        String text = ToolArgs.string(args, "text").orElse("");
        if (text.isEmpty()) {
            return ToolOutcome.error("text 不能为空：要写一条自包含的事实，例如「玩家喜欢中世纪风格」。");
        }
        MemoryStore.AddResult result = memories.write(player.getUUID(), text, config);
        if (result == null) {
            return ToolOutcome.error("写入失败：内容无效。");
        }
        StringBuilder message = new StringBuilder("已记住（#" + result.entry().id() + "）。");
        if (result.evicted() != null) {
            message.append("注意：记忆已达上限，最旧的 #").append(result.evicted().id())
                    .append("「").append(result.evicted().text()).append("」已被移出。");
        }
        return ToolOutcome.ok(message.toString());
    }

    public static ToolOutcome invokeSearch(ServerPlayer player, MemoryStore memories, AiConfig config, JsonObject args) {
        if (!config.memoryEnabled()) {
            return ToolOutcome.error("长期记忆功能未开启（ai.memory.enabled）。");
        }
        String query = ToolArgs.string(args, "query").orElse("");
        int limit = Math.max(1, config.toolMaxResults());
        List<MemoryStore.Entry> matches = memories.search(player.getUUID(), query, limit);
        if (matches.isEmpty()) {
            return ToolOutcome.ok(query.isBlank()
                    ? "还没有任何长期记忆。"
                    : "长期记忆里没有匹配「" + query + "」的内容。");
        }
        StringBuilder out = new StringBuilder((matches.size() + 1) * 32);
        out.append(query.isBlank() ? "最近的长期记忆（新→旧）：" : "匹配到 ").append(matches.size()).append(" 条：");
        if (matches.size() >= limit) {
            out.append("（已达单次返回上限，可能不完整）");
        }
        out.append('\n');
        for (MemoryStore.Entry entry : matches) {
            out.append("- [#").append(entry.id()).append("] ").append(entry.text()).append('\n');
        }
        return ToolOutcome.ok(out.toString());
    }

    public static ToolOutcome invokeForget(ServerPlayer player, MemoryStore memories, AiConfig config, JsonObject args) {
        if (!config.memoryEnabled()) {
            return ToolOutcome.error("长期记忆功能未开启（ai.memory.enabled）。");
        }
        String rawId = ToolArgs.string(args, "id").orElse("");
        long id;
        try {
            id = Long.parseLong(rawId.replace("#", "").strip());
        } catch (NumberFormatException e) {
            return ToolOutcome.error("id 必须是记忆编号（如 \"7\"），可先用 memory_search 查到编号。");
        }
        boolean removed = memories.forget(player.getUUID(), id);
        return removed
                ? ToolOutcome.ok("已遗忘 #" + id + "。")
                : ToolOutcome.error("#" + id + " 不存在或已被删除（可先用 memory_search 确认编号）。");
    }
}
