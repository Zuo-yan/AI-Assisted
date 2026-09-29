package org.gwfx.aiassisted.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.config.AiConfigEdits;
import org.gwfx.aiassisted.core.config.AiConfigSnapshot;
import org.gwfx.aiassisted.core.llm.ProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：AI 配置快照（打开界面时、以及每次「应用」之后都会发一份）。
 *
 * <p><b>为什么整包只带一个 JSON 字符串</b>：同步体有 18 个字段（含字段级错误表），
 * 远超手写字段编解码能舒服维护的规模。更重要的是理由是<b>字段集只在一处定义</b>
 * （{@link AiConfigSnapshot}），加一项不必动编解码，
 * 而且那份 (反)序列化是纯逻辑、可以直接单测。
 *
 * <p><b>密钥永不出现在这里</b>：包里只有「来源枚举名」与（有权限时）文件路径，
 * 没有任何可以还原出密钥内容的东西。
 */
public record AiConfigSyncPacket(String json) {

    /**
     * JSON 字符数上限。
     *
     * <p>要装下全部 30 多项配置值 + 字段级错误表，其中最长的一项是系统提示词
     * （界面里限 1024 字符，但 TOML 可以被人手改得更长），因此留足余量。
     */
    public static final int MAX_JSON_CHARS = 16384;

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_config_sync");

    public static void encode(AiConfigSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.json, MAX_JSON_CHARS);
    }

    public static AiConfigSyncPacket decode(FriendlyByteBuf buf) {
        return new AiConfigSyncPacket(buf.readUtf(MAX_JSON_CHARS));
    }

    public static void handle(AiConfigSyncPacket packet, Supplier<NetworkEvent.Context> context) {
        // 持有逻辑在 client 包：本类是双端类，客户端类引用只放在 enqueueWork 的 lambda 里，
        // 否则专用服务端加载本类时会因解析客户端类而连带崩
        context.get().enqueueWork(() -> org.gwfx.aiassisted.client.AiConfigClientData.apply(packet.json()));
        context.get().setPacketHandled(true);
    }

    /** 按当前生效的配置发一份快照（打开界面时用）。 */
    public static void sendCurrent(ServerPlayer player) {
        send(player, Map.of(), Map.of(), false, "");
    }

    /**
     * 带着结果反馈发一份快照。
     *
     * @param appliedValues 本次实际写入的值（字段键 → 规范文本）；只有改动过的子集也行，
     *                      下面会先取当前全量再覆盖，因此界面拿到的永远是<b>写完之后</b>的完整状态
     * @param fieldErrors   字段名 → 本地化键
     * @param adjusted      是否有值被规范化/夹紧
     * @param error         整体性错误（无权限、写盘失败等），优先于逐字段提示
     */
    public static void send(ServerPlayer player, Map<String, String> appliedValues,
                            Map<String, String> fieldErrors, boolean adjusted, String error) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        boolean canEdit = hasEditPermission(player);
        AiConfig current = AiRuntime.get().config();

        // 当前全量 → 覆盖本次写入的键。
        // 为什么必须补成全量：界面有两页，一页只提交自己那几个字段；若原样回这几个字段，
        // 另一页的表单就会被刷成空白。
        // 为什么要覆盖而不是直接重读运行时配置：AiRuntime 的重载在下一个 tick 才发生，
        // 此刻读到的还是旧值，界面会显示成"改了没生效"。
        Map<String, String> values = new LinkedHashMap<>(AiConfigEdits.of(current));
        if (appliedValues != null) {
            values.putAll(appliedValues);
        }

        AiConfigSnapshot snapshot = new AiConfigSnapshot(
                canEdit,
                values,
                ProviderRegistry.normalizeId(values.getOrDefault(AiConfigEdits.KEY_PROVIDER, current.provider())),
                AiRuntime.get().keyStore().source().name(),
                // 密钥文件路径只在有编辑权限时下发：非管理员没必要知道服务端的文件布局
                // （密钥内容本身任何情况下都不下发，只发"来源"这一枚举名）
                canEdit ? AiRuntime.get().keyStore().file().toString() : "",
                fieldErrors,
                adjusted,
                error);

        AiPacketHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AiConfigSyncPacket(snapshot.toJson().toString()));
    }

    /**
     * 是否有权修改配置。
     *
     * <p><b>服务端唯一权威判定</b>：与 {@code /ai} 的管理类子命令同源，都走 {@link AiPermissions}
     * （门槛是配置 {@code ai.permission.adminLevel}，默认 3 = 管理员），
     * 避免出现两套"算不算管理员"的定义。客户端下发的 {@code canEdit} 只控制控件是否可点，
     * 不能代替这里的判断。
     */
    public static boolean hasEditPermission(ServerPlayer player) {
        return AiPermissions.allows(player);
    }
}
