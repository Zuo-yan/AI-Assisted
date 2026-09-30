package org.gwfx.aiassisted.core.voice;

import org.gwfx.aiassisted.core.http.HttpResponseBytesData;
import org.gwfx.aiassisted.core.http.HttpResponseData;
import org.gwfx.aiassisted.core.http.HttpTransport;
import org.gwfx.aiassisted.core.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceClientTest {

    

    @Test
    void transcribeSendsMultipartAndParsesText() throws Exception {
        byte[] fakeWav = WavHelper.pcmToWav(new byte[1600]);

        HttpTransport mockTransport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponseData> postJson(URI uri, Map<String, String> headers, String jsonBody, Duration timeout) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<HttpResponseData> postMultipart(URI uri, Map<String, String> headers, byte[] multipartBody, String boundary, Duration timeout) {
                assertEquals("https://api.openai.com/v1/audio/transcriptions", uri.toString());
                assertEquals("Bearer test-key", headers.get("Authorization"));
                String bodyStr = new String(multipartBody, StandardCharsets.UTF_8);
                assertTrue(bodyStr.contains("name=\"model\""));
                assertTrue(bodyStr.contains("whisper-1"));
                assertTrue(bodyStr.contains("name=\"file\""));
                assertTrue(bodyStr.contains("name=\"prompt\""));
                return CompletableFuture.completedFuture(new HttpResponseData(200, "{\"text\":\"你好，世界！\"}"));
            }
        };

        VoiceClient client = new VoiceClient(mockTransport);
        String text = client.transcribe(fakeWav, "https://api.openai.com/v1", "whisper-1", "test-key", Duration.ofSeconds(10)).get();
        assertEquals("你好，世界！", text);
    }

    

    @Test
    void transcribeHandlesHttpError() {
        byte[] fakeWav = WavHelper.pcmToWav(new byte[1600]);

        HttpTransport mockTransport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponseData> postJson(URI uri, Map<String, String> headers, String jsonBody, Duration timeout) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<HttpResponseData> postMultipart(URI uri, Map<String, String> headers, byte[] multipartBody, String boundary, Duration timeout) {
                return CompletableFuture.completedFuture(new HttpResponseData(401, "{\"error\":{\"message\":\"Invalid API Key\"}}"));
            }
        };

        VoiceClient client = new VoiceClient(mockTransport);
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                client.transcribe(fakeWav, "https://api.openai.com/v1", "whisper-1", "bad-key", Duration.ofSeconds(10)).get()
        );
        assertTrue(ex.getCause() instanceof LlmException);
        assertTrue(ex.getCause().getMessage().contains("Invalid API Key"));
    }

    @Test
    void normalizeAudioBaseUrlAutoCompletesV1ForKnownDomains() {
        assertEquals("https://api.openai.com/v1", VoiceClient.normalizeAudioBaseUrl("https://api.openai.com"));
        assertEquals("https://api.openai.com/v1", VoiceClient.normalizeAudioBaseUrl("https://api.openai.com/"));
        assertEquals("https://api.siliconflow.cn/v1", VoiceClient.normalizeAudioBaseUrl("https://api.siliconflow.cn"));
        assertEquals("https://api.groq.com/openai/v1", VoiceClient.normalizeAudioBaseUrl("https://api.groq.com/openai"));
        // 已经有 /v1 的不重复追加
        assertEquals("https://api.openai.com/v1", VoiceClient.normalizeAudioBaseUrl("https://api.openai.com/v1"));
        assertEquals("https://custom.endpoint.com/v2", VoiceClient.normalizeAudioBaseUrl("https://custom.endpoint.com/v2"));
    }
}
