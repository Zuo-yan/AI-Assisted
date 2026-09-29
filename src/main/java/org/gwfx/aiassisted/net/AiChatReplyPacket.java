package org.gwfx.aiassisted.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.gwfx.aiassisted.AiAssistedMod;

/**
 * 服务端 → 客户端：AI 回复的一页文本。
 *
 * <p>为什么不直接 {@code sendSystemMessage}：本模组的架构约定是「AI 回复走自定义 payload」
 * （见任务文档「关键设计决策 7」）。走独立通道的收益是——不会和其他聊天类模组的拦截/改写
 * 冲突，将来加聊天界面时也有现成的挂点。展示形态仍然是一条普通聊天消息，
 * 所以玩家体验上就是「AI 回了一句话」。
 *
 * <p>结构完全对齐既有的 {@code ModToastPacket}：文本是服务端解析好的完整 Component，
 * 客户端只负责显示。
 */
public record AiChatReplyPacket(Component text) implements CustomPacketPayload {

    public static final Type<AiChatReplyPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_chat_reply"));

    public static final StreamCodec<ByteBuf, AiChatReplyPacket> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC, AiChatReplyPacket::text,
            AiChatReplyPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AiChatReplyPacket packet, IPayloadContext context) {
        // 显示逻辑在 client 包：本类是双端类，绝不能直接 import net.minecraft.client.*，
        // 否则专用服务端加载模组时会因为要解析 Minecraft 而连带加载客户端界面类直接崩。
        context.enqueueWork(() -> org.gwfx.aiassisted.client.AiChatClient.display(packet.text()));
    }

    /** 把一页回复发给指定玩家。 */
    public static void send(ServerPlayer player, Component text) {
        PacketDistributor.sendToPlayer(player, new AiChatReplyPacket(text));
    }
}
