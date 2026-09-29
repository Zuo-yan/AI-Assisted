package org.gwfx.aiassisted.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.gwfx.aiassisted.AiAssistedMod;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求一份 AI 配置快照（打开配置界面时发）。
 *
 * <p>服务端立刻回 {@link AiConfigSyncPacket} —— 没有它，界面第一次打开会是空白。
 */
public record RequestAiConfigPacket() {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_request_config");

    public static void encode(RequestAiConfigPacket packet, FriendlyByteBuf buf) {
        // 无字段
    }

    public static RequestAiConfigPacket decode(FriendlyByteBuf buf) {
        return new RequestAiConfigPacket();
    }

    public static void handle(RequestAiConfigPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player != null) {
                AiConfigSyncPacket.sendCurrent(player);
            }
        });
        context.get().setPacketHandled(true);
    }
}
