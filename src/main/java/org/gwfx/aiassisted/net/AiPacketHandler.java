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
 * 网络层入口（1.20.1 Forge 的 SimpleChannel 形态）。
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

    public static void register() {
        // AI 聊天：服务端把回复分页下发
        CHANNEL.registerMessage(packetId++, AiChatReplyPacket.class,
                AiChatReplyPacket::encode, AiChatReplyPacket::decode, AiChatReplyPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // AI 图形化配置
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

        // AI 语音交互（STT 上行 与 TTS 下行）
        CHANNEL.registerMessage(packetId++, AiVoiceInputPacket.class,
                AiVoiceInputPacket::encode, AiVoiceInputPacket::decode, AiVoiceInputPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        LOGGER.info("[AiAssisted-Network] Successfully registered packet handlers (including voice channels)");
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

    public static void sendVoiceInput(byte[] audioData) {
        sendVoiceInput(audioData, "");
    }

    public static void sendVoiceInput(byte[] audioData, String recognizedText) {
        byte[] safeAudio = audioData == null ? new byte[0] : audioData;
        if (safeAudio.length <= AiVoiceInputPacket.MAX_AUDIO_BYTES) {
            sendToServer(new AiVoiceInputPacket(safeAudio, recognizedText));
        }
    }

    private static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
