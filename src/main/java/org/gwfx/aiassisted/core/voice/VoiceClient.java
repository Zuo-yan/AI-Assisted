package org.gwfx.aiassisted.core.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.http.HttpResponseData;
import org.gwfx.aiassisted.core.http.HttpTransport;
import org.gwfx.aiassisted.core.llm.LlmException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 语音识别 (STT) 客户端（零 MC 依赖）。
 *
 * <p>遵循 OpenAI Compatible 标准音频 API 规范：
 * POST /audio/transcriptions (multipart/form-data)
 */
public final class VoiceClient {

    public static final String DEFAULT_STT_MODEL = "whisper-1";

    private final HttpTransport transport;

    public VoiceClient(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * 将 WAV 音频发送至 STT 接口转录为自然语言文本。
     */
    public CompletableFuture<String> transcribe(
            byte[] wavBytes, String sttBaseUrl, String model, String apiKey, Duration timeout) {
        if (wavBytes == null || wavBytes.length == 0) {
            return CompletableFuture.completedFuture("");
        }

        String base = normalizeAudioBaseUrl(sttBaseUrl);
        if (base.isEmpty()) {
            return CompletableFuture.failedFuture(new LlmException("未配置语音识别 (STT) 的 API 地址"));
        }

        String endpoint = base.endsWith("/audio/transcriptions") ? base : base + "/audio/transcriptions";
        URI uri = URI.create(endpoint);

        String effectiveModel = (model == null || model.isBlank()) ? DEFAULT_STT_MODEL : model.strip();
        String boundary = "----AiVoiceBoundary" + UUID.randomUUID().toString().replace("-", "");

        byte[] multipartBody = buildSttMultipartBody(wavBytes, effectiveModel, boundary);

        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey.strip());
        }

        Duration reqTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;

        return transport.postMultipart(uri, headers, multipartBody, boundary, reqTimeout)
                .thenApply(response -> handleSttResponse(response, endpoint));
    }

    private static byte[] buildSttMultipartBody(byte[] wavBytes, String model, String boundary) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Part 1: file
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
            out.writeBytes("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(wavBytes);
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));

            // Part 2: model
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.writeBytes(model.getBytes(StandardCharsets.UTF_8));
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));

            // Part 3: prompt
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.writeBytes("Minecraft游戏对话，方块建造与指令：帮我建一个小木屋、造房子、查看周围方块、红石、确认、取消".getBytes(StandardCharsets.UTF_8));
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));

            // End boundary
            out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to assemble multipart body", e);
        }
    }

    private static String handleSttResponse(HttpResponseData response, String endpoint) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            try {
                JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
                if (obj.has("text")) {
                    return obj.get("text").getAsString().strip();
                }
                return "";
            } catch (Exception e) {
                throw new LlmException("解析语音转录结果失败：" + e.getMessage(), e);
            }
        }
        String errorMsg = extractErrorMessage(response.body(), response.statusCode());
        throw new LlmException("STT 请求失败 (HTTP " + response.statusCode() + "): " + errorMsg);
    }

    private static String extractErrorMessage(String body, int statusCode) {
        if (body != null && !body.isBlank()) {
            try {
                JsonElement element = JsonParser.parseString(body);
                if (element.isJsonObject()) {
                    JsonObject obj = element.getAsJsonObject();
                    if (obj.has("error")) {
                        JsonElement errEl = obj.get("error");
                        if (errEl.isJsonObject() && errEl.getAsJsonObject().has("message")) {
                            return errEl.getAsJsonObject().get("message").getAsString();
                        }
                        return errEl.toString();
                    }
                }
            } catch (Exception ignored) {
            }
            if (body.length() <= 200) {
                return body.strip();
            }
            return body.substring(0, 200).strip() + "...";
        }
        return "HTTP 状态码 " + statusCode;
    }

    public static String normalizeAudioBaseUrl(String url) {
        String base = AiConfig.normalizeBaseUrl(url);
        if (base.isEmpty()) {
            return "";
        }
        if (base.equalsIgnoreCase("https://api.openai.com")
                || base.equalsIgnoreCase("https://api.siliconflow.cn")
                || base.equalsIgnoreCase("https://api.groq.com/openai")) {
            return base + "/v1";
        }
        return base;
    }
}
