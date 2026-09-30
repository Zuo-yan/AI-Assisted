package org.gwfx.aiassisted.core.voice;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WavHelperTest {

    @Test
    void pcmToWavGeneratesValidHeader() {
        byte[] pcm = new byte[32000]; // 1 second of 16kHz 16-bit mono
        byte[] wav = WavHelper.pcmToWav(pcm);

        assertTrue(WavHelper.isValidWav(wav));
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);
    }

    @Test
    void calculatesDurationCorrectly() {
        byte[] pcm = new byte[32000]; // 1 second
        assertEquals(1.0, WavHelper.getDurationSeconds(pcm.length), 1e-4);

        byte[] halfSec = new byte[16000];
        assertEquals(0.5, WavHelper.getDurationSeconds(halfSec.length), 1e-4);
    }

    @Test
    void invalidDataIsNotWav() {
        assertFalse(WavHelper.isValidWav(null));
        assertFalse(WavHelper.isValidWav(new byte[10]));
        assertFalse(WavHelper.isValidWav(new byte[50]));
    }

    @Test
    void trimsTrailingKeyClickCorrectly() {
        byte[] pcm = new byte[32000]; // 1000ms
        byte[] trimmed = WavHelper.trimTrailingClick(pcm, 60);
        // 60ms = 1920 字节
        assertEquals(32000 - 1920, trimmed.length);
    }

    @Test
    void normalizesGainWithoutClipping() {
        byte[] lowPcm = new byte[100];
        ByteBuffer bb = ByteBuffer.wrap(lowPcm).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(0, (short) 10000); // 峰值 10000

        byte[] boosted = WavHelper.normalizeGain(lowPcm, 26000, 3.5f);
        ByteBuffer outBb = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN);
        short boostedPeak = outBb.getShort(0);

        assertEquals(26000, boostedPeak);
    }
}
