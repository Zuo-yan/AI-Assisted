package org.gwfx.aiassisted.voice;

import org.gwfx.aiassisted.client.voice.AiLocalVoiceRecognizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceIntentTest {

    @Test
    void recognizesConfirmIntents() {
        assertTrue(ServerVoiceService.isConfirmIntent("确认"));
        assertTrue(ServerVoiceService.isConfirmIntent("确定"));
        assertTrue(ServerVoiceService.isConfirmIntent("执行"));
        assertTrue(ServerVoiceService.isConfirmIntent("好的，造吧！"));
        assertTrue(ServerVoiceService.isConfirmIntent("可以"));
        assertTrue(ServerVoiceService.isConfirmIntent("行"));
        assertTrue(ServerVoiceService.isConfirmIntent("好的"));
        assertTrue(ServerVoiceService.isConfirmIntent("没问题"));
        assertTrue(ServerVoiceService.isConfirmIntent("确认建造"));
        assertTrue(ServerVoiceService.isConfirmIntent("确认执行"));
        assertTrue(ServerVoiceService.isConfirmIntent("好的执行吧"));
        assertTrue(ServerVoiceService.isConfirmIntent("OK"));
        assertTrue(ServerVoiceService.isConfirmIntent("yes"));
    }

    @Test
    void recognizesCancelIntents() {
        assertTrue(ServerVoiceService.isCancelIntent("取消"));
        assertTrue(ServerVoiceService.isCancelIntent("算了"));
        assertTrue(ServerVoiceService.isCancelIntent("不要了"));
        assertTrue(ServerVoiceService.isCancelIntent("别建了"));
        assertTrue(ServerVoiceService.isCancelIntent("别造了"));
        assertTrue(ServerVoiceService.isCancelIntent("别执行"));
        assertTrue(ServerVoiceService.isCancelIntent("cancel"));
        assertTrue(ServerVoiceService.isCancelIntent("abort"));
    }

    @Test
    void rejectsAmbiguousOrQuestions() {
        assertFalse(ServerVoiceService.isConfirmIntent("你确认吗？"));
        assertFalse(ServerVoiceService.isConfirmIntent("确定可以吗"));
        assertFalse(ServerVoiceService.isConfirmIntent("不确定"));
        assertFalse(ServerVoiceService.isConfirmIntent("先不确认"));
        assertFalse(ServerVoiceService.isConfirmIntent("帮我看看周围"));
    }

    @Test
    void correctsCommonPhoneticMistakes() {
        assertEquals("帮我建一个小木屋", AiLocalVoiceRecognizer.correctCommonMistakes("王文件一个小木屋"));
        assertEquals("帮我建一个小木屋", AiLocalVoiceRecognizer.correctCommonMistakes("当我见一个小木屋"));
        assertEquals("帮我建一个小木屋", AiLocalVoiceRecognizer.correctCommonMistakes("把文件一个小木屋"));
        assertEquals("造一个小木屋", AiLocalVoiceRecognizer.correctCommonMistakes("照一个小木屋"));
    }
}
