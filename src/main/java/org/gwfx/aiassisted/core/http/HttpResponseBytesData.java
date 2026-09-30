package org.gwfx.aiassisted.core.http;

import java.util.Arrays;
import java.util.Objects;

/**
 * 承载 HTTP 二进制字节响应的数据载荷（零 MC 依赖）。
 *
 * <p>供 TTS 音频流拉取等需要原始字节的接口使用。
 */
public record HttpResponseBytesData(int statusCode, byte[] body, String contentType) {

    public HttpResponseBytesData {
        body = body == null ? new byte[0] : body;
        contentType = contentType == null ? "" : contentType;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HttpResponseBytesData that)) return false;
        return statusCode == that.statusCode &&
                Objects.equals(contentType, that.contentType) &&
                Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(statusCode, contentType);
        result = 31 * result + Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "HttpResponseBytesData[statusCode=" + statusCode + ", bytes=" + body.length + ", contentType=" + contentType + "]";
    }
}
