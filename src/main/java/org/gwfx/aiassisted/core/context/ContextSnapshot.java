package org.gwfx.aiassisted.core.context;

import java.util.List;

/**
 * 感知快照：本轮对话可供模型参考的「游戏事实」（零 MC 依赖）。
 */
public record ContextSnapshot(
        String dimensionId,
        int callerPermissionLevel,
        int playerX,
        int playerY,
        int playerZ,
        long gameTime,
        boolean raining,
        boolean thundering,
        List<NearbyEntity> nearbyEntities,
        List<InventoryEntry> inventory,
        List<NearbyContainer> nearbyContainers,
        NearbyStructure nearestVillage,
        VillageStatus villageStatus,
        long capturedAtMillis,
        boolean stale,
        RecentCommand recentCommand,
        List<String> installedMods) {

    public ContextSnapshot {
        nearbyEntities = nearbyEntities == null ? List.of() : List.copyOf(nearbyEntities);
        inventory = inventory == null ? List.of() : List.copyOf(inventory);
        nearbyContainers = nearbyContainers == null ? List.of() : List.copyOf(nearbyContainers);
        villageStatus = villageStatus == null ? VillageStatus.DISABLED : villageStatus;
        installedMods = installedMods == null ? List.of() : List.copyOf(installedMods);
    }

    /** 向后兼容的 16 参数构造器。 */
    public ContextSnapshot(
            String dimensionId, int callerPermissionLevel, int playerX, int playerY, int playerZ,
            long gameTime, boolean raining, boolean thundering, List<NearbyEntity> nearbyEntities,
            List<InventoryEntry> inventory, List<NearbyContainer> nearbyContainers,
            NearbyStructure nearestVillage, VillageStatus villageStatus,
            long capturedAtMillis, boolean stale, RecentCommand recentCommand) {
        this(dimensionId, callerPermissionLevel, playerX, playerY, playerZ, gameTime,
                raining, thundering, nearbyEntities, inventory, nearbyContainers,
                nearestVillage, villageStatus, capturedAtMillis, stale, recentCommand, List.of());
    }

    public record RecentCommand(String command, String outputSummary, long secondsAgo) {
    }

    public enum VillageStatus {
        DISABLED,
        NOT_FOUND,
        FAILED,
        FOUND
    }

    public record NearbyEntity(String typeId, int distance, int count) {
    }

    public record InventoryEntry(String itemId, int count) {
    }

    public record NearbyContainer(String blockId, int x, int y, int z, boolean likelyLoot) {
    }

    public record NearbyStructure(String structureId, int x, int z, int distance) {
    }
}
