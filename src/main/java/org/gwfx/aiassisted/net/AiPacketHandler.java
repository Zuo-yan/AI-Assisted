package org.gwfx.aiassisted.net;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.gwfx.aiassisted.AiAssistedMod;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * 网络层入口（1.20.1 Forge 的 SimpleChannel 形态；26.3 分支等价物是 PayloadRegistrar）。
 *
 * <p>六个包的方向与 26.3 保持一致：
 * <ul>
 *   <li>服务端 → 客户端：{@link AiChatReplyPacket}、{@link AiConfigSyncPacket}、
 *       {@link AiTestConnectionResultPacket}</li>
 *   <li>客户端 → 服务端：{@link RequestAiConfigPacket}、{@link AiConfigUpdatePacket}、
 *       {@link AiTestConnectionPacket}</li>
 * </ul>
 *
 * <p>协议版本固定为 "1"：通道按版本协商，双端一致才允许连接。
 * 包的编解码走 {@code FriendlyByteBuf}，字段与 26.3 的 StreamCodec 一一对应。
 */
public final class AiPacketHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(AiAssistedMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int packetId;

    private AiPacketHandler() {
    }

    /** 在模组构造期调用（见 {@code AiAssistedMod}）：1.20.1 的消息注册没有独立事件。 */
    public static void register() {
        // AI 聊天：服务端把回复分页下发（只读展示，不需要客户端回传）
        CHANNEL.registerMessage(packetId++, AiChatReplyPacket.class,
                AiChatReplyPacket::encode, AiChatReplyPacket::decode, AiChatReplyPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // AI 图形化配置：打开界面请求快照 → 提交修改 → 服务端回权威快照
        CHANNEL.registerMessage(packetId++, RequestAiConfigPacket.class,
                RequestAiConfigPacket::encode, RequestAiConfigPacket::decode, RequestAiConfigPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, AiConfigUpdatePacket.class,
                AiConfigUpdatePacket::encode, AiConfigUpdatePacket::decode, AiConfigUpdatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, AiConfigSyncPacket.class,
                AiConfigSyncPacket::encode, AiConfigSyncPacket::decode, AiConfigSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // AI 连通性测试
        CHANNEL.registerMessage(packetId++, AiTestConnectionPacket.class,
                AiTestConnectionPacket::encode, AiTestConnectionPacket::decode, AiTestConnectionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, AiTestConnectionResultPacket.class,
                AiTestConnectionResultPacket::encode, AiTestConnectionResultPacket::decode,
                AiTestConnectionResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        LOGGER.info("[AiAssisted-Network] Successfully registered packet handlers");
    }

    public static void sendRequestAiConfig() {
        sendToServer(new RequestAiConfigPacket());
    }

    public static void sendTestAiConnection(String json) {
        String payload = json == null ? "{}" : json;
        if (payload.length() > 4096) {
            payload = "{}";
        }
        sendToServer(new AiTestConnectionPacket(payload));
    }

    public static void sendUpdateAiConfig(String json) {
        String payload = json == null ? "{}" : json;
        if (payload.length() > AiConfigSyncPacket.MAX_JSON_CHARS) {
            payload = "{}";
        }
        sendToServer(new AiConfigUpdatePacket(payload));
    }

    private static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
