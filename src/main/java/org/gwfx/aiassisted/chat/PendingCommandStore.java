package org.gwfx.aiassisted.chat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认执行的危险级指令（T002）。
 *
 * <p><b>它存在的唯一理由</b>：把「模型想执行」与「真正执行」拆成两步。模型调用
 * {@code propose_command} 只会在<em>这里</em>留一条记录，真正执行要玩家在游戏内敲 {@code /ai confirm}。
 * 这样模型<b>无法独自完成一次执行</b>，提示词注入也拿不到"立刻做点什么"的能力。
 *
 * <p><b>为什么每玩家只留一条、且带 TTL</b>：
 * <ul>
 *   <li>只留一条：模型连点也不会攒出十几条等着玩家误确认；</li>
 *   <li>60 秒过期：待确认项放久了，玩家很可能忘了当时的上下文（"这条 /give 是谁要的？"），
 *       过期的默认结果是<b>不执行</b>，这是正确的默认。</li>
 * </ul>
 *
 * <p>与 {@link GoalStore} 立场一致：仅内存，服务端重启即清空（持久化属于 T001-8，
 * 而"待确认"这种状态本来也不该跨重启）。
 */
public final class PendingCommandStore {

    /** 待确认指令的有效期（毫秒）。常量而非配置：它不是需要运维调的参数，而是一道"别放太久"的闸。 */
    public static final long TTL_MILLIS = 60_000L;

    private final Map<UUID, Pending> pendingByPlayer = new ConcurrentHashMap<>();

    /**
     * 一条待确认指令。
     *
     * @param command     归一化后的指令（不含前导 {@code /}）
     * @param reason      模型给出的理由（给玩家看，帮助他判断要不要确认）
     * @param proposedAtMillis 提出时刻（毫秒），用于 TTL 判定与提示里的"x 秒前"
     */
    public record Pending(String command, String reason, long proposedAtMillis) {
    }

    /**
     * 记下一条待确认指令，覆盖该玩家原有的那条。
     *
     * @return 实际存下的记录
     */
    public Pending propose(UUID playerId, String command, String reason, long nowMillis) {
        Pending record = new Pending(command, reason == null ? "" : reason, nowMillis);
        if (playerId != null) {
            this.pendingByPlayer.put(playerId, record);
        }
        return record;
    }

    /** 查看未过期的待确认指令（不移除）。 */
    public Optional<Pending> peek(UUID playerId, long nowMillis) {
        Pending record = playerId == null ? null : this.pendingByPlayer.get(playerId);
        if (record == null) {
            return Optional.empty();
        }
        if (isExpired(record, nowMillis)) {
            this.pendingByPlayer.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /**
     * 取走待确认指令（取到即移除，一次确认只能执行一次）。
     *
     * <p>过期的在这里一并清掉，返回空 —— "已过期"与"根本没有"对玩家是同一件事：
     * 都不该执行，都该说一句"没有待确认的指令"。
     */
    public Optional<Pending> take(UUID playerId, long nowMillis) {
        Optional<Pending> found = peek(playerId, nowMillis);
        found.ifPresent(record -> this.pendingByPlayer.remove(playerId));
        return found;
    }

    /** 玩家退出时清理，避免长时间开服后 Map 里堆满离线玩家。 */
    public void clear(UUID playerId) {
        if (playerId != null) {
            this.pendingByPlayer.remove(playerId);
        }
    }

    private static boolean isExpired(Pending record, long nowMillis) {
        return nowMillis - record.proposedAtMillis() >= TTL_MILLIS;
    }
}
