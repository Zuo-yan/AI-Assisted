package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.chat.PendingCommandStore;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.gwfx.aiassisted.core.text.CommandText;

import java.util.Optional;

/**
 * 工具 {@code propose_command}：<b>提议</b>执行一条服务器指令（T002，危险级）。
 *
 * <p><b>它不执行任何东西</b>。名字里的 propose 是刻意的 —— 模型能做的只有"把这条指令
 * 摆到玩家面前"，真正执行要玩家在游戏内敲 {@code /ai confirm}。这样模型无法独自完成一次执行，
 * 提示词注入也拿不到"立刻做点什么"的能力。
 *
 * <p><b>三道门禁</b>（见任务文档 T002 的「安全与边界」）：
 * <ol>
 *   <li>注册阶段：达不到 {@code ai.tool.adminLevel} 的玩家根本拿不到这个工具（见 {@code AiTools}）；</li>
 *   <li>执行阶段：这里再查一次等级与 {@code ai.tool.dangerousEnabled} 开关；</li>
 *   <li>确认阶段：{@code /ai confirm} 时还会查第三次（提议之后可能被降权或关掉开关）。</li>
 * </ol>
 *
 * <p><b>提议阶段的预检</b>：用调用者<b>自己的权限集</b>先 parse 一遍。解析不过就当场告诉模型原因，
 * 免得玩家白确认一次 —— 也顺带保证了"AI 只能提议玩家本人有权执行的指令"这条边界。
 *
 * <p>必须在服务端主线程调用。
 */
public final class ProposeCommandTool {

    public static final String NAME = "propose_command";

    private ProposeCommandTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME,
                        "提议执行一条服务器指令（例如 list、time query daytime）。"
                                + "你无法直接执行它：调用本工具只会把指令提交给玩家，"
                                + "由他在游戏内用 /ai confirm 确认后才真正运行。"
                                + "指令必须是发起者本人权限范围内可执行的，且不要写前导斜杠。")
                .stringParam("command", "要提议的指令原文，不含前导斜杠，例如 list", true)
                .stringParam("reason", "为什么需要执行它。这句话会展示给玩家，帮助他判断要不要确认", false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config,
                              PendingCommandStore pending, JsonObject args) {
        if (!config.dangerousToolsEnabled()) {
            return ToolOutcome.error("本服务器未开启「AI 提议执行指令」（ai.tool.dangerousEnabled=false），"
                    + "你无法提议执行任何指令。");
        }
        int level = AiPermissions.highestLevelFor(player);
        if (level < config.toolAdminLevel()) {
            return ToolOutcome.error("发起者权限等级为 " + level + "，低于本功能要求的 "
                    + config.toolAdminLevel() + " 级，无法提议执行指令。");
        }

        Optional<String> rawCommand = ToolArgs.string(args, "command");
        if (rawCommand.isEmpty()) {
            return ToolOutcome.error("缺少 command 参数：请给出不含前导斜杠的指令原文。");
        }
        CommandText.Result normalized = CommandText.normalize(rawCommand.get());
        if (!normalized.ok()) {
            return ToolOutcome.error("指令不合法：" + normalized.error());
        }
        String command = normalized.command();
        String reason = CommandText.normalizeReason(ToolArgs.string(args, "reason").orElse(""));

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return ToolOutcome.error("服务端不可用，无法校验指令。");
        }

        // 预检：用调用者本人的权限集解析一遍。解析不过 = 未知指令或他没这个权限，两种情况都不该
        // 让他去确认一次。注意这里只 parse、不执行 —— parse 不会改变任何状态。
        //
        // 为什么 catch Exception 而不是某个具体类型：26.3 里 parse 的抛出类型不是可捕获的检查型
        // （catch CommandSyntaxException 会编译不过），而"未知指令"与"权限不足"在实现里也是
        // 同一个类型体系。统一兜住并把原因转述给模型，比按版本猜类型可靠。
        CommandSourceStack probe = player.createCommandSourceStack();
        try {
            server.getCommands().getDispatcher().parse(command, probe);
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "指令无法解析" : e.getMessage();
            return ToolOutcome.error("这条指令当前无法执行（未知指令，或发起者权限不足）：" + detail);
        }

        PendingCommandStore.Pending stored = pending.propose(player.getUUID(), command, reason,
                System.currentTimeMillis());
        notifyPlayer(player, stored);

        return ToolOutcome.ok("已提交待确认：/ " + command
                + "\n它**尚未执行**。玩家在游戏内敲 /ai confirm 才会真正运行（60 秒内有效）。"
                + "执行结果不会自动回传给你，也不会自动产生新的调用 —— "
                + "玩家下次提问时会在上下文里看到它。"
                + "\n如果玩家拒绝，请尊重他的决定，不要反复提议同一条指令。");
    }

    /**
     * 给玩家发确认提示。
     *
     * <p>刻意把<b>指令原文</b>摆出来：这是玩家唯一的判断依据，摘要或转述都可能被模型"美化"。
     */
    private static void notifyPlayer(ServerPlayer player, PendingCommandStore.Pending pending) {
        player.sendSystemMessage(Component.translatable("ai.ai_assisted.command.proposed",
                pending.command(),
                pending.reason().isEmpty() ? "(未给出理由)" : pending.reason(),
                PendingCommandStore.TTL_MILLIS / 1000L));
    }
}

