package org.gwfx.aiassisted.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.command.AiCommand;
import org.gwfx.aiassisted.core.config.AiConfig;

/**
 * AI 功能的 Forge 事件挂点（全部服务端侧，FORGE 游戏总线）。
 *
 * <p>1.20.1 与 26.3 的总线差异：{@code ModConfigEvent} 在 MOD 总线上，它的监听在
 * {@code Config.onLoad} 里（同一张总线上还做静态字段回填）；本类只挂游戏总线事件。
 *
 * <p>事件与职责：
 * <ul>
 *   <li>{@code RegisterCommandsEvent} → 注册 {@code /ai}，并顺带初始化运行时（把问题暴露在启动期）</li>
 *   <li>{@code TickEvent.ServerTickEvent}（END 阶段）→ 推进分页队列、处理挂起的配置热重载、
 *       推进建造/撤销作业</li>
 *   <li>{@code PlayerEvent.PlayerLoggedOutEvent} → 回收该玩家的会话与待发分页</li>
 *   <li>{@code ServerStoppingEvent} → 关闭 HTTP 线程池</li>
 *   <li>{@code ServerChatEvent} → <b>默认不拦截</b>；仅当配置开启前缀模式且消息命中前缀时才接管</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AiAssistedMod.MODID)
public final class AiServerEvents {

    private AiServerEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 主动初始化一次：配置错、路径不可写这类问题应该在开服时就暴露，
        // 而不是等玩家敲第一条 /ai chat 才发现
        AiRuntime.get();
        AiCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 1.20.1 的 tick 事件没有 Pre/Post 之分，只取 END 阶段（等价 26.3 的 ServerTickEvent.Post）
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!AiRuntime.isInitialized()) {
            return;
        }
        AiRuntime runtime = AiRuntime.get();
        runtime.tick();
        // 建造/撤销作业分 tick 推进（T001-6）：预算每 tick 从配置现读，改配置下一 tick 就生效
        runtime.builds().tick(event.getServer(), runtime.config().buildBlocksPerTick());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (AiRuntime.isInitialized() && event.getEntity() instanceof ServerPlayer player) {
            AiRuntime.get().onPlayerLeave(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (AiRuntime.isInitialized()) {
            AiRuntime.get().shutdown();
        }
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!AiRuntime.isInitialized()) {
            return;
        }
        AiConfig config = AiRuntime.get().config();

        // 默认关闭：全量拦截等于每说一句话都烧用户的 API 额度，还会把私人聊天发给第三方
        if (!config.chatPrefixEnabled()) {
            return;
        }
        String prefix = config.chatPrefix();
        String rawText = event.getRawText();
        if (prefix.isEmpty() || rawText == null || !rawText.startsWith(prefix)) {
            return;
        }

        // 命中前缀：这条不再广播给其他人，只当作给 AI 的输入（否则会既发出去又给 AI 回）
        event.setCanceled(true);
        AiRuntime.get().chatService().request(event.getPlayer(), rawText.substring(prefix.length()).strip());
    }
}
