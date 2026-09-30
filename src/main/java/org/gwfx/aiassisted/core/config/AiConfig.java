package org.gwfx.aiassisted.core.config;

import org.gwfx.aiassisted.core.llm.ProviderRegistry;

import java.time.Duration;

/**
 * AI 配置的<b>不可变快照</b>。
 *
 * <p>刻意<b>不含任何密钥字段</b>：本对象会被 {@code /ai status} 回显、也可能进日志，
 * 密钥只由 {@code ProviderRegistry.ProviderSettings} 随 Provider 构造单独注入。
 */
public record AiConfig(
        boolean enabled,
        String provider,
        String baseUrl,
        String model,
        double temperature,
        int maxTokens,
        Duration timeout,
        int retryCount,
        String systemPrompt,
        boolean chatPrefixEnabled,
        String chatPrefix,
        int replyChunkSize,
        int replyIntervalTicks,
        int requestCooldownSeconds,
        int maxConcurrentRequests,
        int entityRadius,
        int entityLimit,
        int containerRadius,
        int containerLimit,
        boolean structureEnabled,
        int structureRadiusChunks,
        int structureCacheSeconds,
        int inventoryTopN,
        int historyMaxMessages,
        int historyMaxChars,
        boolean toolCallingEnabled,
        int toolMaxSteps,
        int toolMaxResults,
        int toolMaxScanBlocks,
        int toolLoopTimeoutSeconds,
        boolean containersReadContents,
        int adminLevel,
        int toolAdminLevel,
        boolean dangerousToolsEnabled,
        boolean buildEnabled,
        boolean buildAllowNonNaturalTerrain,
        int buildMaxBlocks,
        int buildBlocksPerTick,
        boolean memoryEnabled,
        int memoryMaxEntries,
        int memoryInjectCount,
        boolean greetingEnabled,
        int greetingCooldownSeconds) {

    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    public static final double MIN_TEMPERATURE = 0.0D;
    public static final double MAX_TEMPERATURE = 2.0D;
    public static final int MIN_MAX_TOKENS = 1;
    public static final int MAX_MAX_TOKENS = 32768;

    public static double clampTemperature(double value) {
        return clamp(value, MIN_TEMPERATURE, MAX_TEMPERATURE);
    }

    public static int clampMaxTokens(int value) {
        return clamp(value, MIN_MAX_TOKENS, MAX_MAX_TOKENS);
    }

    public AiConfig {
        provider = provider == null ? "" : provider.strip();
        baseUrl = normalizeBaseUrl(baseUrl);
        model = model == null ? "" : model.strip();
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        chatPrefix = chatPrefix == null ? "" : chatPrefix;

        temperature = clampTemperature(temperature);
        maxTokens = clampMaxTokens(maxTokens);
        timeout = clampTimeout(timeout);
        retryCount = clamp(retryCount, 0, 5);
        replyChunkSize = clamp(replyChunkSize, 40, 1000);
        replyIntervalTicks = clamp(replyIntervalTicks, 1, 200);
        requestCooldownSeconds = clamp(requestCooldownSeconds, 0, 300);
        maxConcurrentRequests = clamp(maxConcurrentRequests, 1, 64);

        entityRadius = clamp(entityRadius, 1, 128);
        entityLimit = clamp(entityLimit, 0, 64);
        containerRadius = clamp(containerRadius, 1, 128);
        containerLimit = clamp(containerLimit, 0, 64);
        structureRadiusChunks = clamp(structureRadiusChunks, 1, 200);
        structureCacheSeconds = clamp(structureCacheSeconds, 0, 86400);
        inventoryTopN = clamp(inventoryTopN, 0, 64);

        historyMaxMessages = clamp(historyMaxMessages, 2, 200);
        historyMaxChars = clamp(historyMaxChars, 200, 200_000);

        toolMaxSteps = clamp(toolMaxSteps, 1, 16);
        toolMaxResults = clamp(toolMaxResults, 1, 64);
        toolMaxScanBlocks = clamp(toolMaxScanBlocks, 1024, 1_048_576);
        toolLoopTimeoutSeconds = clamp(toolLoopTimeoutSeconds, 1, 600);

        adminLevel = clamp(adminLevel, 0, 4);
        toolAdminLevel = clamp(toolAdminLevel, 0, 4);
        buildMaxBlocks = clamp(buildMaxBlocks, 1, 32768);
        buildBlocksPerTick = clamp(buildBlocksPerTick, 1, 512);

        memoryMaxEntries = clamp(memoryMaxEntries, 10, 500);
        memoryInjectCount = clamp(memoryInjectCount, 0, 50);

        greetingCooldownSeconds = clamp(greetingCooldownSeconds, 0, 86400);
    }

    public String effectiveProvider() {
        return ProviderRegistry.normalizeId(provider);
    }

    public boolean requiresApiKey() {
        return "openai-compatible".equals(effectiveProvider()) || "anthropic".equals(effectiveProvider());
    }

    public boolean hasModel() {
        return !model.isEmpty();
    }

    public static String normalizeBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/chat/completions".length());
        }
        return trimmed;
    }

    private static Duration clampTimeout(Duration value) {
        if (value == null) {
            return MIN_TIMEOUT;
        }
        if (value.compareTo(MIN_TIMEOUT) < 0) {
            return MIN_TIMEOUT;
        }
        return value.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
