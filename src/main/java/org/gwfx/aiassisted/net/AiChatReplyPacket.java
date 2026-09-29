package org.gwfx.aiassisted.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.gwfx.aiassisted.AiAssistedMod;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：AI 回复的一页文本。
 *
 * <p>为什么不直接 {@code sendSystemMessage}：本模组的架构约定是「AI 回复走自定义包」
 * （见任务文档「关键设计决策 7」）。走独立通道的收益是——不会和其他聊天类模组的拦截/改写
 * 冲突，将来加聊天界面时也有现成的挂点。展示形态仍然是一条普通聊天消息，
 * 所以玩家体验上就是「AI 回了一句话」。
 */
public record AiChatReplyPacket(Component text) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_chat_reply");

    public static void encode(AiChatReplyPacket packet, FriendlyByteBuf buf) {
        buf.writeComponent(packet.text);
    }

    public static AiChatReplyPacket decode(FriendlyByteBuf buf) {
        return new AiChatReplyPacket(buf.readComponent());
    }

    public static void handle(AiChatReplyPacket packet, Supplier<NetworkEvent.Context> context) {
        // 显示逻辑在 client 包：本类是双端类，绝不能直接 import net.minecraft.client.*，
        // 否则专用服务端加载模组时会因为要解析 Minecraft 而连带加载客户端界面类直接崩。
        context.get().enqueueWork(() -> org.gwfx.aiassisted.client.AiChatClient.display(packet.text()));
        context.get().setPacketHandled(true);
    }

    /** 把一页回复发给指定玩家。 */
    public static void send(ServerPlayer player, Component text) {
        AiPacketHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new AiChatReplyPacket(text));
    }
}
