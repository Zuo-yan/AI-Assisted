package org.gwfx.aiassisted.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.gwfx.aiassisted.AiAssistedMod;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：AI 连通性测试结果。
 */
public record AiTestConnectionResultPacket(boolean success, int latencyMs, String message) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_test_connection_result");

    public static void encode(AiTestConnectionResultPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeVarInt(packet.latencyMs);
        buf.writeUtf(packet.message, 1024);
    }

    public static AiTestConnectionResultPacket decode(FriendlyByteBuf buf) {
        return new AiTestConnectionResultPacket(buf.readBoolean(), buf.readVarInt(), buf.readUtf(1024));
    }

    public static void handle(AiTestConnectionResultPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> org.gwfx.aiassisted.client.AiConfigClientData.onTestResult(packet));
        context.get().setPacketHandled(true);
    }
}
