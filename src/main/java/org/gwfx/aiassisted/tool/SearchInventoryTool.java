package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具 {@code search_inventory}：过滤背包。
 *
 * <p>补的缺口：上下文里的「背包摘要」是**按数量排序取前 N 项**的聚合摘要
 * （{@code ai.context.inventoryTopN}，默认 8）。所以「我背包里到底有几个钻石」这类问题，
 * 只要钻石没进前 8 就答不了；给定物品的**逐槽明细**更是完全没有。
 *
 * <p>两种情况：不传 {@code item_id} = 完整聚合摘要（比上下文更全）；
 * 传 {@code item_id} = 该物品的逐槽明细。
 *
 * <p><b>隐私取舍</b>：只读发起者<b>自己</b>的背包。
 *
 * <p><b>必须在服务端主线程调用</b>。
 */
public final class SearchInventoryTool {

    public static final String NAME = "search_inventory";

    /** 快捷栏槽位数量，用来把槽位号翻译成玩家熟悉的说法。 */
    private static final int HOTBAR_SIZE = 9;

    private SearchInventoryTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME, "查询玩家背包（含快捷栏）里的物品：不给 item_id 就返回完整摘要，"
                        + "给 item_id 就返回该物品的逐槽明细与总数。")
                .stringParam("item_id", "只查某个物品的注册表 id，例如 minecraft:diamond；省略则查全部", false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config, JsonObject args) {
        Inventory inventory = player.getInventory();

        Optional<String> rawId = ToolArgs.string(args, "item_id");
        if (rawId.isEmpty()) {
            return ToolOutcome.ok(describeAll(inventory, config.toolMaxResults()));
        }

        ResourceLocation id = ResourceLocation.tryParse(rawId.get());
        if (id == null) {
            return ToolOutcome.error("item_id 不是合法的注册表 id：" + rawId.get());
        }
        Optional<Item> resolved = BuiltInRegistries.ITEM.getOptional(id);
        if (resolved.isEmpty()) {
            return ToolOutcome.error("注册表里不存在这个物品：" + id);
        }
        return ToolOutcome.ok(describeItem(inventory, resolved.get(), id));
    }

    /** 完整摘要：按物品聚合、按数量排序。 */
    private static String describeAll(Inventory inventory, int maxResults) {
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        Map<ResourceLocation, Integer> slotCounts = new HashMap<>();
        int usedSlots = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }
            counts.merge(id, stack.getCount(), Integer::sum);
            slotCounts.merge(id, 1, Integer::sum);
            usedSlots++;
        }

        if (counts.isEmpty()) {
            return "背包与快捷栏都是空的。";
        }

        List<Map.Entry<ResourceLocation, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));

        int limit = Math.min(sorted.size(), Math.max(1, maxResults));
        StringBuilder out = new StringBuilder(256);
        out.append("背包（含快捷栏）占用 ").append(usedSlots).append(" 个槽位，共 ")
                .append(counts.size()).append(" 种物品");
        if (sorted.size() > limit) {
            out.append("（按数量排序，只列出前 ").append(limit).append(" 种）");
        }
        out.append("：\n");

        for (int i = 0; i < limit; i++) {
            Map.Entry<ResourceLocation, Integer> entry = sorted.get(i);
            out.append("- ").append(entry.getKey()).append(" x").append(entry.getValue())
                    .append("（占 ").append(slotCounts.get(entry.getKey())).append(" 槽）\n");
        }
        return out.toString();
    }

    /** 单个物品的逐槽明细。 */
    private static String describeItem(Inventory inventory, Item item, ResourceLocation id) {
        StringBuilder detail = new StringBuilder(128);
        int total = 0;
        int slotCount = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            // Item 是注册表单例，引用比较即身份比较
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            total += stack.getCount();
            slotCount++;
            detail.append("- 槽位 ").append(slot)
                    .append("（").append(slot < HOTBAR_SIZE ? "快捷栏" : "主背包").append("）")
                    .append(" x").append(stack.getCount()).append('\n');
        }

        if (total == 0) {
            return "背包（含快捷栏）里没有 " + id + "。";
        }
        return "背包（含快捷栏）里有 " + id + " 共 " + total + " 个，分布在 " + slotCount + " 个槽位：\n" + detail;
    }
}
