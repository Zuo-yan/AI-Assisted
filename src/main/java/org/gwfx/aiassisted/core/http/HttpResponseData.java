package org.gwfx.aiassisted.core.http;

/**
 * 一次 HTTP 响应的最小载体。
 *
 * <p>刻意不区分「成功/失败」以外的语义：非 2xx <b>不抛异常</b>而是原样返回，
 * 由 Provider 决定如何把状态码翻译成面向玩家的话术（例如 401 → 提示 Key 无效）。
 */
public record HttpResponseData(int statusCode, String body) {

    public HttpResponseData {
        body = body == null ? "" : body;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
