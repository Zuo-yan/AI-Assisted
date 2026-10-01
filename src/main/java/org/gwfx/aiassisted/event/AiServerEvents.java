package org.gwfx.aiassisted.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.command.AiCommand;
import org.gwfx.aiassisted.core.config.AiConfig;

/**
 * AI 功能的 NeoForge 事件挂点（全部服务端侧）。
 *
 * <p>刻意不改动 {@code AiAssistedMod} 主类：新功能通过 {@code @EventBusSubscriber} 自注册，
 * 降低对既有代码的侵入，也让 AI 模块出问题时更容易整体摘除。
 *
 * <p>事件与职责：
 * <ul>
 *   <li>{@code RegisterCommandsEvent} → 注册 {@code /ai}，并顺带初始化运行时（把问题暴露在启动期）</li>
 *   <li>{@code ServerStartedEvent} → 重建 HTTP 传输层（单人模式退出世界会关掉它，重进必须救活）</li>
 *   <li>{@code ServerTickEvent.Post} → 推进分页队列、处理挂起的配置热重载、
 *       推进建造/撤销作业</li>
 *   <li>{@code PlayerEvent.PlayerLoggedInEvent} → 进服问候（守卫不满足则静默跳过）</li>
 *   <li>{@code PlayerEvent.PlayerLoggedOutEvent} → 回收该玩家的会话与待发分页</li>
 *   <li>{@code ModConfigEvent} → 请求热重载（延迟到下一 tick，避开与 Config.onLoad 的顺序竞态）</li>
 *   <li>{@code ServerStoppingEvent} → 关闭 HTTP 线程池</li>
 *   <li>{@code ServerChatEvent} → <b>默认不拦截</b>；仅当配置开启前缀模式且消息命中前缀时才接管</li>
 * </ul>
 */
@EventBusSubscriber(modid = AiAssistedMod.MODID)
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
    public static void onServerTick(ServerTickEvent.Post event) {
        if (AiRuntime.isInitialized()) {
            AiRuntime runtime = AiRuntime.get();
            runtime.tick();
            // 建造/撤销作业分 tick 推进（T001-6）：预算每 tick 从配置现读，改配置下一 tick 就生效
            runtime.builds().tick(event.getServer(), runtime.config().buildBlocksPerTick());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 单人模式的生命周期陷阱：退出世界会触发 ServerStoppingEvent → shutdown() 关闭
        // HTTP 线程池，而 AiRuntime 是跨世界存活的单例 —— 不在这里重建的话，
        // 重进世界后所有 AI 调用都会撞上 RejectedExecutionException（"Task …AsyncSupply rejected"）
        if (AiRuntime.isInitialized()) {
            AiRuntime.get().reload();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (AiRuntime.isInitialized() && event.getEntity() instanceof ServerPlayer player) {
            // 主动问候：内部自带开关/冷却/并发守卫，不满足就静默跳过
            AiRuntime.get().greetOnJoin(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (AiRuntime.isInitialized() && event.getEntity() instanceof ServerPlayer player) {
            AiRuntime.get().onPlayerLeave(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent event) {
        // 加载期不创建实例（那时不该建线程池）；已初始化时只置一个标记，下一 tick 再重建
        if (AiRuntime.isInitialized()) {
            AiRuntime.get().requestReload();
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
