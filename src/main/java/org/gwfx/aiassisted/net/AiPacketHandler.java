package org.gwfx.aiassisted.net;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.client.ClientPacketSender;
import org.slf4j.Logger;

@EventBusSubscriber(modid = AiAssistedMod.MODID)
public final class AiPacketHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AiPacketHandler() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(AiAssistedMod.MODID).versioned("1");

        // AI 聊天：服务端把回复分页下发（只读展示，不需要客户端回传）
        registrar.playToClient(
                AiChatReplyPacket.TYPE,
                AiChatReplyPacket.STREAM_CODEC,
                AiChatReplyPacket::handle
        );

        // AI 图形化配置：打开界面请求快照 → 提交修改 → 服务端回权威快照
        registrar.playToServer(
                RequestAiConfigPacket.TYPE,
                RequestAiConfigPacket.STREAM_CODEC,
                RequestAiConfigPacket::handle
        );
        registrar.playToServer(
                AiConfigUpdatePacket.TYPE,
                AiConfigUpdatePacket.STREAM_CODEC,
                AiConfigUpdatePacket::handle
        );
        registrar.playToClient(
                AiConfigSyncPacket.TYPE,
                AiConfigSyncPacket.STREAM_CODEC,
                AiConfigSyncPacket::handle
        );

        // AI 连通性测试
        registrar.playToServer(
                AiTestConnectionPacket.TYPE,
                AiTestConnectionPacket.STREAM_CODEC,
                AiTestConnectionPacket::handle
        );
        registrar.playToClient(
                AiTestConnectionResultPacket.TYPE,
                AiTestConnectionResultPacket.STREAM_CODEC,
                AiTestConnectionResultPacket::handle
        );

        // AI 语音交互（STT 上行 与 TTS 下行）
        registrar.playToServer(
                AiVoiceInputPacket.TYPE,
                AiVoiceInputPacket.STREAM_CODEC,
                AiVoiceInputPacket::handle
        );

        LOGGER.info("[AiAssisted-Network] Successfully registered payload handlers (including voice channels)");
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
        String safeText = recognizedText == null ? "" : recognizedText;
        if (safeAudio.length > AiVoiceInputPacket.MAX_AUDIO_BYTES) {
            if (!safeText.isBlank()) {
                // 本地有识别文本，丢弃过大音频数据并仅上报文本
                sendToServer(new AiVoiceInputPacket(new byte[0], safeText));
            } else {
                LOGGER.warn("[AiAssisted-Network] 语音数据超过上限且无转录文本，丢弃");
            }
            return;
        }
        sendToServer(new AiVoiceInputPacket(safeAudio, safeText));
    }

    private static void sendToServer(CustomPacketPayload payload) {
        ClientPacketSender.sendToServer(payload);
    }
}
