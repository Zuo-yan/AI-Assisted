package org.gwfx.aiassisted.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.gwfx.aiassisted.AiAssistedMod;

/**
 * 客户端 → 服务端：请求一份 AI 配置快照（打开配置界面时发）。
 *
 * <p>服务端立刻回 {@link AiConfigSyncPacket} —— 没有它，界面第一次打开会是空白。
 * 与升级系统 {@code RequestUpgradeSyncPacket} 的职责完全对应。
 */
public record RequestAiConfigPacket() implements CustomPacketPayload {

    public static final Type<RequestAiConfigPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_request_config"));

    public static final StreamCodec<ByteBuf, RequestAiConfigPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestAiConfigPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestAiConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                AiConfigSyncPacket.sendCurrent(player);
            }
        });
    }
}
