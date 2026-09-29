package org.gwfx.aiassisted.core.config;

import org.gwfx.aiassisted.core.llm.ProviderRegistry;

import java.time.Duration;

/**
 * AI 配置的<b>不可变快照</b>。
 *
 * <p>刻意<b>不含任何密钥字段</b>：本对象会被 {@code /ai status} 回显、也可能进日志，
 * 密钥只由 {@code ProviderRegistry.ProviderSettings} 随 Provider 构造单独注入。
 *
 * <p>构造时统一做归一化与夹紧，保证下游（ContextBuilder / Provider / 分页器）
 * 拿到的值一定是合理范围，不必各自重复校验。
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
        /**
         * 谁能改 AI 配置（0~4，Minecraft 权限等级）。判定见 {@code ai.AiPermissions}。
         *
         * <p>它不影响对话本身的任何行为，但必须在这里 —— 图形化配置界面要能读能写它，
         * 而界面读的正是本对象；没有它，这一项就得在界面/校验/快照三处各开一个特例。
         */
        int adminLevel,
        /**
         * 使用管理员级工具（{@code server_info} / {@code propose_command}）所需的最低权限等级（0~4，默认 2=OP）。
         *
         * <p>与 {@link #adminLevel} 是两个旋钮：那个管"能不能改配置"（影响全服），
         * 这个管"能不能用指令 / 读全服信息"（最多做成本人本来就能做的事）。
         */
        int toolAdminLevel,
        /**
         * 危险级工具总开关（默认 false）。
         *
         * <p>关闭时 {@code propose_command} 不注册 —— 模型看不到它，也就不会去提议执行指令。
         */
        boolean dangerousToolsEnabled,
        /** AI 建造总开关（默认 false）。关闭时 {@code propose_build} 不注册。 */
        boolean buildEnabled,
        /** 是否放权 AI 对建筑/非自然地形的所有操作（允许覆盖/拆除非自然地形）。 */
        boolean buildAllowNonNaturalTerrain,
        /** 单次建造体积上限（包围盒格子数）。 */
        int buildMaxBlocks,
        /** 每 tick 放置预算。 */
        int buildBlocksPerTick) {

    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 温度的允许区间。
     *
     * <p>提成常量是为了让「图形化配置界面」的写入校验与这里的夹紧<b>共用同一份定义</b> ——
     * 否则界面上写 0.0~2.0、这里悄悄夹到别的范围，玩家会以为生效了其实没有。
     */
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

        // 工具调用：步数是费用上限，必须有下界；扫描预算是主线程工作量上限，同样不能为 0
        toolMaxSteps = clamp(toolMaxSteps, 1, 16);
        toolMaxResults = clamp(toolMaxResults, 1, 64);
        toolMaxScanBlocks = clamp(toolMaxScanBlocks, 1024, 1_048_576);
        toolLoopTimeoutSeconds = clamp(toolLoopTimeoutSeconds, 1, 600);

        // 与 Config 的 defineInRange(..., 0, 4) 一致；越界夹紧而不是拒绝，理由同上
        adminLevel = clamp(adminLevel, 0, 4);
        toolAdminLevel = clamp(toolAdminLevel, 0, 4);
        // dangerousToolsEnabled / buildEnabled 是玩家自己拍的板，AiConfig 只如实传递
        buildMaxBlocks = clamp(buildMaxBlocks, 1, 32768);
        buildBlocksPerTick = clamp(buildBlocksPerTick, 1, 512);
    }

    /**
     * 实际生效的 Provider 协议 id（未知值回退 {@code openai-compatible}）。
     *
     * <p>与 {@link #provider()} 分开保留：{@code /ai status} 需要同时显示
     * 「你填的是什么」和「实际用的是什么」，否则用户拼错 provider 时会一脸茫然。
     */
    public String effectiveProvider() {
        return ProviderRegistry.normalizeId(provider);
    }

    /**
     * 当前 Provider 是否必须要有密钥。
     *
     * <p>只有 {@code openai-compatible} 需要 —— 本地部署的 OpenAI 兼容服务常见无鉴权，
     * 因此这里不强制，真需要时由服务端返回 401 再提示。
     */
    public boolean requiresApiKey() {
        return "openai-compatible".equals(effectiveProvider()) || "anthropic".equals(effectiveProvider());
    }

    /** 模型名为空时无法发请求，调用方应先提示用户配置。 */
    public boolean hasModel() {
        return !model.isEmpty();
    }

    /** 去空白、去末尾斜杠。空输入保持为空，交由调用方判断。 */
    public static String normalizeBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
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

