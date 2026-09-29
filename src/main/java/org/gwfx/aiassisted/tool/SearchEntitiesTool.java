package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 工具 {@code search_entities}：在玩家周围搜索指定类型的实体。
 *
 * <p>与上下文里的「附近实体」段不同：上下文给出的是<b>所有类型的聚合摘要</b>（按数量排序取前 N），
 * 本工具则针对<b>单个类型</b>给出更精确的结果 —— 数量更多、半径更大、带每个实体的取整坐标。
 *
 * <p>实现直接复用 {@code getEntitiesOfClass}（AABB 一次查询），不做逐坐标扫描；
 * 半径上限比上下文默认值大，但仍然封顶，避免一次查询把主线程拖住。
 *
 * <p><b>必须在服务端主线程调用</b>。
 */
public final class SearchEntitiesTool {

    public static final String NAME = "search_entities";

    static final int DEFAULT_RADIUS = 16;
    static final int MIN_RADIUS = 1;
    static final int MAX_RADIUS = 64;

    private SearchEntitiesTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME, "在玩家周围搜索指定类型的实体（生物/掉落物等），返回数量与最近若干个的坐标。"
                        + "适用于「附近有几只羊/苦力怕」这类问题。")
                .stringParam("entity_id", "实体类型注册表 id，例如 minecraft:sheep、minecraft:creeper", true)
                .integerParam("radius", "搜索半径（格）", MIN_RADIUS, MAX_RADIUS, false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config, JsonObject args) {
        Optional<String> rawId = ToolArgs.string(args, "entity_id");
        if (rawId.isEmpty()) {
            return ToolOutcome.error("缺少参数 entity_id（实体类型注册表 id，例如 minecraft:sheep）");
        }
        Identifier id = Identifier.tryParse(rawId.get());
        if (id == null) {
            return ToolOutcome.error("entity_id 不是合法的注册表 id：" + rawId.get());
        }
        Optional<EntityType<?>> resolved = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (resolved.isEmpty()) {
            return ToolOutcome.error("注册表里不存在这个实体类型：" + id);
        }
        EntityType<?> target = resolved.get();

        int radius = ToolArgs.integer(args, "radius", DEFAULT_RADIUS, MIN_RADIUS, MAX_RADIUS);
        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> found = player.level().getEntitiesOfClass(Entity.class, area,
                candidate -> candidate != player && candidate.isAlive() && candidate.getType() == target);

        found.sort(Comparator.comparingDouble(player::distanceToSqr));
        return ToolOutcome.ok(render(id.toString(), radius, player, found, config.toolMaxResults()));
    }

    private static String render(String entityId, int radius, ServerPlayer player,
                                 List<Entity> found, int maxResults) {
        StringBuilder out = new StringBuilder(256);
        out.append("搜索实体 ").append(entityId).append("（半径 ").append(radius).append(" 格）：\n");
        if (found.isEmpty()) {
            out.append("没有找到。");
            return out.toString();
        }

        int limit = Math.min(found.size(), Math.max(1, maxResults));
        out.append("共 ").append(found.size()).append(" 个。最近的 ").append(limit).append(" 个：\n");
        for (int i = 0; i < limit; i++) {
            Entity entity = found.get(i);
            BlockPos pos = entity.blockPosition();
            out.append("- ").append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                    .append("（距离 ").append((int) Math.round(Math.sqrt(player.distanceToSqr(entity))))
                    .append(" 格）\n");
        }
        return out.toString();
    }
}
