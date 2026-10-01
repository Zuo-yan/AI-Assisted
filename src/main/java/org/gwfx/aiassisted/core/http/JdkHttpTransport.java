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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK 内置 {@link HttpClient} 的异步传输实现。
 *
 * <p><b>零第三方依赖</b>：只用 {@code java.net.http}，避免为接入 AI 破坏
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
        // 26.3 目标是 Java 21+：用虚拟线程承载异步请求 —— HTTP 客户端本身是阻塞式 IO，虚拟线程足够轻量
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
        applyHeaders(builder, headers);
        return attempt(builder.build(), timeout, 0);
    }

    @Override
    public CompletableFuture<HttpResponseData> postMultipart(
            URI uri, Map<String, String> headers, byte[] multipartBody, String boundary, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody));
        applyHeaders(builder, headers);
        return attempt(builder.build(), timeout, 0);
    }

    @Override
    public CompletableFuture<HttpResponseBytesData> postJsonBytes(
            URI uri, Map<String, String> headers, String jsonBody, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        applyHeaders(builder, headers);
        return attemptBytes(builder.build(), timeout, 0);
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null && !value.isEmpty()) {
                    builder.header(name, value);
                }
            });
        }
    }

    private CompletableFuture<HttpResponseData> attempt(HttpRequest request, Duration timeout, int attempt) {
        CompletableFuture<HttpResponseData> call;
        try {
            call = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> new HttpResponseData(response.statusCode(), response.body()));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(constructionFailure(e));
        }

        return call.handle((data, error) -> {
            if (error == null) {
                if (isRetryableStatus(data.statusCode()) && attempt < retryCount) {
                    return retryLater(request, timeout, attempt).thenCompose(r -> attempt(r, timeout, attempt + 1));
                }
                return CompletableFuture.completedFuture(data);
            }
            if (attempt < retryCount && isRetryableError(error)) {
                return retryLater(request, timeout, attempt).thenCompose(r -> attempt(r, timeout, attempt + 1));
            }
            return CompletableFuture.<HttpResponseData>failedFuture(translate(error));
        }).thenCompose(inner -> inner);
    }

    private CompletableFuture<HttpResponseBytesData> attemptBytes(HttpRequest request, Duration timeout, int attempt) {
        CompletableFuture<HttpResponseBytesData> call;
        try {
            call = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        String contentType = response.headers().firstValue("content-type").orElse("");
                        return new HttpResponseBytesData(response.statusCode(), response.body(), contentType);
                    });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(constructionFailure(e));
        }

        return call.handle((data, error) -> {
            if (error == null) {
                if (isRetryableStatus(data.statusCode()) && attempt < retryCount) {
                    return retryLater(request, timeout, attempt).thenCompose(r -> attemptBytes(r, timeout, attempt + 1));
                }
                return CompletableFuture.completedFuture(data);
            }
            if (attempt < retryCount && isRetryableError(error)) {
                return retryLater(request, timeout, attempt).thenCompose(r -> attemptBytes(r, timeout, attempt + 1));
            }
            return CompletableFuture.<HttpResponseBytesData>failedFuture(translate(error));
        }).thenCompose(inner -> inner);
    }

    private CompletableFuture<HttpRequest> retryLater(HttpRequest request, Duration timeout, int attempt) {
        long delayMillis = RETRY_BASE_DELAY_MILLIS * (attempt + 1L);
        return CompletableFuture
                .supplyAsync(() -> request, CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor));
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
        if (cause instanceof RejectedExecutionException) {
            return rejectedPool(cause);
        }
        if (cause instanceof HttpTimeoutException) {
            return new LlmException("请求超时", cause);
        }
        if (cause instanceof IOException) {
            return new LlmException("网络请求失败：" + messageOf(cause), cause);
        }
        return new LlmException("请求失败：" + messageOf(cause), cause);
    }

    private static LlmException rejectedPool(Throwable cause) {
        return new LlmException(
                "HTTP 线程池已关闭，无法发送请求（常见于刚退出上一个世界后复用旧连接）。重进世界或重启游戏即可恢复", cause);
    }

    private static LlmException constructionFailure(Throwable error) {
        if (unwrap(error) instanceof RejectedExecutionException) {
            return rejectedPool(error);
        }
        return new LlmException("HTTP 请求构造失败：" + messageOf(error), error);
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
        executor.shutdown();
    }
}
