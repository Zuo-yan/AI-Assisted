package org.gwfx.aiassisted.client.voice;

import com.mojang.logging.LogUtils;
import org.gwfx.aiassisted.core.voice.WavHelper;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端麦克风音频录制器（Java Sound API 实现）。
 *
 * <p>以 16000Hz, 16-bit, 单声道 PCM 格式录制，并可打包为标准 WAV。
 */
public final class AiVoiceRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单次最长录音时长（毫秒）：60 秒保护上限，防按键卡死或失焦导致的内存无限增长。 */
    public static final long MAX_RECORDING_MILLIS = 60_000L;
    public static final int MAX_PCM_BYTES = 1_920_000;

    private final AudioFormat format;
    private final DataLine.Info info;
    private TargetDataLine line;
    private Thread captureThread;
    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private long recordStartTime = 0;

    public AiVoiceRecorder() {
        // 16kHz, 16-bit, 1 channel, signed, little-endian
        this.format = new AudioFormat(16000.0f, 16, 1, true, false);
        this.info = new DataLine.Info(TargetDataLine.class, format);
    }

    /**
     * 开始录音。
     *
     * @return 是否成功启动录音
     */
    public synchronized boolean startRecording() {
        if (recording.get()) {
            return false;
        }

        if (!AudioSystem.isLineSupported(info)) {
            LOGGER.warn("[AI-Voice] 麦克风音频格式不受当前系统支持");
            return false;
        }

        try {
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
        } catch (LineUnavailableException e) {
            LOGGER.warn("[AI-Voice] 无法打开麦克风设备: {}", e.getMessage());
            return false;
        }

        pcmBuffer.reset();
        recording.set(true);
        recordStartTime = System.currentTimeMillis();

        captureThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            while (recording.get()) {
                if (System.currentTimeMillis() - recordStartTime >= MAX_RECORDING_MILLIS) {
                    LOGGER.info("[AI-Voice] 录音已达最大时长上限（{} 秒），停止采集", MAX_RECORDING_MILLIS / 1000);
                    break;
                }
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) {
                    synchronized (pcmBuffer) {
                        if (pcmBuffer.size() + read <= MAX_PCM_BYTES) {
                            pcmBuffer.write(buffer, 0, read);
                        } else {
                            break;
                        }
                    }
                }
            }
        }, "AI-Voice-Capture-Thread");
        captureThread.setDaemon(true);
        captureThread.start();

        return true;
    }

    /**
     * 停止录音并返回打包好的标准 WAV 字节数组。
     */
    public synchronized byte[] stopRecording() {
        if (!recording.compareAndSet(true, false)) {
            return new byte[0];
        }

        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception ignored) {
            }
            line = null;
        }

        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        byte[] pcmData;
        synchronized (pcmBuffer) {
            pcmData = pcmBuffer.toByteArray();
            pcmBuffer.reset();
        }

        return WavHelper.pcmToWav(pcmData);
    }

    /**
     * 取消录音，丢弃录制的内容。
     */
    public synchronized void cancelRecording() {
        if (recording.compareAndSet(true, false)) {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignored) {
                }
                line = null;
            }
            captureThread = null;
            synchronized (pcmBuffer) {
                pcmBuffer.reset();
            }
        }
    }

    public boolean isRecording() {
        return recording.get();
    }

    public long getRecordingDurationMillis() {
        if (!recording.get()) {
            return 0;
        }
        return System.currentTimeMillis() - recordStartTime;
    }

    public boolean isDurationLimitExceeded() {
        return recording.get() && getRecordingDurationMillis() >= MAX_RECORDING_MILLIS;
    }
}
