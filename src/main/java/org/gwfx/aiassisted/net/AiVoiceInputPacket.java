package org.gwfx.aiassisted.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.voice.ServerVoiceService;

/**
 * 客户端 → 服务端：麦克风录制的 WAV 音频输入包（可附带客户端本地转录文本）。
 */
public record AiVoiceInputPacket(byte[] audioData, String recognizedText) implements CustomPacketPayload {

    public static final int MAX_AUDIO_BYTES = 2_000_000; // ~2MB 保护上限
    public static final int MAX_TEXT_CHARS = 32767;

    public static final Type<AiVoiceInputPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AiAssistedMod.MODID, "ai_voice_input"));

    public static final StreamCodec<ByteBuf, AiVoiceInputPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.byteArray(MAX_AUDIO_BYTES), AiVoiceInputPacket::audioData,
            ByteBufCodecs.stringUtf8(MAX_TEXT_CHARS), AiVoiceInputPacket::recognizedText,
            AiVoiceInputPacket::new);

    public AiVoiceInputPacket {
        audioData = audioData == null ? new byte[0] : audioData;
        recognizedText = recognizedText == null ? "" : recognizedText;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AiVoiceInputPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            if (player != null && (packet.audioData().length > 0 || !packet.recognizedText().isBlank())) {
                ServerVoiceService.handleVoiceInput(player, packet.audioData(), packet.recognizedText());
            }
        });
    }
}
