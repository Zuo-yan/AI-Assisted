package org.gwfx.aiassisted.core.llm;

import org.gwfx.aiassisted.core.http.HttpResponseData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpErrorsTest {

    @Test
    void extractsRelayErrorMessage() {
        // one-api / new-api 等中转站的典型错误体：{"error":{"code":"","message":"..."}}
        LlmException error = HttpErrors.fromResponse(new HttpResponseData(403,
                "{\"error\":{\"code\":\"\",\"message\":\"This token has no access to model gpt-6.1-sol\"}}"));

        assertEquals(403, error.httpStatus());
        assertEquals("HTTP 403：This token has no access to model gpt-6.1-sol", error.getMessage());
    }

    @Test
    void extractsAnthropicStyleErrorMessage() {
        LlmException error = HttpErrors.fromResponse(new HttpResponseData(401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"invalid x-api-key\"}}"));

        assertEquals(401, error.httpStatus());
        assertEquals("HTTP 401：invalid x-api-key", error.getMessage());
    }

    @Test
    void extractsTopLevelMessageWhenErrorWrapperMissing() {
        LlmException error = HttpErrors.fromResponse(new HttpResponseData(429,
                "{\"message\":\"请求过于频繁，请稍后再试\"}"));

        assertEquals("HTTP 429：请求过于频繁，请稍后再试", error.getMessage());
    }

    @Test
    void fallsBackToBodySnippetWhenBodyIsNotJson() {
        LlmException error = HttpErrors.fromResponse(new HttpResponseData(502, "<html>Bad Gateway</html>"));

        assertEquals("HTTP 502：<html>Bad Gateway</html>", error.getMessage());
    }

    @Test
    void omitsSnippetWhenBodyEmpty() {
        assertEquals("HTTP 500", HttpErrors.fromResponse(new HttpResponseData(500, "")).getMessage());
        assertEquals("HTTP 500", HttpErrors.fromResponse(new HttpResponseData(500, null)).getMessage());
        assertEquals("HTTP 500", HttpErrors.fromResponse(new HttpResponseData(500, "   ")).getMessage());
    }

    @Test
    void ignoresEmptyOrNonPrimitiveMessage() {
        assertEquals("HTTP 400：{\"error\":{\"message\":null}}",
                HttpErrors.fromResponse(new HttpResponseData(400, "{\"error\":{\"message\":null}}")).getMessage());
        assertEquals("HTTP 400：{\"error\":{}}",
                HttpErrors.fromResponse(new HttpResponseData(400, "{\"error\":{}}")).getMessage());
    }

    @Test
    void truncatesBothExtractedMessageAndRawSnippet() {
        String longMessage = "m".repeat(5000);
        LlmException extracted = HttpErrors.fromResponse(new HttpResponseData(403,
                "{\"error\":{\"message\":\"" + longMessage + "\"}}"));
        assertTrue(extracted.getMessage().length() < 400,
                "提取的 message 过长: " + extracted.getMessage().length());

        String longBody = "x".repeat(5000);
        LlmException snippet = HttpErrors.fromResponse(new HttpResponseData(500, longBody));
        assertTrue(snippet.getMessage().length() < 400,
                "原文片段过长: " + snippet.getMessage().length());
    }
}
