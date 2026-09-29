package org.gwfx.aiassisted.core.http;

import org.gwfx.aiassisted.core.llm.LlmException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK 内置 {@link HttpClient} 的异步传输实现。
 *
 * <p><b>零第三方依赖</b>：只用 {@code java.net.http} + 虚拟线程，避免为接入 AI 破坏
 * 本仓库惯用的 {@code ./gradlew build --offline}。
 *
 * <p><b>重试策略</b>：只重试「网络层 IOException」与「5xx」。
 * 4xx 一律不重试 —— 那是参数/密钥/额度问题，重试不会变好，只会白烧用户的钱。
 *
 * <p><b>不做任何日志</b>：本类位于 {@code ai.core}，保持零 Minecraft 依赖、可脱离 MC 单测；
 * 需要记日志的地方在 {@code ai.*} 层，并统一过 KeyRedactor。
 */
public final class JdkHttpTransport implements HttpTransport {

    /** 退避基数：第 n 次重试等 500ms * n。 */
    private static final long RETRY_BASE_DELAY_MILLIS = 500L;

    private final HttpClient client;
    private final ExecutorService executor;
    private final int retryCount;

    public JdkHttpTransport(Duration connectTimeout, int retryCount) {
        this.retryCount = Math.max(0, retryCount);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .executor(this.executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public CompletableFuture<HttpResponseData> postJson(
            URI uri, Map<String, String> headers, String jsonBody, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null && !value.isEmpty()) {
                    builder.header(name, value);
                }
            });
        }
        return attempt(builder.build(), timeout, 0);
    }

    /**
     * 第 {@code attempt} 次尝试（从 0 开始）。
     *
     * <p>重试走 {@code retryLater -> attempt(attempt + 1)} 这条独立分支，
     * 不再回流进本层的 handle，因此不会出现「一次失败被两边各重试一遍」的指数放大。
     */
    private CompletableFuture<HttpResponseData> attempt(HttpRequest request, Duration timeout, int attempt) {
        CompletableFuture<HttpResponseData> call;
        try {
            call = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> new HttpResponseData(response.statusCode(), response.body()));
        } catch (RuntimeException e) {
            // sendAsync 同步抛出（例如 header 非法）时不会有 future，直接转成失败结果
            return CompletableFuture.failedFuture(new LlmException("HTTP 请求构造失败：" + messageOf(e), e));
        }

        return call.handle((data, error) -> {
            if (error == null) {
                if (isRetryableStatus(data.statusCode()) && attempt < retryCount) {
                    return retryLater(request, timeout, attempt);
                }
                return CompletableFuture.completedFuture(data);
            }
            if (attempt < retryCount && isRetryableError(error)) {
                return retryLater(request, timeout, attempt);
            }
            return CompletableFuture.<HttpResponseData>failedFuture(translate(error));
        }).thenCompose(inner -> inner);
    }

    private CompletableFuture<HttpResponseData> retryLater(HttpRequest request, Duration timeout, int attempt) {
        long delayMillis = RETRY_BASE_DELAY_MILLIS * (attempt + 1L);
        return CompletableFuture
                .supplyAsync(() -> null, CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor))
                .thenCompose(ignored -> attempt(request, timeout, attempt + 1));
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode >= 500 && statusCode <= 599;
    }

    private static boolean isRetryableError(Throwable error) {
        return unwrap(error) instanceof IOException;
    }

    private static LlmException translate(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof LlmException llm) {
            return llm;
        }
        if (cause instanceof HttpTimeoutException) {
            return new LlmException("请求超时", cause);
        }
        if (cause instanceof IOException) {
            return new LlmException("网络请求失败：" + messageOf(cause), cause);
        }
        return new LlmException("请求失败：" + messageOf(cause), cause);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        // 只关线程池、不调 client.close()：后者会等待在途请求收尾，可能拖住服务端停机。
        executor.shutdown();
    }
}
