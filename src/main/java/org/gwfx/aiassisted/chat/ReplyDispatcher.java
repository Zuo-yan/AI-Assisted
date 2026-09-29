package org.gwfx.aiassisted.chat;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.net.AiChatReplyPacket;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * tick 驱动的分页发送队列。
 *
 * <p>为什么需要它：AI 回复一次可能十几页，必须做两件事 ——
 * <ol>
 *   <li><b>分页</b>：单条 MC 消息有长度上限；</li>
 *   <li><b>限流</b>：一口气发十几条会被判定为刷屏（甚至被其他聊天类模组当垃圾包拦掉）。</li>
 * </ol>
 *
 * <p>由 {@code AiServerEvents} 在 {@code ServerTickEvent.Post} 里调用 {@link #drain()} 推进，
 * 因此发包<b>一定发生在服务端主线程</b>，不需要网络回调自己去碰世界或连接。
 */
public final class ReplyDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record Pending(ServerPlayer player, Component text, long dueTick) {
    }

    private final Deque<Pending> queue = new ArrayDeque<>();

    /** 由 {@link #drain()} 自增的逻辑时钟（单位：次 drain，即服务端 tick）。 */
    private long tick;

    /**
     * 排队一批分页。
     *
     * @param intervalTicks 页与页之间的间隔；第一页会在下一次 {@link #drain()} 立即发出
     */
    public void enqueue(ServerPlayer player, List<Component> pages, int intervalTicks) {
        if (player == null || pages == null || pages.isEmpty()) {
            return;
        }
        int gap = Math.max(1, intervalTicks);
        synchronized (this.queue) {
            long due = this.tick;
            for (Component page : pages) {
                this.queue.addLast(new Pending(player, page, due));
                due += gap;
            }
        }
    }

    /** 在服务端 tick 里调用：发出所有到期的页。 */
    public void drain() {
        synchronized (this.queue) {
            this.tick++;
            while (!this.queue.isEmpty()) {
                Pending head = this.queue.peekFirst();
                if (head.dueTick() > this.tick) {
                    break;
                }
                this.queue.pollFirst();
                deliver(head);
            }
        }
    }

    /** 玩家退出时丢掉他还没发完的页。 */
    public void clearFor(UUID playerId) {
        synchronized (this.queue) {
            this.queue.removeIf(pending -> pending.player().getUUID().equals(playerId));
        }
    }

    /** 服务端停机时清空。 */
    public void clear() {
        synchronized (this.queue) {
            this.queue.clear();
        }
    }

    private static void deliver(Pending pending) {
        ServerPlayer player = pending.player();
        // 分页是异步的：这段时间里玩家完全可能已经退出或死亡重生
        if (player.isRemoved() || player.hasDisconnected()) {
            return;
        }
        try {
            AiChatReplyPacket.send(player, pending.text());
        } catch (RuntimeException e) {
            // 掉线竞态下发包可能抛异常；这里必须兜住，否则一个玩家断线会打断整个服务端 tick
            LOGGER.debug("[AI] 分页下发失败，已跳过该页", e);
        }
    }
}
