package org.gwfx.aiassisted.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.gwfx.aiassisted.voice.ServerVoiceService;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：麦克风录制的 WAV 音频输入包（可附带客户端本地转录文本）。
 */
public record AiVoiceInputPacket(byte[] audioData, String recognizedText) {

    public static final int MAX_AUDIO_BYTES = 2_000_000; // ~2MB 保护上限

    public AiVoiceInputPacket {
        audioData = audioData == null ? new byte[0] : audioData;
        recognizedText = recognizedText == null ? "" : recognizedText;
    }

    public static void encode(AiVoiceInputPacket packet, FriendlyByteBuf buf) {
        buf.writeByteArray(packet.audioData);
        buf.writeUtf(packet.recognizedText, 32767);
    }

    public static AiVoiceInputPacket decode(FriendlyByteBuf buf) {
        byte[] audio = buf.readByteArray(MAX_AUDIO_BYTES);
        String text = buf.readUtf(32767);
        return new AiVoiceInputPacket(audio, text);
    }

    public static void handle(AiVoiceInputPacket packet, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ServerPlayer player = ctx.getSender();
        if (player != null && (packet.audioData().length > 0 || !packet.recognizedText().isBlank())) {
            ctx.enqueueWork(() -> ServerVoiceService.handleVoiceInput(player, packet.audioData(), packet.recognizedText()));
        }
        ctx.setPacketHandled(true);
    }
}
