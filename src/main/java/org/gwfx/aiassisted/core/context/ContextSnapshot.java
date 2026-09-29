package org.gwfx.aiassisted.core.context;

import java.util.List;

/**
 * 感知快照：本轮对话可供模型参考的「游戏事实」。
 *
 * <p>结构借鉴 HiyoriAI 的固定快照字段（connection / world / body / owner / nearby / capturedAt / stale），
 * 但做了取舍：
 * <ul>
 *   <li><b>没有 body 段</b>：本模组不做 AI 玩家实体（FR-06），不存在「AI 自己的身体/背包」，
 *       所以不保留这类永远为空的字段。</li>
 *   <li><b>没有目标/任务段</b>：多步目标系统属于 T001-5，本轮不留空壳字段。</li>
 *   <li>{@code stale} 保留：断连、查询失败或超出缓存时效时明确告知模型
 *       「这份快照可能已过期，别当作实时事实」——这是抑制幻觉最有效的一条。</li>
 * </ul>
 */
public record ContextSnapshot(
        String dimensionId,
        /**
         * 调用者（发起本次提问的玩家）的权限等级 0~4，由服务端判定。
         *
         * <p>它的意义是让模型能回答「我是不是管理员」这类问题，并且<b>知道自己不该假装更高级别</b>：
         * 管理员级工具本身就是按这个等级裁剪后才注册的（看不到的工具就是不许用的）。
         */
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
        /**
         * 最近一次<b>玩家确认执行</b>的指令（T002）。没有则 null，此时整行不渲染。
         *
         * <p>为什么要放进上下文：确认执行后我们刻意不再自动调一次模型（省一次计费，
         * 也避免模型借已批准的指令继续加码），所以结果只能靠下一轮对话带回去 —— 就靠这个字段。
         */
        RecentCommand recentCommand) {

    public ContextSnapshot {
        nearbyEntities = nearbyEntities == null ? List.of() : List.copyOf(nearbyEntities);
        inventory = inventory == null ? List.of() : List.copyOf(inventory);
        nearbyContainers = nearbyContainers == null ? List.of() : List.copyOf(nearbyContainers);
        villageStatus = villageStatus == null ? VillageStatus.DISABLED : villageStatus;
    }

    /**
     * 已确认执行过的指令。
     *
     * @param outputSummary 输出摘要（存储时就已截断，避免一行吃掉整个上下文预算）
     * @param secondsAgo    距今多少秒，由调用方现算（不是存下来的：存下来会变成"当时是 3 秒前"）
     */
    public record RecentCommand(String command, String outputSummary, long secondsAgo) {
    }

    /** 村庄查询的四种结果。必须能区分「没查」和「查了没有」，否则会让模型误判。 */
    public enum VillageStatus {
        /** 配置关闭了结构查询。 */
        DISABLED,
        /** 查了，范围内没有村庄。 */
        NOT_FOUND,
        /** 查询过程出错（快照同时会被标记为 stale）。 */
        FAILED,
        /** 查到了，见 {@link #nearestVillage}。 */
        FOUND
    }

    /**
     * 附近实体：<b>按类型聚合</b>后只给类型、最近距离与数量。
     *
     * <p>不带名字、不带坐标、不带 NBT —— 既是脱敏（不把其他玩家的信息塞给第三方 API），
     * 也是省 token（一群僵尸只占一行）。玩家同样以 {@code minecraft:player ×N} 出现。
     */
    public record NearbyEntity(String typeId, int distance, int count) {
    }

    /** 背包条目：按物品 id 聚合后的总量，不逐槽展开、不带 NBT。 */
    public record InventoryEntry(String itemId, int count) {
    }

    /** 附近容器（箱子/木桶/潜影盒……）。 */
    public record NearbyContainer(String blockId, int x, int y, int z, boolean likelyLoot) {
    }

    /**
     * 最近的结构（本轮只查村庄）。
     *
     * <p><b>刻意不含高度</b>，两个原因（都已核对 26.3 源码）：
     * <ol>
     *   <li>{@code StructurePlacement#getLocatePos} 把 y <b>写死为 0</b>
     *       （{@code new BlockPos(chunkPos.getMinBlockX(), 0, chunkPos.getMinBlockZ())}），
     *       村庄的 {@code locateOffset()} 又是零向量，所以查询结果的高度恒为 0。
     *       把 0 当真实高度交给模型，它很可能回答「村庄在 y=0」（基岩层）—— 那是主动误导。</li>
     *   <li>查真实地表高度需要高度图查询，可能强制加载远处区块；对一条每轮对话都要跑的
     *       上下文采集来说，代价与风险都不划算。</li>
     * </ol>
     * 因此只给水平坐标，并在渲染时明确标注「高度未知」，让模型只能说它确实知道的事。
     *
     * @param distance <b>水平</b>直线距离（格）。不能用三维距离：伪造的 y=0 会掺进
     *                 几十格的垂直分量，把距离算大。
     */
    public record NearbyStructure(String structureId, int x, int z, int distance) {
    }
}
