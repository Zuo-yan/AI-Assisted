package org.gwfx.aiassisted.context;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.context.ContextSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 服务端：把 {@link ServerPlayer} 的当前处境采集为 {@link ContextSnapshot}。
 *
 * <p><b>必须在服务端主线程调用。</b>两处 API 有线程要求：
 * {@code ServerChunkCache#getChunkNow} 非主线程直接返回 null（会静默少扫一片区块），
 * 结构查询会触达区块加载。非主线程调用时本类会把村庄查询标记为失败而不是硬闯。
 *
 * <p><b>成本控制</b>（这是本类存在的意义，不是「顺便」）：
 * <ul>
 *   <li>实体：按 AABB 一次查询 + 按类型聚合，越界数据不进上下文；</li>
 *   <li>背包：按物品 id 聚合，不逐槽、不读 NBT；</li>
 *   <li>容器：只遍历<b>已加载区块</b>的 BlockEntity 表，不做逐坐标扫描
 *       （半径 16 的逐坐标扫描是 33³ ≈ 3.6 万次方块查询，区块表通常是数十次）；</li>
 *   <li>村庄：与 {@code /locate structure} 同源，代价最高，因此加了每玩家 TTL 缓存，
 *       并且 {@code createReference=false}（与原版 /locate 一致，不写结构引用）。</li>
 * </ul>
 *
 * <p>任何一个子项抛异常都不会打断整条命令：该项降级为空并把快照标记为 {@code stale}，
 * 让模型知道「这块信息不可信」，而不是拿到伪造的实时数据。
 */
public final class McContextCollector {

    /** 村庄缓存条目上限，超出直接整体清空（缓存本身只是省开销，丢了不影响正确性）。 */
    private static final int MAX_VILLAGE_CACHE_ENTRIES = 512;

    private final Map<UUID, VillageCacheEntry> villageCache = new ConcurrentHashMap<>();

    /**
     * 采集一次完整快照。
     *
     * @param recentCommand 最近一次玩家确认执行的指令（T002）；没有就传 null。
     *                      由调用方给出，因为它们存在 {@code LastCommandStore} 里、与本类的世界采集无关。
     */
    public ContextSnapshot collect(ServerPlayer player, AiConfig config,
                                   ContextSnapshot.RecentCommand recentCommand) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        long now = System.currentTimeMillis();

        Section<List<ContextSnapshot.NearbyEntity>> entities =
                Section.of(() -> collectEntities(level, player, config), List.of());
        Section<List<ContextSnapshot.InventoryEntry>> inventory =
                Section.of(() -> collectInventory(player, config), List.of());
        Section<List<ContextSnapshot.NearbyContainer>> containers =
                Section.of(() -> collectContainers(level, origin, config), List.of());
        VillageResult village = queryVillage(level, origin, player.getUUID(), config, now);

        boolean stale = entities.failed() || inventory.failed() || containers.failed()
                || village.status() == ContextSnapshot.VillageStatus.FAILED;

        return new ContextSnapshot(
                level.dimension().identifier().toString(),
                // 调用者等级由服务端现算：它就是"模型眼里的你"，必须与工具注册时用的同一个判据
                AiPermissions.highestLevelFor(player.permissions()),
                origin.getX(), origin.getY(), origin.getZ(),
                level.getGameTime(),
                level.isRaining(),
                level.isThundering(),
                entities.value(),
                inventory.value(),
                containers.value(),
                village.village(),
                village.status(),
                now,
                stale,
                recentCommand,
                collectInstalledMods());
    }

    // ===== 附近实体 =====

    private static List<ContextSnapshot.NearbyEntity> collectEntities(
            ServerLevel level, ServerPlayer player, AiConfig config) {
        if (config.entityLimit() <= 0) {
            return List.of();
        }

        AABB area = player.getBoundingBox().inflate(config.entityRadius());
        List<Entity> found = level.getEntitiesOfClass(Entity.class, area,
                candidate -> candidate != player && candidate.isAlive());

        // 类型 id -> [最近距离平方, 数量]
        Map<String, int[]> aggregated = new LinkedHashMap<>();
        for (Entity entity : found) {
            String typeId = entityTypeId(entity.getType());
            if (typeId == null) {
                continue;
            }
            int distanceSqr = (int) Math.round(player.distanceToSqr(entity));
            int[] slot = aggregated.computeIfAbsent(typeId, key -> new int[]{Integer.MAX_VALUE, 0});
            slot[0] = Math.min(slot[0], distanceSqr);
            slot[1]++;
        }

        return aggregated.entrySet().stream()
                .map(entry -> new ContextSnapshot.NearbyEntity(
                        entry.getKey(),
                        (int) Math.round(Math.sqrt(entry.getValue()[0])),
                        entry.getValue()[1]))
                .sorted(Comparator.comparingInt(ContextSnapshot.NearbyEntity::distance))
                .limit(config.entityLimit())
                .toList();
    }

    private static String entityTypeId(EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id == null ? null : id.toString();
    }

    // ===== 背包摘要 =====

    private static List<ContextSnapshot.InventoryEntry> collectInventory(ServerPlayer player, AiConfig config) {
        if (config.inventoryTopN() <= 0) {
            return List.of();
        }

        Inventory inventory = player.getInventory();
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }
            counts.merge(id.toString(), stack.getCount(), Integer::sum);
        }

        return counts.entrySet().stream()
                .map(entry -> new ContextSnapshot.InventoryEntry(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(ContextSnapshot.InventoryEntry::count).reversed())
                .limit(config.inventoryTopN())
                .toList();
    }

    // ===== 附近容器（宝箱等）=====

    private static List<ContextSnapshot.NearbyContainer> collectContainers(
            ServerLevel level, BlockPos origin, AiConfig config) {
        if (config.containerLimit() <= 0) {
            return List.of();
        }

        int radius = config.containerRadius();
        int radiusSqr = radius * radius;

        int minChunkX = SectionPos.blockToSectionCoord(origin.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(origin.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(origin.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(origin.getZ() + radius);

        ServerChunkCache chunkSource = level.getChunkSource();
        List<ContainerHit> hits = new ArrayList<>();

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // getChunkNow 只取已加载区块；未加载的返回 null。绝不为了上下文去强加载区块。
                LevelChunk chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockEntity blockEntity = entry.getValue();
                    if (!(blockEntity instanceof Container)) {
                        continue;
                    }
                    BlockPos pos = entry.getKey();
                    int distanceSqr = distanceSqr(origin, pos);
                    if (distanceSqr > radiusSqr) {
                        continue;
                    }
                    Identifier blockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
                    if (blockId == null) {
                        continue;
                    }
                    boolean likelyLoot = blockEntity instanceof RandomizableContainerBlockEntity randomizable
                            && randomizable.getLootTable() != null;
                    hits.add(new ContainerHit(blockId.toString(), pos.getX(), pos.getY(), pos.getZ(),
                            likelyLoot, distanceSqr));
                }
            }
        }

        return hits.stream()
                .sorted(Comparator.comparingInt(ContainerHit::distanceSqr))
                .limit(config.containerLimit())
                .map(hit -> new ContextSnapshot.NearbyContainer(
                        hit.blockId(), hit.x(), hit.y(), hit.z(), hit.likelyLoot()))
                .toList();
    }

    private static int distanceSqr(BlockPos origin, BlockPos target) {
        int dx = target.getX() - origin.getX();
        int dy = target.getY() - origin.getY();
        int dz = target.getZ() - origin.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ===== 最近的村庄 =====

    private VillageResult queryVillage(ServerLevel level, BlockPos origin, UUID playerId,
                                      AiConfig config, long nowMillis) {
        if (!config.structureEnabled()) {
            return new VillageResult(null, ContextSnapshot.VillageStatus.DISABLED);
        }

        long ttlMillis = config.structureCacheSeconds() * 1000L;
        VillageCacheEntry cached = villageCache.get(playerId);
        if (cached != null && ttlMillis > 0 && nowMillis - cached.atMillis() < ttlMillis) {
            return toResult(cached, origin);
        }

        if (!level.getServer().isSameThread()) {
            // 结构查询会触达区块加载，不能在异步线程上硬闯
            return new VillageResult(null, ContextSnapshot.VillageStatus.FAILED);
        }

        VillageCacheEntry entry;
        try {
            // createReference=false：与原版 /locate structure 的调用一致，不写结构引用。
            // 该查询与 /locate 同源，可能触发区块生成，因此有半径上限 + TTL 缓存兜着。
            BlockPos found = level.findNearestMapStructure(
                    StructureTags.VILLAGE, origin, config.structureRadiusChunks(), false);
            entry = found == null
                    ? new VillageCacheEntry(0, 0, ContextSnapshot.VillageStatus.NOT_FOUND, nowMillis)
                    : new VillageCacheEntry(found.getX(), found.getZ(),
                            ContextSnapshot.VillageStatus.FOUND, nowMillis);
        } catch (RuntimeException e) {
            entry = new VillageCacheEntry(0, 0, ContextSnapshot.VillageStatus.FAILED, nowMillis);
        }

        // FAILED 不入缓存：让下一次请求有机会重试，而不是把一次偶发失败钉死 5 分钟
        if (config.structureCacheSeconds() > 0 && entry.status() != ContextSnapshot.VillageStatus.FAILED) {
            if (villageCache.size() >= MAX_VILLAGE_CACHE_ENTRIES) {
                villageCache.clear();
            }
            villageCache.put(playerId, entry);
        }
        return toResult(entry, origin);
    }

    /**
     * 由缓存条目 + <b>当前</b>玩家位置组装结果。
     *
     * <p>距离刻意不缓存、每次现算：缓存的是「村庄在哪」，不是「当时有多远」。
     * 若把距离一起缓存，玩家在 TTL 内移动后，上下文里会出现
     * 「玩家坐标 A」配「距离（相对旧的 A′）」这种自相矛盾的两个数字。
     *
     * <p>另外只算水平距离：结构查询返回的 y 是 {@code getLocatePos} 写死的 0，
     * 把它算进三维距离会凭空多出几十格的垂直分量（见 {@code ContextSnapshot.NearbyStructure} 的说明）。
     */
    private static VillageResult toResult(VillageCacheEntry entry, BlockPos origin) {
        if (entry.status() != ContextSnapshot.VillageStatus.FOUND) {
            return new VillageResult(null, entry.status());
        }
        double dx = entry.x() - origin.getX();
        double dz = entry.z() - origin.getZ();
        int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        return new VillageResult(
                new ContextSnapshot.NearbyStructure(
                        StructureTags.VILLAGE.location().toString(),
                        entry.x(), entry.z(), distance),
                ContextSnapshot.VillageStatus.FOUND);
    }

    /** 缓存条目：只存结构位置与状态，不存距离（见 {@link #toResult}）。 */
    private record VillageCacheEntry(int x, int z,
                                     ContextSnapshot.VillageStatus status,
                                     long atMillis) {
    }

    private record VillageResult(ContextSnapshot.NearbyStructure village,
                                 ContextSnapshot.VillageStatus status) {
    }

    private record ContainerHit(String blockId, int x, int y, int z, boolean likelyLoot, int distanceSqr) {
    }

    /**
     * 子项采集的容错包装：失败不抛，只标记 failed 并降级为 fallback。
     *
     * <p>上下文采集是「锦上添花」的一步，不该因为某个 API 在特定维度/模组环境下抛异常
     * 就把玩家的一次对话整条打断。
     */
    private record Section<T>(T value, boolean failed) {
        static <T> Section<T> of(Supplier<T> action, T fallback) {
            try {
                return new Section<>(action.get(), false);
            } catch (RuntimeException e) {
                return new Section<>(fallback, true);
            }
        }
    }

    private static List<String> collectInstalledMods() {
        List<String> list = new ArrayList<>();
        try {
            for (net.neoforged.neoforgespi.language.IModInfo mod : net.neoforged.fml.ModList.get().getMods()) {
                String modId = mod.getModId();
                if (modId == null || modId.isBlank()) continue;
                if (modId.equals("minecraft") || modId.equals("neoforge") || modId.equals("framework")
                        || modId.equals("ai_assisted") || modId.startsWith("curse") || modId.equals("mixin")) {
                    continue;
                }
                list.add(modId);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }
}
