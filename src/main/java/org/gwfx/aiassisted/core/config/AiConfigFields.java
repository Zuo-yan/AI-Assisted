package org.gwfx.aiassisted.core.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 图形化配置界面的<b>字段表</b>：AI 全部可编辑配置项的唯一定义处（零 MC 依赖，可直接单测）。
 *
 * <p><b>为什么要有这张表</b>：界面要配置 30 多项，而每一项都要在四个地方出现 ——
 * 界面控件、更新请求校验、快照下发、写回 ModConfigSpec。手写四遍必然漏，
 * 且"界面显示 0~2、配置里夹到别的范围"这类不一致正是由此而来。
 * 把字段定义收敛到这一张表后，加一项配置 = 表里加一行 + 语言文件加两条。
 *
 * <p><b>键名三处同名</b>：这里的 {@code key} 既是 TOML 键、也是界面字段名与网络包字段名。
 * 不另立一套命名，排查时「界面这一项 ↔ 配置文件哪一行」一目了然。
 *
 * <p>⚠️ {@code min}/{@code max} <b>必须与 {@code Config} 里 {@code defineInRange} 的取值范围一致</b>：
 * 界面按这里的范围夹紧，配置层按那里的范围再夹一次。两边若不同步，玩家就会遇到
 * 「界面接受了、落盘却变了」或反之。改任一边时请同时改另一边。
 */
public final class AiConfigFields {

    /** 取值类型：界面按它选控件（开关 / 输入框 / 滑条），校验按它决定怎么解析。 */
    public enum Kind {
        BOOL,
        INT,
        DOUBLE,
        TEXT
    }

    /**
     * 界面分组：{@link #BASE} 是主界面，其余按声明顺序成为「更多设置」里的分页。
     *
     * <p>分页而不是滚动：一页最多 8 项，两列排得下，也不必处理滚轮与控件裁剪。
     */
    public enum Group {
        BASE,
        REQUEST,
        TOOL,
        BUILD,
        CONTEXT,
        HISTORY,
        MEMORY,
        GREETING;

        /** 组标题的语言文件键。 */
        public String labelKey() {
            return "ai.ai_assisted.gui.group." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 一个可编辑字段。
     *
     * @param key            TOML 键 = 界面字段名 = 网络包字段名
     * @param kind           取值类型
     * @param group          所属界面分组
     * @param min            数值下界（{@link Kind#INT}/{@link Kind#DOUBLE} 才有意义）
     * @param max            数值上界
     * @param parseErrorKey  解析失败时回给界面的本地化键
     */
    public record Field(String key, Kind kind, Group group, double min, double max, String parseErrorKey) {

        /** 界面标签的语言文件键。 */
        public String labelKey() {
            return "ai.ai_assisted.gui.field." + key;
        }

        /**
         * 界面说明（灰字，画在控件下方）的语言文件键。
         *
         * <p>每项都必须有：这些配置光看名字看不出取值含义与代价（"工具往返步数上限"是费用上限、
         * "村庄搜索半径"影响服务端卡顿），没有说明玩家只能去翻文档。
         */
        public String descriptionKey() {
            return "ai.ai_assisted.gui.field." + key + ".desc";
        }

        /** 是否数值型（决定要不要提示取值范围、要不要夹紧）。 */
        public boolean numeric() {
            return kind == Kind.INT || kind == Kind.DOUBLE;
        }

        /** 取值范围的语言文件参数（工具提示用）；非数值型返回空。 */
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
            // ===== 主界面：连得上、看得见的最小集合 =====
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
            // 管理权限门槛（原「更多设置」末页整组只有这一项，挪到主界面补空位）。
            // 放在界面里意味着"能改权限的人可以把门槛降到把自己锁在外面之前"，所以默认 4
            num("ai.permission.adminLevel", Kind.INT, Group.BASE, 0, 4),

            // ===== 更多设置 · 请求与回复 =====
            num("ai.timeoutSeconds", Kind.INT, Group.REQUEST, 1, 600),
            num("ai.retryCount", Kind.INT, Group.REQUEST, 0, 5),
            // ⚠️ ai.systemPrompt 刻意<b>不在表里</b>：它是抑制模型编造坐标/物品的那道护栏，
            // 界面上一个单行输入框很容易被误清空，而清空后 AI 就开始编——这种"静默变坏"
            // 比"改不了"糟糕得多。它只留在 TOML 里（README/配置注释都指向那一行）。
            num("ai.replyChunkSize", Kind.INT, Group.REQUEST, 40, 1000),
            num("ai.replyIntervalTicks", Kind.INT, Group.REQUEST, 1, 200),
            num("ai.requestCooldownSeconds", Kind.INT, Group.REQUEST, 0, 300),
            num("ai.maxConcurrentRequests", Kind.INT, Group.REQUEST, 1, 64),

            // ===== 更多设置 · 工具调用（每一项都是费用或主线程工作量的上限）=====
            num("ai.toolMaxSteps", Kind.INT, Group.TOOL, 1, 16),
            num("ai.toolMaxResults", Kind.INT, Group.TOOL, 1, 64),
            num("ai.toolMaxScanBlocks", Kind.INT, Group.TOOL, 1024, 1048576),
            num("ai.toolLoopTimeoutSeconds", Kind.INT, Group.TOOL, 1, 600),
            // 管理员级工具门槛（T002）。与 ai.permission.adminLevel 是两个旋钮，见 Config 的注释
            num("ai.tool.adminLevel", Kind.INT, Group.TOOL, 0, 4),
            // 危险级总开关。默认 false；打开后才注册 propose_command
            bool("ai.tool.dangerousEnabled", Group.TOOL),

            // ===== 更多设置 · AI 建造（T001-6）=====
            // 刻意不塞进 Group.TOOL：那页讲的是"模型能查什么"，这里讲的是"模型能不能改世界"，两类关心点
            bool("ai.build.enabled", Group.BUILD),
            bool("ai.build.allowNonNaturalTerrain", Group.BUILD),
            num("ai.build.maxBlocks", Kind.INT, Group.BUILD, 1, 32768),
            num("ai.build.blocksPerTick", Kind.INT, Group.BUILD, 1, 512),

            // ===== 更多设置 · 上下文采集 =====
            num("ai.context.entityRadius", Kind.INT, Group.CONTEXT, 1, 128),
            num("ai.context.entityLimit", Kind.INT, Group.CONTEXT, 0, 64),
            num("ai.context.containerRadius", Kind.INT, Group.CONTEXT, 1, 128),
            num("ai.context.containerLimit", Kind.INT, Group.CONTEXT, 0, 64),
            bool("ai.context.structureEnabled", Group.CONTEXT),
            num("ai.context.structureRadiusChunks", Kind.INT, Group.CONTEXT, 1, 200),
            num("ai.context.structureCacheSeconds", Kind.INT, Group.CONTEXT, 0, 86400),
            num("ai.context.inventoryTopN", Kind.INT, Group.CONTEXT, 0, 64),

            // ===== 更多设置 · 对话历史 =====
            num("ai.history.maxMessages", Kind.INT, Group.HISTORY, 2, 200),
            num("ai.history.maxChars", Kind.INT, Group.HISTORY, 200, 200_000),

            // ===== 更多设置 · 长期记忆（T001-8）=====
            bool("ai.memory.enabled", Group.MEMORY),
            num("ai.memory.maxEntries", Kind.INT, Group.MEMORY, 10, 500),
            num("ai.memory.injectCount", Kind.INT, Group.MEMORY, 0, 50),

            // ===== 更多设置 · 进服问候 =====
            bool("ai.greeting.enabled", Group.GREETING),
            num("ai.greeting.cooldownSeconds", Kind.INT, Group.GREETING, 0, 86400)
    );

    private AiConfigFields() {
    }

    /** 全部字段，按界面顺序。 */
    public static List<Field> all() {
        return ALL;
    }

    /** 某个分组下的字段，保持声明顺序（也就是界面上的行序）。 */
    public static List<Field> byGroup(Group group) {
        return ALL.stream().filter(field -> field.group() == group).toList();
    }

    /** 「更多设置」里的分组，按声明顺序；不含 {@link Group#BASE}。 */
    public static List<Group> advancedGroups() {
        return List.of(Group.REQUEST, Group.TOOL, Group.BUILD, Group.CONTEXT, Group.HISTORY, Group.MEMORY,
                Group.GREETING);
    }

    /** 按键名查字段；未知键返回空（调用方据此拒绝伪造/版本错配的包）。 */
    public static Optional<Field> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(field -> field.key().equals(key)).findFirst();
    }

    // ===== 当前值 → 界面文本 =====

    /** 全表投影：字段键 → 规范文本值。界面打开时拿它填控件，等价于"服务端现在是什么"。 */
    public static Map<String, String> toTextMap(AiConfig config) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : ALL) {
            values.put(field.key(), textOf(config, field.key()));
        }
        return values;
    }

    /**
     * 把一个字段的当前值渲染成规范文本（数值型也走文本：界面里它们本来就是输入框内容，
     * 统一成文本省掉"显示几位小数"的第二套规则）。
     *
     * <p>刻意 {@code default -> throw} 而不是返回空串：表里加了行却忘了在这里补投影，
     * 应当在第一次打开界面时就炸出来，而不是安静地显示为空白、让人以为配置丢了。
     * {@code AiConfigFieldsTest} 会遍历全表把这件事测出来。
     */
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
            // 超时在运行时是 Duration，回到界面要还原成秒 —— 单位必须与字段名一致，否则玩家会填错量级
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

    // ===== 表行构造助手（让上面的表读起来像一份清单，而不是一堆构造调用）=====
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

