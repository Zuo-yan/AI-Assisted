package org.gwfx.aiassisted.core.llm;

import org.gwfx.aiassisted.core.http.HttpResponseData;
import org.gwfx.aiassisted.core.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryTest {

    private static final HttpTransport NOOP_TRANSPORT = (uri, headers, body, timeout) ->
            CompletableFuture.completedFuture(new HttpResponseData(200, "{}"));

    private static ProviderRegistry.ProviderSettings settings() {
        return new ProviderRegistry.ProviderSettings("http://localhost", "", Duration.ofSeconds(5), 1);
    }

    @Test
    void recognizesKnownIdsCaseInsensitively() {
        assertTrue(ProviderRegistry.isKnown("openai-compatible"));
        assertTrue(ProviderRegistry.isKnown("  OpenAI-Compatible  "));
        assertTrue(ProviderRegistry.isKnown("anthropic"));
        assertTrue(ProviderRegistry.isKnown("  Anthropic  "));
        assertFalse(ProviderRegistry.isKnown("mock"));
        assertFalse(ProviderRegistry.isKnown("ollama"));
        assertFalse(ProviderRegistry.isKnown("gemini"));
        assertFalse(ProviderRegistry.isKnown(""));
        assertFalse(ProviderRegistry.isKnown(null));
    }

    @Test
    void fallsBackToOpenAiCompatibleForUnknownOrBlankIds() {
        assertEquals(OpenAiCompatibleProvider.ID, ProviderRegistry.normalizeId("gemini"));
        assertEquals(OpenAiCompatibleProvider.ID, ProviderRegistry.normalizeId(""));
        assertEquals(OpenAiCompatibleProvider.ID, ProviderRegistry.normalizeId(null));
        assertEquals(AnthropicProvider.ID, ProviderRegistry.normalizeId("ANTHROPIC"));
    }

    @Test
    void knownIdsAreStableAndComplete() {
        assertEquals(java.util.List.of("openai-compatible", "anthropic"), ProviderRegistry.knownIds());
        for (String id : ProviderRegistry.knownIds()) {
            assertTrue(ProviderRegistry.isKnown(id), id + " 应被 isKnown 认可");
        }
    }

    @Test
    void createsFallbackProviderForUnknownIdInsteadOfThrowing() {
        ProviderRegistry registry = new ProviderRegistry(NOOP_TRANSPORT);

        assertInstanceOf(OpenAiCompatibleProvider.class, registry.create("gemini", settings()));
        assertInstanceOf(OpenAiCompatibleProvider.class, registry.create("openai-compatible", settings()));
        assertInstanceOf(AnthropicProvider.class, registry.create("anthropic", settings()));
    }
}