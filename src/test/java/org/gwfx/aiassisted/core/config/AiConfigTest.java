package org.gwfx.aiassisted.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConfigTest {

    /** 一份「合理」的配置，测试只覆盖需要验证的那一项。 */
    private static AiConfig config(String provider, String baseUrl, double temperature, int maxTokens,
                                   Duration timeout, int retryCount, String model) {
        return new AiConfig(
                true, provider, baseUrl, model, temperature, maxTokens, timeout, retryCount,
                "prompt", false, "ai:", 200, 10, 3, 4,
                16, 8, 16, 5, true, 32, 300, 8, 20, 8000,
                true, 4, 10, 32768, 120, true, 4, 2, false, false, false, 4096, 64,
                true, 100, 15, true, 600);
    }

    private static AiConfig defaultConfig() {
        return config("openai-compatible", "https://api.openai.com/v1", 0.7D, 1024, Duration.ofSeconds(60), 1, "gpt-x");
    }

    // ===== baseUrl 归一化 =====

    @Test
    void stripsTrailingSlashesFromBaseUrl() {
        assertEquals("https://api.example.com/v1",
                config("openai-compatible", "https://api.example.com/v1/", 0.7D, 1, Duration.ofSeconds(1), 0, "m").baseUrl());
        assertEquals("http://localhost:11434",
                config("anthropic", "http://localhost:11434///", 0.7D, 1, Duration.ofSeconds(1), 0, "m").baseUrl());
        assertEquals("https://api.example.com/v1",
                config("openai-compatible", "  https://api.example.com/v1  ", 0.7D, 1, Duration.ofSeconds(1), 0, "m").baseUrl());
    }

    @Test
    void keepsEmptyBaseUrlAsEmpty() {
        assertEquals("", AiConfig.normalizeBaseUrl(""));
        assertEquals("", AiConfig.normalizeBaseUrl(null));
        assertEquals("", AiConfig.normalizeBaseUrl("   "));
    }

    @Test
    void stripsPastedChatCompletionsSuffix() {
        // 玩家常把文档里的完整端点整个粘进来：不剥后缀会拼出 .../chat/completions/chat/completions
        assertEquals("https://7k7kapi.xyz/v1",
                AiConfig.normalizeBaseUrl("https://7k7kapi.xyz/v1/chat/completions"));
        assertEquals("https://api.example.com/v1",
                AiConfig.normalizeBaseUrl("https://api.example.com/v1/chat/completions/"));
        // 别的后缀不剥：/v1 是根地址的一部分，/responses、/v1/messages 由各家 Provider 自己处理
        assertEquals("https://api.openai.com/v1/responses",
                AiConfig.normalizeBaseUrl("https://api.openai.com/v1/responses"));
        assertEquals("https://api.anthropic.com/v1/messages",
                AiConfig.normalizeBaseUrl("https://api.anthropic.com/v1/messages"));
    }

    // ===== 夹紧 =====

    @Test
    void clampsNumericRanges() {
        AiConfig out = config("openai-compatible", "x", 5.0D, 999_999, Duration.ofSeconds(99_999), 99, "m");

        assertEquals(2.0D, out.temperature(), 1e-9);
        assertEquals(32768, out.maxTokens());
        assertEquals(AiConfig.MAX_TIMEOUT, out.timeout());
        assertEquals(5, out.retryCount());
    }

    @Test
    void clampsLowerBounds() {
        AiConfig out = config("openai-compatible", "x", -1.0D, 0, Duration.ofMillis(1), -3, "m");

        assertEquals(0.0D, out.temperature(), 1e-9);
        assertEquals(1, out.maxTokens());
        assertEquals(AiConfig.MIN_TIMEOUT, out.timeout());
        assertEquals(0, out.retryCount());
    }

    @Test
    void fallsBackToMinTimeoutOnNull() {
        assertEquals(AiConfig.MIN_TIMEOUT, config("openai-compatible", "x", 0.7D, 1, null, 0, "m").timeout());
    }

    @Test
    void clampsReplyAndHistoryBounds() {
        AiConfig out = new AiConfig(
                true, "openai-compatible", "x", "m", 0.7D, 100, Duration.ofSeconds(30), 1,
                "p", false, "ai:", 1, 0, -5, 0,
                0, -1, 0, -1, true, 0, -1, -1, 0, 0,
                true, 0, 0, 0, 0, true, 4, 2, false, false, false, 4096, 64,
                true, 0, 999, true, -5);

        assertEquals(40, out.replyChunkSize());
        assertEquals(1, out.replyIntervalTicks());
        assertEquals(0, out.requestCooldownSeconds());
        assertEquals(1, out.maxConcurrentRequests());
        assertEquals(1, out.entityRadius());
        assertEquals(0, out.entityLimit());
        assertEquals(1, out.containerRadius());
        assertEquals(0, out.containerLimit());
        assertEquals(1, out.structureRadiusChunks());
        assertEquals(0, out.structureCacheSeconds());
        assertEquals(0, out.inventoryTopN());
        assertEquals(2, out.historyMaxMessages());
        assertEquals(200, out.historyMaxChars());
        // 工具调用：步数与扫描预算都不能被夹到 0（否则工具调用与查询直接失效）
        assertEquals(1, out.toolMaxSteps());
        assertEquals(1, out.toolMaxResults());
        assertEquals(1024, out.toolMaxScanBlocks());
        assertEquals(1, out.toolLoopTimeoutSeconds());
        // 长期记忆：条数上限有下界 10（低于它功能就没意义了），注入条数 0 是合法值
        assertEquals(10, out.memoryMaxEntries());
        assertEquals(50, out.memoryInjectCount());
        // 问候冷却：负数夹到 0（每次进服都问候）
        assertEquals(0, out.greetingCooldownSeconds());
    }

    @Test
    void keepsMemoryToggleAsIs() {
        AiConfig off = new AiConfig(
                true, "openai-compatible", "x", "m", 0.7D, 100, Duration.ofSeconds(30), 1,
                "p", false, "ai:", 200, 10, 3, 4,
                16, 8, 16, 5, true, 32, 300, 8, 20, 8000,
                true, 4, 10, 32768, 120, true, 4, 2, false, false, false, 4096, 64,
                false, 100, 15, true, 600);
        assertFalse(off.memoryEnabled());

        AiConfig on = config("openai-compatible", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "m");
        assertTrue(on.memoryEnabled());
    }

    @Test
    void clampsToolUpperBounds() {
        AiConfig out = new AiConfig(
                true, "openai-compatible", "x", "m", 0.7D, 100, Duration.ofSeconds(30), 1,
                "p", false, "ai:", 200, 10, 3, 4,
                16, 8, 16, 5, true, 32, 300, 8, 20, 8000,
                true, 999, 999, Integer.MAX_VALUE, 9999, false, 4, 2, false, false, false, 4096, 64,
                true, 999, 999, true, 99999);

        assertEquals(16, out.toolMaxSteps());
        assertEquals(64, out.toolMaxResults());
        assertEquals(1_048_576, out.toolMaxScanBlocks());
        assertEquals(600, out.toolLoopTimeoutSeconds());
        // 问候冷却上限一天：再长就失去"冷却"的意义了
        assertEquals(86400, out.greetingCooldownSeconds());
    }

    @Test
    void keepsToolCallingToggleAsIs() {
        AiConfig out = new AiConfig(
                true, "openai-compatible", "x", "m", 0.7D, 100, Duration.ofSeconds(30), 1,
                "p", false, "ai:", 200, 10, 3, 4,
                16, 8, 16, 5, true, 32, 300, 8, 20, 8000,
                false, 4, 10, 32768, 120, true, 4, 2, false, false, false, 4096, 64,
                true, 100, 15, true, 600);

        assertFalse(out.toolCallingEnabled());
    }

    @Test
    void keepsContainerReadToggleAsIs() {
        // 容器可读是玩家自己拍的板（默认开），AiConfig 只负责如实传递，不做任何"更安全"的改写
        AiConfig on = config("openai-compatible", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "m");
        assertTrue(on.containersReadContents());

        AiConfig off = new AiConfig(
                true, "openai-compatible", "x", "m", 0.7D, 100, Duration.ofSeconds(30), 1,
                "p", false, "ai:", 200, 10, 3, 4,
                16, 8, 16, 5, true, 32, 300, 8, 20, 8000,
                true, 4, 10, 32768, 120, false, 4, 2, false, false, false, 4096, 64,
                true, 100, 15, true, 600);
        assertFalse(off.containersReadContents());
    }

    // ===== Provider 解析 =====

    @Test
    void effectiveProviderFallsBackToMockButKeepsConfiguredValue() {
        AiConfig out = config("Gemini", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "m");

        // 实际生效 mock，但配置值原样保留，便于 /ai status 告诉用户「你填的是什么」
        assertEquals("openai-compatible", out.effectiveProvider());
        assertEquals("Gemini", out.provider());
    }

    @Test
    void effectiveProviderNormalizesCase() {
        assertEquals("anthropic", config("  Anthropic ", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "m").effectiveProvider());
    }

    @Test
    void openAiAndAnthropicRequireApiKey() {
        assertTrue(config("openai-compatible", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "m").requiresApiKey());
        assertTrue(config("anthropic", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "m").requiresApiKey());
    }

    @Test
    void blankProviderFallsBackToMockWithoutWarning() {
        assertEquals("openai-compatible", config("   ", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "m").effectiveProvider());
    }

    @Test
    void hasModelReflectsBlankInput() {
        assertTrue(defaultConfig().hasModel());
        assertFalse(config("openai-compatible", "x", 0.7D, 1, Duration.ofSeconds(1), 0, "   ").hasModel());
    }
}

