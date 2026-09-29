package org.gwfx.aiassisted.net;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
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

/**
 * 客户端 → 服务端：发起 AI 连通性测试。
 *
 * <p>服务端收到后使用指定（或当前保存的）配置在后台异步发起微型测试请求，并回传测试结果包。
 */
public record AiTestConnectionPacket(String json) implements CustomPacketPayload {

    public static final Type<AiTestConnectionPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_test_connection"));

    public static final StreamCodec<ByteBuf, AiTestConnectionPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(4096), AiTestConnectionPacket::json,
            AiTestConnectionPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AiTestConnectionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            if (!AiPermissions.allows(player)) {
                PacketDistributor.sendToPlayer(player,
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
                PacketDistributor.sendToPlayer(player,
                        new AiTestConnectionResultPacket(false, -1, "API 地址未配置"));
                return;
            }
            if (model.isEmpty()) {
                PacketDistributor.sendToPlayer(player,
                        new AiTestConnectionResultPacket(false, -1, "模型名称未配置"));
                return;
            }

            ProviderRegistry registry = AiRuntime.get().providers();
            ProviderRegistry.ProviderSettings settings = new ProviderRegistry.ProviderSettings(
                    baseUrl, apiKey, Duration.ofSeconds(10), 0);
            LlmProvider testProvider = registry.create(provider, settings);

            ChatRequest testRequest = new ChatRequest(
                    model,
                    "",
                    List.of(ChatMessage.user("ping")),
                    0.0D,
                    5
            );

            long startTime = System.currentTimeMillis();
            testProvider.chat(testRequest)
                    .thenAccept(response -> {
                        long latency = System.currentTimeMillis() - startTime;
                        PacketDistributor.sendToPlayer(player,
                                new AiTestConnectionResultPacket(true, (int) latency, "连接成功 (" + latency + "ms)"));
                    })
                    .exceptionally(ex -> {
                        long latency = System.currentTimeMillis() - startTime;
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        String msg = cause.getMessage() != null ? cause.getMessage() : "未知网络错误";
                        String redactedMsg = AiRuntime.get().redactor().redact(msg);
                        PacketDistributor.sendToPlayer(player,
                                new AiTestConnectionResultPacket(false, (int) latency, redactedMsg));
                        return null;
                    });
        });
    }
}