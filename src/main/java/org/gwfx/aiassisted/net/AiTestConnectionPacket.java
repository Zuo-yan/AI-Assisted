package org.gwfx.aiassisted.net;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.AiPermissions;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.config.AiConfigEdits;
import org.gwfx.aiassisted.core.llm.ChatMessage;
import org.gwfx.aiassisted.core.llm.ChatRequest;
import org.gwfx.aiassisted.core.llm.LlmProvider;
import org.gwfx.aiassisted.core.llm.ProviderRegistry;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：发起 AI 连通性测试。
 *
 * <p>服务端收到后使用指定（或当前保存的）配置在后台异步发起微型测试请求，并回传测试结果包。
 */
public record AiTestConnectionPacket(String json) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_test_connection");

    public static void encode(AiTestConnectionPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.json, 4096);
    }

    public static AiTestConnectionPacket decode(FriendlyByteBuf buf) {
        return new AiTestConnectionPacket(buf.readUtf(4096));
    }

    public static void handle(AiTestConnectionPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) {
                return;
            }
            // 异步回调里不能碰世界：server 在这里（主线程）先捕获
            MinecraftServer server = player.level().getServer();

            if (!AiPermissions.allows(player)) {
                AiPacketHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new AiTestConnectionResultPacket(false, -1, "无权限执行测试"));
                return;
            }

            JsonObject raw = AiConfigEdits.parseObject(packet.json());
            AiConfig current = AiRuntime.get().config();

            String rawProvider = raw.has(AiConfigEdits.KEY_PROVIDER) ? raw.get(AiConfigEdits.KEY_PROVIDER).getAsString() : "";
            String provider = rawProvider.isBlank() ? current.effectiveProvider() : ProviderRegistry.normalizeId(rawProvider);

            String rawBaseUrl = raw.has(AiConfigEdits.KEY_BASE_URL) ? raw.get(AiConfigEdits.KEY_BASE_URL).getAsString() : "";
            String baseUrl = rawBaseUrl.isBlank() ? current.baseUrl() : AiConfig.normalizeBaseUrl(rawBaseUrl);

            String rawModel = raw.has(AiConfigEdits.KEY_MODEL) ? raw.get(AiConfigEdits.KEY_MODEL).getAsString() : "";
            String model = rawModel.isBlank() ? current.model() : rawModel.strip();

            String apiKey = AiConfigEdits.apiKey(raw);
            if (apiKey.isEmpty()) {
                apiKey = AiRuntime.get().keyStore().resolved().orElse("");
            }

            if (baseUrl.isEmpty()) {
                AiPacketHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new AiTestConnectionResultPacket(false, -1, "API 地址未配置"));
                return;
            }
            if (model.isEmpty()) {
                AiPacketHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new AiTestConnectionResultPacket(false, -1, "模型名称未配置"));
                return;
            }

            ProviderRegistry registry = AiRuntime.get().providers();
            ProviderRegistry.ProviderSettings settings = new ProviderRegistry.ProviderSettings(
                    baseUrl, apiKey, Duration.ofSeconds(10), 0);
            LlmProvider testProvider = registry.create(provider, settings);

            // 这是连通性测试，不是正式对话，参数按「最容易被各家接受」取：
            // temperature 给 1.0（默认值）—— 0.0 会被推理系模型（o 系 / gpt-5 系）整单拒收；
            // 上限给 32 —— 太小（如 5）低于 Responses 协议 max_output_tokens 的下限 16，
            // 还没走到网络那一步就 400 了，中转站玩家会误以为「接不上」
            ChatRequest testRequest = new ChatRequest(
                    model,
                    "",
                    List.of(ChatMessage.user("ping")),
                    1.0D,
                    32
            );

            long startTime = System.currentTimeMillis();
            testProvider.chat(testRequest)
                    .thenAccept(response -> {
                        long latency = System.currentTimeMillis() - startTime;
                        sendResult(server, player,
                                new AiTestConnectionResultPacket(true, (int) latency, "连接成功 (" + latency + "ms)"));
                    })
                    .exceptionally(ex -> {
                        long latency = System.currentTimeMillis() - startTime;
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        String msg = cause.getMessage() != null ? cause.getMessage() : "未知网络错误";
                        String redactedMsg = AiRuntime.get().redactor().redact(msg);
                        sendResult(server, player, new AiTestConnectionResultPacket(false, (int) latency, redactedMsg));
                        return null;
                    });
        });
        context.get().setPacketHandled(true);
    }

    /** 结果包必须回服务端主线程发：网络回调在传输线程，且玩家可能已退出/切世界。 */
    private static void sendResult(MinecraftServer server, ServerPlayer player, AiTestConnectionResultPacket result) {
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            AiPacketHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
        });
    }
}
