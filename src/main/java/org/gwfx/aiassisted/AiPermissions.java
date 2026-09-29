package org.gwfx.aiassisted;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.Config;

/**
 * AI 管理权限的<b>唯一判定入口</b>：谁能改 AI 配置、谁能用 {@code /ai} 的管理类子命令。
 *
 * <p><b>为什么单独一个类</b>：判定点有三个（命令的 {@code requires} 谓词、配置快照下发时的
 * {@code canEdit}、更新包处理时的复核），而这三处必须永远一致 —— 只有一份实现才谈得上一致。
 * 历史教训就在本仓库：原来命令用 {@code Permissions.COMMANDS_GAMEMASTER}、网络包又各自写一遍，
 * 一旦门槛要可配置，就会漏改其中一处，出现"命令拦住了、包还放行"的洞。
 *
 * <p><b>门槛每次判定现读</b>（{@link Config#aiPermissionAdminLevel}）：不缓存、不在类初始化时
 * 固化成一个等级值。缓存会让"改了配置得重启"重新变成一个坑，
 * 而"门槛可调"正是这项配置要解决的问题。这也是命令谓词写成方法引用而不是预先构造的原因。
 *
 * <p><b>1.20.1 的权限模型</b>：没有 26.3 的 {@code PermissionSet/PermissionCheck} 体系，
 * 只有等级制的 {@code hasPermission(int)}（0=所有人、1=版主、2=OP、3=管理员、4=服务器所有者），
 * 与配置项的语义一一对应，逐级探测即可。
 */
public final class AiPermissions {

    private AiPermissions() {
    }

    /** 命令 {@code requires} 谓词入口。控制台等非玩家来源同样按等级判定。 */
    public static boolean allows(CommandSourceStack source) {
        return source != null && source.hasPermission(configuredLevel());
    }

    /** 玩家判定入口（配置界面下发 {@code canEdit}、更新包复核用）。 */
    public static boolean allows(ServerPlayer player) {
        return player != null && hasLevel(player, configuredLevel());
    }

    /** 配置里的门槛值，越界时夹紧到 0~4（配置未加载时读静态默认值，不会出现 0 导致误放行）。 */
    public static int configuredLevel() {
        return Math.max(0, Math.min(4, Config.aiPermissionAdminLevel));
    }

    /**
     * 该玩家<b>实际达到</b>的最高等级（0~4）。
     *
     * <p>从高到低逐级探测，取第一个通过的；全都不通过就返回 0 —— 保守：
     * 宁可当普通玩家，也不要把"读不出来"当成"是管理员"。
     * 判不准的后果是"模型认为你没权限"，玩家还能问一句为什么，而不是被静默提权。
     */
    public static int highestLevelFor(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        for (int level = 4; level >= 1; level--) {
            if (hasLevel(player, level)) {
                return level;
            }
        }
        return 0;
    }

    /** 等级判定：{@code hasPermissions(n)} 表示玩家等级 ≥ n。 */
    private static boolean hasLevel(ServerPlayer player, int level) {
        return player.hasPermissions(level);
    }
}
