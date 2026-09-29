package org.gwfx.aiassisted.build;

import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.BuildPlan;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认的建造方案（T001-6）。
 *
 * <p>模型调用 {@code propose_build} 后<b>不会写任何方块</b>，只在这里留一份方案；
 * 真正落地要玩家在游戏内敲 {@code /ai confirm}。与 {@code PendingCommandStore} 同构
 * （每玩家一条 / take 即消费），但 TTL 取 <b>120 秒</b>：建造比一条指令更需要时间 ——
 * 玩家得把那张分层字符画看完才敢确认。
 *
 * <p><b>与待确认指令互斥</b>：{@code ProposeBuildTool} 提议时会清掉待确认指令，反之亦然。
 * 否则 {@code /ai confirm} 可能执行到"不是玩家刚刚看过的那件事"。
 */
public final class PendingBuildStore {

    /** 待确认建造的有效期（毫秒）。 */
    public static final long TTL_MILLIS = 120_000L;

    /**
     * 一份待确认的建造。
     *
     * @param plan          展开后的施工计划（相对坐标）
     * @param placement     已经算好的落点与朝向
     * @param proposedAtMillis 提出时刻，用于 TTL
     */
    public record Pending(BuildPlan plan, BuildPlacement placement, long proposedAtMillis) {

        public String name() {
            return this.plan == null || this.plan.name().isEmpty() ? "未命名建筑" : this.plan.name();
        }
    }

    private final Map<UUID, Pending> pendings = new ConcurrentHashMap<>();

    /** 记下一份待确认建造，覆盖该玩家原有的那条。 */
    public Pending propose(UUID playerId, BuildPlan plan, BuildPlacement placement, long nowMillis) {
        Pending pending = new Pending(plan, placement, nowMillis);
        if (playerId != null) {
            this.pendings.put(playerId, pending);
        }
        return pending;
    }

    /** 查看未过期的待确认建造（不移除）。 */
    public Optional<Pending> peek(UUID playerId, long nowMillis) {
        Pending pending = playerId == null ? null : this.pendings.get(playerId);
        if (pending == null) {
            return Optional.empty();
        }
        if (isExpired(pending, nowMillis)) {
            this.pendings.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /** 取走待确认建造（取到即移除：一次确认只建造一次）。 */
    public Optional<Pending> take(UUID playerId, long nowMillis) {
        Optional<Pending> found = peek(playerId, nowMillis);
        found.ifPresent(pending -> this.pendings.remove(playerId));
        return found;
    }

    public void clear(UUID playerId) {
        if (playerId != null) {
            this.pendings.remove(playerId);
        }
    }

    private static boolean isExpired(Pending pending, long nowMillis) {
        return nowMillis - pending.proposedAtMillis() >= TTL_MILLIS;
    }
}
