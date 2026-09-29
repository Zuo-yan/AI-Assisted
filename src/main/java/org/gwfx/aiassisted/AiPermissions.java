package org.gwfx.aiassisted;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionSet;
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
 * 固化成一个 {@code PermissionCheck}。缓存会让"改了配置得重启"重新变成一个坑，
 * 而"门槛可调"正是这项配置要解决的问题。这也是命令谓词写成方法引用而不是预先构造的原因。
 */
public final class AiPermissions {

    private AiPermissions() {
    }

    /** 命令 {@code requires} 谓词入口。控制台等非玩家来源同样有权限集合，走同一套判定。 */
    public static boolean allows(CommandSourceStack source) {
        return source != null && allows(source.permissions());
    }

    /** 玩家判定入口（配置界面下发 {@code canEdit}、更新包复核用）。 */
    public static boolean allows(ServerPlayer player) {
        return player != null && allows(player.permissions());
    }

    /**
     * @param permissions 来源的权限集合；{@code null} 一律视为无权限（宁可拒改，不可放行）
     */
    public static boolean allows(PermissionSet permissions) {
        if (permissions == null) {
            return false;
        }
        return permissionCheck().check(permissions);
    }

    /** 配置里的门槛值，越界时夹紧到 0~4（配置未加载时读静态默认值，不会出现 0 导致误放行）。 */
    public static int configuredLevel() {
        return Math.max(0, Math.min(4, Config.aiPermissionAdminLevel));
    }

    /**
     * 该权限集<b>实际达到</b>的最高等级（0~4）。
     *
     * <p>为什么不直接读某个 {@code level()} 字段：{@code PermissionSet} 不保证是等级制实现
     * （第三方权限插件给的可能是原子权限集），所以这里从高到低逐个用原版等级常量探测，
     * 取第一个通过的。
     *
     * <p>全都不通过就返回 0 —— 保守：宁可当普通玩家，也不要把"读不出来"当成"是管理员"。
     * 判不准的后果是"模型认为你没权限"，玩家还能问一句为什么，而不是被静默提权。
     */
    public static int highestLevelFor(PermissionSet permissions) {
        if (permissions == null) {
            return 0;
        }
        for (int level = 4; level >= 1; level--) {
            if (checkFor(level).check(permissions)) {
                return level;
            }
        }
        return 0;
    }

    /**
     * 等级值 → 26.3 的权限检查对象。
     *
     * <p>刻意用 {@code Commands.LEVEL_*} 常量而不是自己 new：这组常量是原版权限等级的定义处，
     * 自己拼 {@code new Permission.HasCommandLevel(...)} 等于把"等级=多少"抄了第二遍。
     */
    private static PermissionCheck checkFor(int level) {
        return switch (level) {
            case 1 -> Commands.LEVEL_MODERATORS;
            case 2 -> Commands.LEVEL_GAMEMASTERS;
            case 3 -> Commands.LEVEL_ADMINS;
            case 4 -> Commands.LEVEL_OWNERS;
            // 0：不设门槛。想"谁都别改"请直接关 ai.enabled，而不是把门槛设成 0 之外的怪值
            default -> Commands.LEVEL_ALL;
        };
    }

    private static PermissionCheck permissionCheck() {
        return checkFor(configuredLevel());
    }
}
