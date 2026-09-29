package org.gwfx.aiassisted.core.build;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个玩家最近一次建造的撤销记录（零 MC 依赖，可单测）。
 *
 * <p><b>为什么只留最近一次</b>：多级撤销需要把每一层的方块历史都留在内存里
 * （4096 块 × 若干场），而"建错了想撤回"这个真实需求几乎总是针对刚刚那一座。
 * 多级的收益远小于它的内存与语义复杂度，所以刻意不做（持久化更是 T001-8 的事）。
 *
 * <p><b>为什么要 TTL</b>：撤销记录放着不用，只会占内存；而过了 10 分钟，玩家多半已经
 * 默认那座建筑就长这样了，这时突然撤回反而更意外。到期即丢，且"丢"是静默的
 * （玩家敲 {@code /ai undo} 时会得到"没有可撤销的建造"）。
 */
public final class UndoStore<S> {

    /** 撤销记录的有效期（毫秒）。 */
    public static final long TTL_MILLIS = 600_000L;

    /** 一条记录：撤销日志 + 建筑名（回执里用）+ 落盘时刻。 */
    public record Record<S>(UndoLog<S> log, String name, long atMillis) {
    }

    private final Map<UUID, Record<S>> records = new ConcurrentHashMap<>();

    /** 记下一次建造（覆盖该玩家上一条 —— 见类注释）。 */
    public void put(UUID playerId, UndoLog<S> log, String name, long nowMillis) {
        if (playerId == null || log == null || log.isEmpty()) {
            return;
        }
        this.records.put(playerId, new Record<>(log, name == null ? "" : name, nowMillis));
    }

    /** 查看未过期的记录（不移除）。 */
    public Optional<Record<S>> peek(UUID playerId, long nowMillis) {
        Record<S> record = playerId == null ? null : this.records.get(playerId);
        if (record == null) {
            return Optional.empty();
        }
        if (isExpired(record, nowMillis)) {
            this.records.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /** 取走记录（取到即移除：一次撤销只做一次）。 */
    public Optional<Record<S>> take(UUID playerId, long nowMillis) {
        Optional<Record<S>> found = peek(playerId, nowMillis);
        found.ifPresent(record -> this.records.remove(playerId));
        return found;
    }

    /** 玩家退出时清理。 */
    public void clear(UUID playerId) {
        if (playerId != null) {
            this.records.remove(playerId);
        }
    }

    /**
     * 清掉已过期的记录。
     *
     * <p>为什么需要显式扫一遍：TTL 是在 {@link #peek} 里惰性判断的，而"建造完就再也没回来"
     * 的玩家永远不会触发 peek —— 每玩家一条 4096 个方块的旧状态并不小，不扫就是纯泄漏。
     * 由服务端 tick 定期调用即可。
     *
     * @return 清掉了几条
     */
    public int sweep(long nowMillis) {
        int before = this.records.size();
        this.records.entrySet().removeIf(entry -> isExpired(entry.getValue(), nowMillis));
        return before - this.records.size();
    }

    /** 当前记录条数（自检/测试用）。 */
    public int size() {
        return this.records.size();
    }

    private static boolean isExpired(Record<?> record, long nowMillis) {
        return nowMillis - record.atMillis() >= TTL_MILLIS;
    }
}
