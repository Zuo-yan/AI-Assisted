package org.gwfx.aiassisted.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyRedactorTest {

    private static final String FAKE_KEY = "sk-fake-0123456789abcdef";

    @Test
    void replacesRegisteredSecret() {
        KeyRedactor redactor = new KeyRedactor();
        redactor.registerSecret(FAKE_KEY);

        String output = redactor.redact("调用失败，key=" + FAKE_KEY + " 请检查");

        assertFalse(output.contains(FAKE_KEY), "密钥必须被替换掉");
        assertTrue(output.contains("***"));
        assertEquals(1, redactor.registeredCount());
    }

    @Test
    void leavesInputUntouchedWhenNoSecretRegistered() {
        KeyRedactor redactor = new KeyRedactor();
        assertEquals("普通日志内容", redactor.redact("普通日志内容"));
    }

    @Test
    void ignoresNullOrEmptyAndTooShortSecrets() {
        KeyRedactor redactor = new KeyRedactor();
        redactor.registerSecret(null);
        redactor.registerSecret("");
        redactor.registerSecret("   ");
        // 过短的"密钥"登记进来会把正常日志打成 ***（例如日志里出现一个 "1"）
        redactor.registerSecret("abc");

        assertEquals(0, redactor.registeredCount());
        assertEquals("abc 出现在日志里", redactor.redact("abc 出现在日志里"));
    }

    @Test
    void handlesNullInput() {
        KeyRedactor redactor = new KeyRedactor();
        assertNull(redactor.redact(null));
        assertEquals("", redactor.redact(""));
    }

    @Test
    void masksSensitiveUrlQueryParameters() {
        assertEquals("https://api.example.com/v1?api_key=***&x=1",
                KeyRedactor.redactUrl("https://api.example.com/v1?api_key=SECRET123&x=1"));
        assertEquals("https://api.example.com/v1?token=***",
                KeyRedactor.redactUrl("https://api.example.com/v1?token=abc.def.ghi"));
        assertEquals("https://api.example.com/v1?Authorization=***&b=2",
                KeyRedactor.redactUrl("https://api.example.com/v1?Authorization=Bearer%20xyz&b=2"));
    }

    @Test
    void urlRedactionWorksWithoutRegisteringAnything() {
        // 模式式兜底：即使某条密钥没来得及登记，也不该原样落盘
        String output = KeyRedactor.redactUrl("GET /v1/chat?access_token=leaked-value");

        assertFalse(output.contains("leaked-value"));
        assertTrue(output.contains("access_token=***"));
    }

    @Test
    void clearRemovesRegisteredSecrets() {
        KeyRedactor redactor = new KeyRedactor();
        redactor.registerSecret(FAKE_KEY);
        redactor.clear();

        assertEquals(0, redactor.registeredCount());
        assertEquals(FAKE_KEY, redactor.redact(FAKE_KEY));
    }

    @Test
    void replacesEveryOccurrence() {
        KeyRedactor redactor = new KeyRedactor();
        redactor.registerSecret(FAKE_KEY);

        String output = redactor.redact(FAKE_KEY + " 和 " + FAKE_KEY);

        assertFalse(output.contains(FAKE_KEY));
        assertEquals("*** 和 ***", output);
    }
}
