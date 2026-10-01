package org.gwfx.aiassisted.tool;

import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.build.PendingBuildStore;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.agent.ToolRegistry;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.memory.MemoryStore;

/**
 * 工具注册入口。
 *
 * <p><b>为什么每次请求现建注册表</b>：工具要读的是「发起命令的那个玩家」周围的世界，
 * 而 {@code ToolInvoker#invoke} 的签名只接收参数、不接收上下文。因此玩家与配置必须
 * 在注册时就被闭包捕获 —— 一个进程级的全局注册表无法表达这一点（多玩家会串）。
 * 注册本身只是几个对象分配，开销可以忽略。
 *
 * <p><b>为什么配方索引反而要传进来</b>：它是唯一一个「跨请求复用才有意义」的状态
 * （全量枚举配方构建反查索引）。所以它由 {@code AiRuntime} 持有、像配置一样传下来，
 * 不能跟着注册表一起每次重建。
 *
 * <p>⚠️ <b>工具声明会进入每一次请求体</b>，也就是说工具越多、每次调用的输入 token 越多。
 * 因此每个工具的 description 与参数说明都要克制，只写模型判断「什么时候该用它」所需的信息。
 *
 * <p>必须在服务端主线程调用（工具构造时就会捕获玩家，且工具执行要读世界）。
 */
public final class AiTools {

    private AiTools() {
    }

    /** 构建绑定到指定玩家、配置与配方索引的工具注册表。 */
    public static ToolRegistry registryFor(ServerPlayer player, AiConfig config,
                                           RecipeIndex recipeIndex,
                                           PendingCommandStore pendingCommands,
                                           PendingBuildStore pendingBuilds,
                                           MemoryStore memories) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(SearchBlocksTool.spec(), args -> SearchBlocksTool.invoke(player, config, args));
        registry.register(SearchAvailableBlocksTool.spec(), SearchAvailableBlocksTool::invoke);
        registry.register(SearchEntitiesTool.spec(), args -> SearchEntitiesTool.invoke(player, config, args));
        registry.register(SearchRecipesTool.spec(),
                args -> SearchRecipesTool.invoke(player.level(), recipeIndex, config, args));
        registry.register(PlayerStatusTool.spec(), args -> PlayerStatusTool.invoke(player, config, args));
        registry.register(SearchInventoryTool.spec(), args -> SearchInventoryTool.invoke(player, config, args));
        registry.register(SearchContainersTool.spec(), args -> SearchContainersTool.invoke(player, config, args));

        // ===== 长期记忆三件套（T001-8）=====
        // 只读写发起对话玩家自己的记忆文件，不碰世界与指令 —— 不设等级门槛，全员可用。
        // 总开关关闭时整组不注册：模型看不到工具名，也就不会承诺「我会记住」。
        if (config.memoryEnabled()) {
            registry.register(MemoryTools.writeSpec(),
                    args -> MemoryTools.invokeWrite(player, memories, config, args));
            registry.register(MemoryTools.searchSpec(),
                    args -> MemoryTools.invokeSearch(player, memories, config, args));
            registry.register(MemoryTools.forgetSpec(),
                    args -> MemoryTools.invokeForget(player, memories, config, args));
        }

        // ===== 管理员级工具（T002）：达不到门槛就**不注册** =====
        // 为什么不是"注册了再在实现里拒绝"：不注册意味着模型连工具名都看不到，
        // 它不会去尝试一个不存在的工具，也就不会在回答里承诺自己做不到的事。
        // （实现里仍各有一次等级复查，那是第二道保险，不是唯一门禁。）
        if (AiPermissions.highestLevelFor(player.permissions()) >= config.toolAdminLevel()) {
            registry.register(ServerInfoTool.spec(), args -> ServerInfoTool.invoke(player, config, args));
            // propose_command 是危险级：除等级外还要总开关打开才注册（默认关闭）
            if (config.dangerousToolsEnabled()) {
                registry.register(ProposeCommandTool.spec(),
                        args -> ProposeCommandTool.invoke(player, config, pendingCommands, args));
            }
            // propose_build 同理，但它有自己的开关（建造比"任意指令"窄，可单独放行）
            if (config.buildEnabled()) {
                registry.register(ProposeBuildTool.spec(),
                        args -> ProposeBuildTool.invoke(player, config, pendingBuilds, pendingCommands, args));
                registry.register(ProposeClearTool.spec(),
                        args -> ProposeClearTool.invoke(player, config, pendingBuilds, pendingCommands, args));
                registry.register(ProposeDismantleTool.spec(),
                        args -> ProposeDismantleTool.invoke(player, config, pendingBuilds, pendingCommands, args));
                registry.register(ProposeDecorateTool.spec(),
                        args -> ProposeDecorateTool.invoke(player, config, pendingBuilds, pendingCommands, args));
            }
        }
        return registry;
    }
}
