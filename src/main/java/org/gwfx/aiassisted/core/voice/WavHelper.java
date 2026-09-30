package org.gwfx.aiassisted.core.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAV 与 PCM 音频数据封装与声学预处理工具类（零 MC 依赖）。
 *
 * <p>提供标准的 44 字节 RIFF/WAVE 文件头打包、自适应音量增益归一化与按键杂音裁切。
 */
public final class WavHelper {

    public static final int DEFAULT_SAMPLE_RATE = 16000;
    public static final int DEFAULT_CHANNELS = 1;
    public static final int DEFAULT_BITS_PER_SAMPLE = 16;

    private WavHelper() {
    }

    /**
     * 将 16-bit PCM 字节流封装为符合 RIFF/WAVE 标准的 WAV 二进制数组。
     */
    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        if (pcmData == null) {
            pcmData = new byte[0];
        }

        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        short blockAlign = (short) (channels * (bitsPerSample / 8));
        int totalDataLen = pcmData.length;
        int totalAudioLen = totalDataLen + 36;

        byte[] header = new byte[44];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF chunk descriptor
        buffer.put((byte) 'R');
        buffer.put((byte) 'I');
        buffer.put((byte) 'F');
        buffer.put((byte) 'F');
        buffer.putInt(totalAudioLen);
        buffer.put((byte) 'W');
        buffer.put((byte) 'A');
        buffer.put((byte) 'V');
        buffer.put((byte) 'E');

        // "fmt " sub-chunk
        buffer.put((byte) 'f');
        buffer.put((byte) 'm');
        buffer.put((byte) 't');
        buffer.put((byte) ' ');
        buffer.putInt(16); // 16 for PCM
        buffer.putShort((short) 1); // Linear quantization (PCM)
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort(blockAlign);
        buffer.putShort((short) bitsPerSample);

        // "data" sub-chunk
        buffer.put((byte) 'd');
        buffer.put((byte) 'a');
        buffer.put((byte) 't');
        buffer.put((byte) 'a');
        buffer.putInt(totalDataLen);

        ByteArrayOutputStream out = new ByteArrayOutputStream(header.length + pcmData.length);
        out.writeBytes(header);
        out.writeBytes(pcmData);
        return out.toByteArray();
    }

    /**
     * 默认单声道 16kHz 16-bit 格式快捷封装（自动包含音频预处理：杂音切除与音量归一化）。
     */
    public static byte[] pcmToWav(byte[] pcmData) {
        byte[] preprocessed = preprocessPcm(pcmData);
        return pcmToWav(preprocessed, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS, DEFAULT_BITS_PER_SAMPLE);
    }

    /**
     * 音频声学预处理：切除松键瞬间机械杂音，并进行自适应峰值音量增益放大。
     */
    public static byte[] preprocessPcm(byte[] rawPcm) {
        if (rawPcm == null || rawPcm.length < 3200) { // 小于 100ms 不处理
            return rawPcm == null ? new byte[0] : rawPcm;
        }

        // 1. 裁剪末尾 60ms 机械按键弹起杂音（16kHz 16-bit 单声道 = 1920 字节）
        byte[] trimmed = trimTrailingClick(rawPcm, 60);

        // 2. 自适应音量增益（目标峰值 26000，约 80% 满量程，最大放大 3.5 倍）
        return normalizeGain(trimmed, 26000, 3.5f);
    }

    /**
     * 裁剪录音末尾松开键盘瞬间产生的机械弹起冲击噪音，并在尾部施加平滑淡出。
     */
    public static byte[] trimTrailingClick(byte[] pcm, int trimMillis) {
        if (pcm == null || pcm.length < 6400) { // 短于 200ms 不裁切
            return pcm;
        }
        int bytesPerSec = DEFAULT_SAMPLE_RATE * DEFAULT_CHANNELS * (DEFAULT_BITS_PER_SAMPLE / 8); // 32000
        int trimBytes = (trimMillis * bytesPerSec) / 1000;
        // 保证对齐到 2 字节（16-bit sample）
        trimBytes = (trimBytes / 2) * 2;

        int newLen = Math.max(3200, pcm.length - trimBytes);
        byte[] result = new byte[newLen];
        System.arraycopy(pcm, 0, result, 0, newLen);

        // 在末尾 20ms 施加线性淡出（fade-out），防止切口截断导致的高频破音
        int fadeSamples = (20 * DEFAULT_SAMPLE_RATE) / 1000;
        int fadeStartIndex = Math.max(0, (newLen / 2) - fadeSamples);
        ByteBuffer bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = fadeStartIndex; i < newLen / 2; i++) {
            int pos = i * 2;
            short sample = bb.getShort(pos);
            float factor = (float) (newLen / 2 - i) / (float) (newLen / 2 - fadeStartIndex);
            short faded = (short) (sample * factor);
            bb.putShort(pos, faded);
        }

        return result;
    }

    /**
     * 自适应峰值音量增益放大（提升低信噪比录音识别率，带饱和防爆音夹紧）。
     */
    public static byte[] normalizeGain(byte[] pcm, int targetPeak, float maxGainFactor) {
        if (pcm == null || pcm.length < 2) {
            return pcm;
        }

        ByteBuffer bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int sampleCount = pcm.length / 2;

        int maxVal = 0;
        for (int i = 0; i < sampleCount; i++) {
            short sample = bb.getShort(i * 2);
            int abs = Math.abs(sample);
            if (abs > maxVal) {
                maxVal = abs;
            }
        }

        if (maxVal <= 0 || maxVal >= targetPeak) {
            return pcm; // 已经足够大或者全静音，无需提升
        }

        float gain = (float) targetPeak / (float) maxVal;
        if (gain > maxGainFactor) {
            gain = maxGainFactor;
        }

        byte[] boosted = new byte[pcm.length];
        ByteBuffer outBb = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < sampleCount; i++) {
            short sample = bb.getShort(i * 2);
            int scaled = Math.round(sample * gain);
            // 夹紧在 [-32768, 32767]
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            outBb.putShort(i * 2, (short) scaled);
        }

        return boosted;
    }

    /**
     * 检查数据是否具有合法的 WAV 头部特征（RIFF....WAVE）。
     */
    public static boolean isValidWav(byte[] data) {
        if (data == null || data.length < 44) {
            return false;
        }
        return data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    /**
     * 计算 PCM 音频时长（秒）。
     */
    public static double getDurationSeconds(int pcmBytesLength, int sampleRate, int channels, int bitsPerSample) {
        int bytesPerSecond = sampleRate * channels * (bitsPerSample / 8);
        if (bytesPerSecond <= 0) {
            return 0.0D;
        }
        return (double) pcmBytesLength / bytesPerSecond;
    }

    public static double getDurationSeconds(int pcmBytesLength) {
        return getDurationSeconds(pcmBytesLength, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS, DEFAULT_BITS_PER_SAMPLE);
    }
}
