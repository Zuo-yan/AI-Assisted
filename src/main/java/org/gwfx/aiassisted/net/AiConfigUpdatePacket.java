package org.gwfx.aiassisted.net;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.gwfx.aiassisted.Config;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.core.config.AiConfigEdits;
import org.gwfx.aiassisted.secret.KeyStore;

import java.util.Map;

/**
 * 客户端 → 服务端：提交一次配置修改（点「应用」时发）。
 *
 * <p><b>服务端是唯一权威</b>，这个包里的一切都当作不可信输入：
 * <ol>
 *   <li>先查权限 —— 客户端控件的可用状态不构成授权，伪造这个包也必须被拒；</li>
 *   <li>再逐字段校验（{@link AiConfigEdits}）—— 任一项不合法就<b>整体拒绝、一项都不写盘</b>，
 *       避免出现"改了一半"的配置；</li>
 *   <li>通过后才批量写入并只落盘一次；</li>
 *   <li>若带回了新的 API Key（界面上的密钥框非空）则一并写入 {@code KeyStore}；</li>
 *   <li>最后回一份<b>权威快照</b>，界面用回包刷新控件 —— 玩家看到的永远是真正生效的值。</li>
 * </ol>
 *
 * <p>承载形式与 {@link AiConfigSyncPacket} 相同：一个 JSON 字符串，字段集定义在 core 层。
 * 密钥只在这个方向上流动（客户端 → 服务端），回包里<b>没有任何</b>可还原密钥的内容。
 */
public record AiConfigUpdatePacket(String json) implements CustomPacketPayload {

    public static final Type<AiConfigUpdatePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_update_config"));

    public static final StreamCodec<ByteBuf, AiConfigUpdatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(AiConfigSyncPacket.MAX_JSON_CHARS), AiConfigUpdatePacket::json,
            AiConfigUpdatePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AiConfigUpdatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            // ① 权限：与 /ai 的管理类子命令同源
            if (!AiConfigSyncPacket.hasEditPermission(player)) {
                AiConfigSyncPacket.send(player, Map.of(),
                        Map.of(), false, AiConfigEdits.ERROR_NO_PERMISSION);
                return;
            }

            // ② 校验：畸形 JSON 会解析成空对象，进而整体报"包里没有可写字段"，这里不需要单独分支
            JsonObject raw = AiConfigEdits.parseObject(packet.json());
            AiConfigEdits.Result result = AiConfigEdits.parse(raw);
            if (!result.ok()) {
                AiConfigSyncPacket.send(player, Map.of(), result.errors(), false, result.error());
                return;
            }

            // ③ 写入：只落盘一次；save() 会触发配置事件，下一个 tick 由 AiRuntime 统一重载
            if (!Config.applyAiSettings(result.values())) {
                AiConfigSyncPacket.send(player, Map.of(), Map.of(), false,
                        "ai.ai_assisted.config.write_failed");
                return;
            }

            // ④ API Key（可选）：只有玩家确实输入了新密钥才改写。
            //    刻意排在配置写入成功之后 —— 整体失败时不留"密钥改了、配置没存"的半截状态。
            storeApiKey(raw);

            // ⑤ 回权威快照：含被归一化/夹紧后的真实值，界面据此刷新
            AiConfigSyncPacket.send(player, result.values(), Map.of(), result.adjusted(), "");
        });
    }

    /**
     * 把玩家在界面上输入的 API Key 写进 {@code KeyStore}（空串 = 本次不改，见 {@link AiConfigEdits#apiKey}）。
     *
     * <p>写文件失败（目录只读等）时退到仅内存的兜底，与 {@code /ai key set} 的处理完全一致，
     * 否则用户会以为"点了应用但没生效"。密钥<b>绝不</b>回显、也不写日志 —— 存进去即登记进脱敏器。
     *
     * <p>这里<b>不</b>调 {@code AiRuntime.reload()}：上一步的配置写入已经触发配置事件，
     * 下一个 tick 的延迟重载会连密钥一起重读（{@code reload()} 内部就是重新读 keyStore 建 Provider）。
     * 再显式 reload 一次只会重载两遍（理由同 {@code Config.applyAiSettings} 的注释）。
     */
    private static void storeApiKey(JsonObject raw) {
        String apiKey = AiConfigEdits.apiKey(raw);
        if (apiKey.isEmpty()) {
            return;
        }
        KeyStore keyStore = AiRuntime.get().keyStore();
        if (!keyStore.store(apiKey)) {
            keyStore.setSessionOnly(apiKey);
        }
    }
}
