package org.gwfx.aiassisted.client;

import org.gwfx.aiassisted.core.config.AiConfigSnapshot;
import org.gwfx.aiassisted.net.AiTestConnectionResultPacket;

import java.util.function.Consumer;

/**
 * 客户端侧持有的 AI 配置快照（最近一次从服务端同步来的）。
 */
public final class AiConfigClientData {

    public record TestResult(boolean success, int latencyMs, String message) {
    }

    private static volatile AiConfigSnapshot snapshot;
    private static volatile Consumer<TestResult> testResultListener;

    private AiConfigClientData() {
    }

    /** 写入一份新快照。 */
    public static void apply(String json) {
        snapshot = AiConfigSnapshot.fromJson(json);
    }

    /** 最近一次快照；尚未收到时为 {@code null}（界面显示「加载中…」）。 */
    public static AiConfigSnapshot snapshot() {
        return snapshot;
    }

    public static void setTestResultListener(Consumer<TestResult> listener) {
        testResultListener = listener;
    }

    public static void onTestResult(AiTestConnectionResultPacket packet) {
        Consumer<TestResult> listener = testResultListener;
        if (listener != null) {
            listener.accept(new TestResult(packet.success(), packet.latencyMs(), packet.message()));
        }
    }

    /** 清掉快照。 */
    public static void clear() {
        snapshot = null;
        testResultListener = null;
    }
}