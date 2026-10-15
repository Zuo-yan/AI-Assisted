package org.gwfx.aiassisted;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** AI 管理权限的统一入口；每次读取配置，让权限门槛热重载立即生效。 */
public final class AiPermissions {
    private AiPermissions() {}

    public static boolean allows(CommandSourceStack source) {
        return source != null && source.hasPermission(configuredLevel());
    }

    public static boolean allows(ServerPlayer player) {
        return player != null && allows(player.createCommandSourceStack());
    }

    public static int configuredLevel() {
        return Math.max(0, Math.min(4, Config.aiPermissionAdminLevel));
    }

    /** 从高到低检查实际命令权限，避免为指令执行提升玩家权限。 */
    public static int highestLevelFor(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        CommandSourceStack source = player.createCommandSourceStack();
        for (int level = 4; level >= 1; level--) {
            if (source.hasPermission(level)) {
                return level;
            }
        }
        return 0;
    }
}
