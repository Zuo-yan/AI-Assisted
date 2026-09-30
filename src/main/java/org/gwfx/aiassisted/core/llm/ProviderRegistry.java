package org.gwfx.aiassisted.core.llm;

import org.gwfx.aiassisted.core.http.HttpTransport;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Provider 工厂注册表：把配置里的 {@code ai.provider} 字符串映射成具体实现。
 *
 * <p>当前支持三种主流大模型接口协议：
 * <ul>
 *   <li>{@code openai-compatible}：Chat Completions 协议 —— 事实上的通用标准，OpenAI 官方端点及
 *       所有兼容端点（DeepSeek / 通义千问 / 智谱 GLM 等）都认它</li>
 *   <li>{@code openai-responses}：OpenAI 官方新版 Responses 协议（gpt-5 / codex 等新模型的主入口）；
 *       与 Chat Completions 是两套报文，第三方兼容服务大多不提供</li>
 *   <li>{@code anthropic}：Anthropic Messages 协议端点（Claude 系列及兼容中转）</li>
 * </ul>
 *
 * <p>未知 id 默认回退到 {@code openai-compatible}。
 */
public final class ProviderRegistry {

    public record ProviderSettings(String baseUrl, String apiKey, Duration timeout, int retryCount) {
    }

    public static List<String> knownIds() {
        return List.of(OpenAiCompatibleProvider.ID, OpenAiResponsesProvider.ID, AnthropicProvider.ID);
    }

    private static final Set<String> KNOWN_IDS = Set.copyOf(knownIds());

    private final Map<String, Function<ProviderSettings, LlmProvider>> factories = new LinkedHashMap<>();
    private final HttpTransport transport;

    public ProviderRegistry(HttpTransport transport) {
        this.transport = transport;
        register(OpenAiCompatibleProvider.ID, settings -> new OpenAiCompatibleProvider(
                this.transport, settings.baseUrl(), settings.apiKey(), settings.timeout()));
        register(OpenAiResponsesProvider.ID, settings -> new OpenAiResponsesProvider(
                this.transport, settings.baseUrl(), settings.apiKey(), settings.timeout()));
        register(AnthropicProvider.ID, settings -> new AnthropicProvider(
                this.transport, settings.baseUrl(), settings.apiKey(), settings.timeout()));
    }

    public void register(String id, Function<ProviderSettings, LlmProvider> factory) {
        factories.put(id, factory);
    }

    public static boolean isKnown(String id) {
        return id != null && KNOWN_IDS.contains(normalize(id));
    }

    public static String normalizeId(String id) {
        String normalized = normalize(id);
        return KNOWN_IDS.contains(normalized) ? normalized : OpenAiCompatibleProvider.ID;
    }

    private static String normalize(String id) {
        return id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    public LlmProvider create(String id, ProviderSettings settings) {
        Function<ProviderSettings, LlmProvider> factory = factories.get(normalizeId(id));
        if (factory == null) {
            factory = factories.get(OpenAiCompatibleProvider.ID);
        }
        return factory.apply(settings);
    }
}
