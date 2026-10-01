package org.gwfx.aiassisted.client.voice;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.gwfx.aiassisted.net.AiPacketHandler;

/**
 * 客户端语音输入总管理器（单例）。
 */
public final class AiVoiceClientManager {

    private static final AiVoiceClientManager INSTANCE = new AiVoiceClientManager();

    public static AiVoiceClientManager get() {
        return INSTANCE;
    }

    private final AiVoiceRecorder recorder = new AiVoiceRecorder();

    private AiVoiceClientManager() {
    }

    /**
     * 按下语音按键时调用。
     */
    public synchronized void onKeyPressed() {
        if (!recorder.isRecording()) {
            recorder.startRecording();
        }
    }

    /**
     * 松开语音按键时调用。
     */
    public synchronized void onKeyReleased() {
        if (recorder.isRecording()) {
            long duration = recorder.getRecordingDurationMillis();
            byte[] wavBytes = recorder.stopRecording();

            Minecraft mc = Minecraft.getInstance();
            if (duration < 300 || wavBytes.length < 1000) {
                // 录音不足 300ms 判定为误触，不发送并友好提示
                if (mc.player != null) {
                    // 26.3 没有 displayClientMessage(Component, boolean)：overlay 语义用 sendOverlayMessage
                    mc.player.sendOverlayMessage(Component.translatable("ai.ai_assisted.voice.too_short"));
                }
                return;
            }

            // 优先在本地快速语音识别，并将音频与识别文本一并提交服务端
            AiLocalVoiceRecognizer.transcribe(wavBytes).whenComplete((localText, err) -> {
                String text = (localText == null) ? "" : localText.strip();
                AiPacketHandler.sendVoiceInput(wavBytes, text);
            });
        }
    }

    public boolean isRecording() {
        return recorder.isRecording();
    }

    public void cancelRecording() {
        recorder.cancelRecording();
    }
}
