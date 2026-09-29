package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 工具 {@code server_info}：全服层面的只读信息（T002，管理员级）。
 *
 * <p>补的缺口：既有的 6 个工具与上下文全都只看得到<b>发起者周围</b>，
 * 所以「服务器在线几个人」「谁在线」「TPS 多少」这类问题原本无从回答 ——
 * 模型只能承认自己看不到，这不是 bug 而是当时的能力边界。
 *
 * <p><b>门禁</b>：调用者等级 ≥ {@code ai.tool.adminLevel}（默认 2=OP）。达不到的玩家
 * <b>根本不会注册</b>这个工具（见 {@code AiTools.registryFor}），这里再查一次只是第二道保险。
 *
 * <p><b>隐私（必须如实告知）</b>：本工具会返回<b>其他玩家的名字与坐标</b>，而这些内容会随请求
 * 发给你配置的第三方 API。这是本模组<b>唯一</b>会外发其他玩家信息的地方（上下文采集至今
 * 只给"玩家 ×N"这种聚合）。所以：工具 description 里写明、配置注释里写明、README/文档里写明。
 *
 * <p>只读且幂等（不写任何状态），符合 {@code ToolInvoker} 的契约。
 */
public final class ServerInfoTool {

    public static final String NAME = "server_info";

    /** "在线管理员"的判定下限：2 = OP。与工具门槛无关，是"谁能算管理员"的固定口径。 */
    private static final int ADMIN_LEVEL = 2;

    private ServerInfoTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME,
                        "读取全服层面的只读信息：在线玩家数、在线玩家名单（名字、维度、坐标）、"
                                + "在线管理员、TPS/MSPT、服务器运行时长、白名单开关与世界种子。"
                                + "无参数。注意：返回内容包含其他玩家的名字与坐标，会发送给配置的第三方 API。")
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config, JsonObject args) {
        int level = AiPermissions.highestLevelFor(player.permissions());
        if (level < config.toolAdminLevel()) {
            return ToolOutcome.error("发起者权限等级为 " + level + "，低于本功能要求的 "
                    + config.toolAdminLevel() + " 级，无权读取全服信息。");
        }

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return ToolOutcome.error("服务端不可用，无法读取全服信息。");
        }

        int limit = Math.max(1, config.toolMaxResults());
        StringBuilder out = new StringBuilder(512);

        List<ServerPlayer> online = new ArrayList<>(server.getPlayerList().getPlayers());
        // 按名字排序：同一份状态每次给模型的顺序要一致，否则模型会以为"顺序变了 = 有人进出"
        online.sort(Comparator.comparing(onlinePlayer -> onlinePlayer.getGameProfile().name()));

        out.append("服务器现状（实时）：\n");
        out.append("- 在线玩家: ").append(online.size())
                .append(" / ").append(server.getPlayerList().getMaxPlayers()).append('\n');
        appendPlayerList(out, online, limit);
        appendOnlineAdmins(out, online, limit);
        out.append("- 性能: ").append(describePerformance(server)).append('\n');
        out.append("- 已运行: ").append(describeUptime(server)).append('\n');
        out.append("- 白名单: ").append(server.isUsingWhitelist() ? "已启用" : "未启用").append('\n');
        out.append("- 世界种子: ").append(server.overworld().getSeed()).append('\n');

        return ToolOutcome.ok(out.toString());
    }

    private static void appendPlayerList(StringBuilder out, List<ServerPlayer> online, int limit) {
        if (online.isEmpty()) {
            out.append("- 在线名单: 无\n");
            return;
        }
        out.append("- 在线名单（名字 / 维度 / 坐标）:\n");
        int shown = Math.min(online.size(), limit);
        for (int i = 0; i < shown; i++) {
            ServerPlayer onlinePlayer = online.get(i);
            out.append("  · ").append(onlinePlayer.getGameProfile().name())
                    .append(" / ").append(onlinePlayer.level().dimension().identifier())
                    .append(' ').append(onlinePlayer.blockPosition().getX())
                    .append(' ').append(onlinePlayer.blockPosition().getY())
                    .append(' ').append(onlinePlayer.blockPosition().getZ())
                    .append('\n');
        }
        if (online.size() > shown) {
            // 如实说明"这是截断的名单"，否则模型会把"只有 10 人"当成事实
            out.append("  （另有 ").append(online.size() - shown).append(" 人未列出，已达单次上限）\n");
        }
    }

    private static void appendOnlineAdmins(StringBuilder out, List<ServerPlayer> online, int limit) {
        List<String> admins = new ArrayList<>();
        for (ServerPlayer onlinePlayer : online) {
            int level = AiPermissions.highestLevelFor(onlinePlayer.permissions());
            if (level >= ADMIN_LEVEL) {
                admins.add(onlinePlayer.getGameProfile().name() + "（" + level + " 级）");
            }
        }
        if (admins.isEmpty()) {
            out.append("- 在线管理员: 无\n");
            return;
        }
        int shown = Math.min(admins.size(), limit);
        String text = String.join("、", admins.subList(0, shown));
        out.append("- 在线管理员: ").append(text);
        if (admins.size() > shown) {
            out.append("，另有 ").append(admins.size() - shown).append(" 人未列出");
        }
        out.append('\n');
    }

    /** MSPT 与由其换算的 TPS。用 P95 之类的统计没有意义 —— 这里要的是"现在卡不卡"。 */
    private static String describePerformance(MinecraftServer server) {
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = mspt <= 50.0D ? 20.0D : Math.min(20.0D, 1000.0D / mspt);
        return String.format(Locale.ROOT, "平均 %.1f ms/tick，约 %.1f TPS", mspt, tps);
    }

    /**
     * 服务器已运行多久。
     *
     * <p>用 {@code getTickCount()}（跑了多少刻）而不是墙钟：26.3 里没有公开的"启动时刻"方法
     * （{@code getStartTimeNano} 在内部的 TimeProfiler 上，不是 MinecraftServer 的 API）。
     * 正常 TPS 下 20 刻 = 1 秒，掉刻时会比墙钟偏小 —— 这也算如实反映"服务端实际推进了多少"。
     */
    private static String describeUptime(MinecraftServer server) {
        long seconds = Math.max(0L, server.getTickCount() / 20L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        return hours > 0 ? hours + " 小时 " + minutes + " 分" : minutes + " 分钟";
    }
}
