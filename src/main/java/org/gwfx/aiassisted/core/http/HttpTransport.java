package org.gwfx.aiassisted.core.http;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 传输抽象。
 *
 * <p>抽出来只有一个目的：让 {@code ai.core} 的 Provider 单测可以注入一个不发真实请求的假实现，
 * 从而在纯 JVM 环境下验证「请求体怎么拼」「响应怎么解」「错误怎么报」这三件事。
 *
 * <p>实现约定：网络层失败（连接失败 / 超时）以 {@code LlmException} 作为 future 的异常完成；
 * 拿到任何 HTTP 状态码都算「成功完成」，交由调用方判断。
 */
public interface HttpTransport {

    /**
     * 发送一个 JSON POST 请求。
     *
     * @param uri     完整请求地址
     * @param headers 附加请求头（Content-Type 由实现负责保证）
     * @param jsonBody 请求体（UTF-8 编码）
     * @param timeout 单次请求超时
     */
    CompletableFuture<HttpResponseData> postJson(
            URI uri, Map<String, String> headers, String jsonBody, Duration timeout);

    /** 释放底层资源。 */
    default void close() {
    }
}
