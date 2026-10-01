package org.gwfx.aiassisted.core.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 图形化配置界面的<b>字段表</b>：AI 全部可编辑配置项的唯一定义处（零 MC 依赖，可直接单测）。
 */
public final class AiConfigFields {

    public enum Kind {
        BOOL,
        INT,
        DOUBLE,
        TEXT
    }

    public enum Group {
        BASE,
        REQUEST,
        TOOL,
        BUILD,
        CONTEXT,
        MEMORY;

        public String labelKey() {
            return "ai.ai_assisted.gui.group." + name().toLowerCase(Locale.ROOT);
        }
    }

    public record Field(String key, Kind kind, Group group, double min, double max, String parseErrorKey) {

        public String labelKey() {
            return "ai.ai_assisted.gui.field." + key;
        }

        public String descriptionKey() {
            return "ai.ai_assisted.gui.field." + key + ".desc";
        }

        public boolean numeric() {
            return kind == Kind.INT || kind == Kind.DOUBLE;
        }

        public Optional<String> rangeText() {
            if (!numeric()) {
                return Optional.empty();
            }
            if (kind == Kind.INT) {
                return Optional.of((long) min + " ~ " + (long) max);
            }
            return Optional.of(min + " ~ " + max);
        }
    }

    private static final List<Field> ALL = List.of(
            // ===== 主界面：常用项 + 搬迁合并项 =====
            bool(AiConfigEdits.KEY_ENABLED, Group.BASE),
            text(AiConfigEdits.KEY_PROVIDER, Group.BASE),
            text(AiConfigEdits.KEY_BASE_URL, Group.BASE),
            text(AiConfigEdits.KEY_MODEL, Group.BASE),
            num(AiConfigEdits.KEY_TEMPERATURE, Kind.DOUBLE, Group.BASE, 0.0D, 2.0D,
                    AiConfigEdits.ERROR_TEMPERATURE),
            num(AiConfigEdits.KEY_MAX_TOKENS, Kind.INT, Group.BASE, 1, 32768,
                    AiConfigEdits.ERROR_MAX_TOKENS),
            bool(AiConfigEdits.KEY_CHAT_PREFIX_ENABLED, Group.BASE),
            text(AiConfigEdits.KEY_CHAT_PREFIX, Group.BASE),
            bool(AiConfigEdits.KEY_TOOL_CALLING_ENABLED, Group.BASE),
            bool(AiConfigEdits.KEY_CONTAINERS_READ_CONTENTS, Group.BASE),
            num("ai.permission.adminLevel", Kind.INT, Group.BASE, 0, 4),
            // 从更多设置搬入主界面第三页的小分组配置（历史与进服问候）
            num("ai.history.maxMessages", Kind.INT, Group.BASE, 2, 200),
            num("ai.history.maxChars", Kind.INT, Group.BASE, 200, 200_000),
            bool("ai.greeting.enabled", Group.BASE),
            num("ai.greeting.cooldownSeconds", Kind.INT, Group.BASE, 0, 86400),

            // ===== 更多设置 · 请求与频控（共 7 项）=====
            num("ai.timeoutSeconds", Kind.INT, Group.REQUEST, 1, 600),
            num("ai.retryCount", Kind.INT, Group.REQUEST, 0, 5),
            num("ai.replyChunkSize", Kind.INT, Group.REQUEST, 40, 1000),
            num("ai.replyIntervalTicks", Kind.INT, Group.REQUEST, 1, 200),
            num("ai.requestCooldownSeconds", Kind.INT, Group.REQUEST, 0, 300),
            num("ai.maxConcurrentRequests", Kind.INT, Group.REQUEST, 1, 64),
            num("ai.toolLoopTimeoutSeconds", Kind.INT, Group.REQUEST, 1, 600),

            // ===== 更多设置 · 工具与安全 =====
            num("ai.toolMaxSteps", Kind.INT, Group.TOOL, 1, 16),
            num("ai.toolMaxResults", Kind.INT, Group.TOOL, 1, 64),
            num("ai.toolMaxScanBlocks", Kind.INT, Group.TOOL, 1024, 1_048_576),
            num("ai.tool.adminLevel", Kind.INT, Group.TOOL, 0, 4),
            bool("ai.tool.dangerousEnabled", Group.TOOL),

            // ===== 更多设置 · AI 建造 =====
            bool("ai.build.enabled", Group.BUILD),
            bool("ai.build.allowNonNaturalTerrain", Group.BUILD),
            num("ai.build.maxBlocks", Kind.INT, Group.BUILD, 1, 32768),
            num("ai.build.blocksPerTick", Kind.INT, Group.BUILD, 1, 512),

            // ===== 更多设置 · 环境感知 =====
            num("ai.context.entityRadius", Kind.INT, Group.CONTEXT, 1, 128),
            num("ai.context.entityLimit", Kind.INT, Group.CONTEXT, 0, 64),
            num("ai.context.containerRadius", Kind.INT, Group.CONTEXT, 1, 128),
            num("ai.context.containerLimit", Kind.INT, Group.CONTEXT, 0, 64),
            bool("ai.context.structureEnabled", Group.CONTEXT),
            num("ai.context.structureRadiusChunks", Kind.INT, Group.CONTEXT, 1, 200),
            num("ai.context.structureCacheSeconds", Kind.INT, Group.CONTEXT, 0, 86400),
            num("ai.context.inventoryTopN", Kind.INT, Group.CONTEXT, 0, 64),

            // ===== 更多设置 · 长期记忆 =====
            bool("ai.memory.enabled", Group.MEMORY),
            num("ai.memory.maxEntries", Kind.INT, Group.MEMORY, 10, 500),
            num("ai.memory.injectCount", Kind.INT, Group.MEMORY, 0, 50)
    );

    private static final Map<String, Field> BY_KEY = new LinkedHashMap<>();

    static {
        for (Field field : ALL) {
            BY_KEY.put(field.key(), field);
        }
    }

    private AiConfigFields() {
    }

    public static List<Field> all() {
        return ALL;
    }

    public static Optional<Field> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    public static List<Field> byGroup(Group group) {
        return ALL.stream().filter(f -> f.group() == group).toList();
    }

    public static List<Group> advancedGroups() {
        return List.of(
                Group.REQUEST,
                Group.TOOL,
                Group.BUILD,
                Group.CONTEXT,
                Group.MEMORY);
    }

    public static Map<String, String> toTextMap(AiConfig config) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Field field : ALL) {
            map.put(field.key(), textOf(config, field.key()));
        }
        return map;
    }

    public static String textOf(AiConfig config, String key) {
        return switch (key) {
            case AiConfigEdits.KEY_ENABLED -> Boolean.toString(config.enabled());
            case AiConfigEdits.KEY_PROVIDER -> config.provider();
            case AiConfigEdits.KEY_BASE_URL -> config.baseUrl();
            case AiConfigEdits.KEY_MODEL -> config.model();
            case AiConfigEdits.KEY_TEMPERATURE -> Double.toString(config.temperature());
            case AiConfigEdits.KEY_MAX_TOKENS -> Integer.toString(config.maxTokens());
            case AiConfigEdits.KEY_CHAT_PREFIX_ENABLED -> Boolean.toString(config.chatPrefixEnabled());
            case AiConfigEdits.KEY_CHAT_PREFIX -> config.chatPrefix();
            case AiConfigEdits.KEY_TOOL_CALLING_ENABLED -> Boolean.toString(config.toolCallingEnabled());
            case AiConfigEdits.KEY_CONTAINERS_READ_CONTENTS -> Boolean.toString(config.containersReadContents());
            case "ai.timeoutSeconds" -> Long.toString(config.timeout().toSeconds());
            case "ai.retryCount" -> Integer.toString(config.retryCount());
            case "ai.replyChunkSize" -> Integer.toString(config.replyChunkSize());
            case "ai.replyIntervalTicks" -> Integer.toString(config.replyIntervalTicks());
            case "ai.requestCooldownSeconds" -> Integer.toString(config.requestCooldownSeconds());
            case "ai.maxConcurrentRequests" -> Integer.toString(config.maxConcurrentRequests());
            case "ai.toolMaxSteps" -> Integer.toString(config.toolMaxSteps());
            case "ai.toolMaxResults" -> Integer.toString(config.toolMaxResults());
            case "ai.toolMaxScanBlocks" -> Integer.toString(config.toolMaxScanBlocks());
            case "ai.toolLoopTimeoutSeconds" -> Integer.toString(config.toolLoopTimeoutSeconds());
            case "ai.tool.adminLevel" -> Integer.toString(config.toolAdminLevel());
            case "ai.tool.dangerousEnabled" -> Boolean.toString(config.dangerousToolsEnabled());
            case "ai.build.enabled" -> Boolean.toString(config.buildEnabled());
            case "ai.build.allowNonNaturalTerrain" -> Boolean.toString(config.buildAllowNonNaturalTerrain());
            case "ai.build.maxBlocks" -> Integer.toString(config.buildMaxBlocks());
            case "ai.build.blocksPerTick" -> Integer.toString(config.buildBlocksPerTick());
            case "ai.context.entityRadius" -> Integer.toString(config.entityRadius());
            case "ai.context.entityLimit" -> Integer.toString(config.entityLimit());
            case "ai.context.containerRadius" -> Integer.toString(config.containerRadius());
            case "ai.context.containerLimit" -> Integer.toString(config.containerLimit());
            case "ai.context.structureEnabled" -> Boolean.toString(config.structureEnabled());
            case "ai.context.structureRadiusChunks" -> Integer.toString(config.structureRadiusChunks());
            case "ai.context.structureCacheSeconds" -> Integer.toString(config.structureCacheSeconds());
            case "ai.context.inventoryTopN" -> Integer.toString(config.inventoryTopN());
            case "ai.history.maxMessages" -> Integer.toString(config.historyMaxMessages());
            case "ai.history.maxChars" -> Integer.toString(config.historyMaxChars());
            case "ai.memory.enabled" -> Boolean.toString(config.memoryEnabled());
            case "ai.memory.maxEntries" -> Integer.toString(config.memoryMaxEntries());
            case "ai.memory.injectCount" -> Integer.toString(config.memoryInjectCount());
            case "ai.greeting.enabled" -> Boolean.toString(config.greetingEnabled());
            case "ai.greeting.cooldownSeconds" -> Integer.toString(config.greetingCooldownSeconds());
            case "ai.permission.adminLevel" -> Integer.toString(config.adminLevel());
            default -> throw new IllegalArgumentException("字段表里有未实现投影的键：" + key);
        };
    }

    private static Field bool(String key, Group group) {
        return new Field(key, Kind.BOOL, group, 0, 0, AiConfigEdits.ERROR_INVALID);
    }

    private static Field text(String key, Group group) {
        return new Field(key, Kind.TEXT, group, 0, 0, AiConfigEdits.ERROR_INVALID);
    }

    private static Field num(String key, Kind kind, Group group, double min, double max) {
        return num(key, kind, group, min, max, AiConfigEdits.ERROR_NUMBER);
    }

    private static Field num(String key, Kind kind, Group group, double min, double max, String parseErrorKey) {
        return new Field(key, kind, group, min, max, parseErrorKey);
    }
}
