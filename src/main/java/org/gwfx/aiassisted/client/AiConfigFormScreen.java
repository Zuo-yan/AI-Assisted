package org.gwfx.aiassisted.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.gwfx.aiassisted.core.config.AiConfigEdits;
import org.gwfx.aiassisted.core.config.AiConfigFields;
import org.gwfx.aiassisted.core.config.AiConfigSnapshot;
import org.gwfx.aiassisted.core.llm.ProviderRegistry;
import org.gwfx.aiassisted.net.AiPacketHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AI 配置界面的公共骨架：主界面与「更多设置」都从这里长出来。
 *
 * <p><b>它只是"遥控器"</b>：所有值都来自服务端下发的 {@link AiConfigSnapshot}，
 * 点「应用」也只是把表单发过去，由服务端校验、写盘、回权威快照。
 * 界面自己从不判定"这个值行不行" —— 那会变成第二套规则。
 *
 * <p><b>布局：每一项 = 控件行 + 说明行</b>。说明是给"一年也未必动一次"的配置用的：
 * 名字看不出取值含义时，玩家只能去翻文档。所以每一项都在控件下方带 1~2 行灰字说明，
 * 说明短了空着、长了截断（不做滚动，MC 的 Screen 里滚动要处理裁剪与点击错位，不划算）。
 *
 * <p><b>装不下就翻页</b>：一屏能放几项由窗口高度实时算出来，放不下就按"分组对齐"分页 ——
 * 同一页尽量属于同一个分组，翻页时不会出现半页上下文采集半页历史的迷惑情况。
 * 页码与翻页按钮在右上角，与底部"应用/完成"这类动作按钮分开，避免误点。
 *
 * <p><b>表单是唯一数据源</b>：控件值实时写回 {@link #form}，翻页或窗口缩放导致的重建
 * 都不会丢编辑。只有"服务端回了新快照"（打开界面、点过应用）才用权威值整体覆盖表单。
 *
 * <p><b>刻意 {@code isPauseScreen() == false}</b>：单机下打开界面若暂停游戏，
 * 集成服务端就不再 tick，我们的请求包永远等不到回应，界面会一直停在「加载中…」。
 * 与 {@code DeathNoteScreen} 的处理一致。
 */
abstract class AiConfigFormScreen extends Screen {

    protected static final int LINE_H = 10;
    /** 顶部信息区起始 y（标题下方）。 */
    protected static final int HEADER_TOP = 26;
    private static final int COL_W = 214;
    /** 标签列宽度。取值要能装下最长的标签（中文 8 字 / 英文 13 字符），否则会压到控件上。 */
    private static final int LABEL_W = 78;
    /** 控件宽度：一列里去掉标签后的剩余宽度。 */
    private static final int CONTROL_W = COL_W - LABEL_W - 4;
    /** 底部按钮宽度；子类（如提示文字的定位）也会用到。 */
    protected static final int BUTTON_W = 100;
    private static final int BUTTON_H = 20;
    private static final int NAV_BUTTON_W = 20;
    /** 每项说明最多几行；两行约 45 个汉字，够写清"这是什么、调大调小会怎样"。 */
    private static final int DESC_LINES = 2;
    /** 控件高度（输入框/按钮）。略小于行高，留出一点呼吸空间。 */
    private static final int CONTROL_H = 14;
    /** 项与项之间的留白，避免说明和下一项的控件贴在一起。 */
    private static final int ITEM_GAP = 5;
    /** 底部留给状态行与按钮的高度（与原布局一致）。 */
    private static final int BOTTOM_RESERVED = 48;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    protected static final int COLOR_LABEL = 0xFFE0E0E0;
    protected static final int COLOR_ERROR = 0xFFFF6B6B;
    protected static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_DESC = 0xFF909090;
    private static final int COLOR_STATUS = 0xFFFFD966;

    /** 表单当前值（字段键 → 文本）。控件的唯一数据源，见类注释。 */
    protected final Map<String, String> form = new LinkedHashMap<>();
    /** 需要按权限整体置灰的控件（只含本界面创建的编辑控件）。 */
    private final List<AbstractWidget> editableWidgets = new ArrayList<>();
    /** 服务端回来的逐字段错误（字段键 → 语言文件键）。 */
    private final Map<String, String> fieldErrors = new LinkedHashMap<>();

    private List<Page> pages = List.of();
    private int pageIndex;
    private int perColumn = 1;
    private int itemsPerPage = 1;
    /** 每项实际保留的说明行数（小窗口下会压到 1 行，见 {@link #layout()}）。 */
    private int descLines = DESC_LINES;

    /** 已渲染过快照的引用：变了就重建控件（首次打开、以及每次「应用」之后）。 */
    private AiConfigSnapshot appliedSnapshot;
    /** 是否在等服务端的应用结果（用于区分"刚打开"与"刚点应用"）。 */
    private boolean awaitingResult;
    /** 本次打开是否只读（无管理权限）。子类据此把"应用"按钮也置灰。 */
    private boolean readOnly;
    private static final int TEST_BTN_W = 60;
    private Button testButton;
    private boolean testingConnection;
    private AiConfigClientData.TestResult lastTestResult;
    /**
     * 测试连接的客户端看门狗：约 10 秒没等到结果包就就地判超时。
     * 服务端 HTTP 请求自身也是 10 秒超时，但 DNS 卡死、结果包丢失这类情况
     * 服务端永远回不了话，客户端不能陪着把「测试中…」挂死。
     */
    private static final int TEST_TIMEOUT_TICKS = 10 * 20;
    private int testTimeoutTicks;
    private Component status = Component.empty();

    // ===== 布局（init 与渲染共用同一份坐标）=====

    protected int leftX;
    protected int rightX;
    protected int rowsTop;
    protected int controlH = CONTROL_H;

    protected AiConfigFormScreen(Component title) {
        super(title);
    }

    // ===== 子类要回答的问题 =====

    /** 本屏涉及的全部字段（按行序；基类负责分页、两列排布与控件创建）。 */
    protected abstract List<AiConfigFields.Field> fields();

    /** 始终排在第一页最前面的行（例如 API Key）；默认没有。 */
    protected List<RowRef> pinnedRows() {
        return List.of();
    }

    /** 底部动作按钮。{@code y} 已算好；用 {@link #bottomButtonX(int)} 排 x。 */
    protected abstract void buildBottomBar(int y);

    /** 顶部信息区占几行 —— 决定可编辑区从哪一行开始。 */
    protected int headerLines() {
        return 0;
    }

    /** 画顶部信息区。 */
    protected void drawHeader(GuiGraphicsExtractor graphics, AiConfigSnapshot snapshot) {
    }

    /** 提交前往请求里追加本屏特有的内容（例如 API Key）；默认不加。 */
    protected void appendExtraPayload(JsonObject raw) {
    }

    // ===== 行与页的模型 =====

    /** 控件工厂：给定坐标造一个控件（基类负责摆放与置灰，工厂只管造）。 */
    @FunctionalInterface
    protected interface RowFactory {
        AbstractWidget create(int x, int y);
    }

    /**
     * 一行：一个标签 + 一个控件 + 一段说明。
     *
     * @param labelKey  标签的语言文件键
     * @param descKey   说明的语言文件键；null 表示这一行没有说明
     * @param errorKey  该行对应 {@code fieldErrors} 里的键；null 表示这一行不可能有字段级错误
     */
    protected record RowRef(String labelKey, String descKey, String errorKey, RowFactory factory) {
    }

    /** 一页：同属一个分组的一批行。分组只用来给页命名与对齐，不影响提交。 */
    private record Page(List<RowRef> rows, AiConfigFields.Group group) {
    }

    // ===== 生命周期 =====

    @Override
    protected void init() {
        super.init();
        this.editableWidgets.clear();

        AiConfigSnapshot snapshot = AiConfigClientData.snapshot();
        if (snapshot != this.appliedSnapshot) {
            // 首次打开，或服务端刚回了新快照：用权威值整体覆盖表单
            this.appliedSnapshot = snapshot;
            this.form.clear();
            this.fieldErrors.clear();
            if (snapshot != null) {
                this.form.putAll(snapshot.values());
                this.fieldErrors.putAll(snapshot.fieldErrors());
            }
        }
        this.readOnly = snapshot == null || !snapshot.canEdit();

        layout();
        paginate();
        clampPageIndex();
        List<RowRef> pageRows = currentRows();
        for (int i = 0; i < pageRows.size(); i++) {
            pageRows.get(i).factory().create(rowX(i), rowY(i));
        }

        // 结果包只在测试进行中被接受：超时后迟到的那份真实结果直接丢弃，
        // 免得它莫名顶掉超时提示，或被误当成下一次测试的结果
        AiConfigClientData.setTestResultListener(result -> {
            if (this.testingConnection) {
                onTestResult(result);
            }
        });
        buildPageNav();
        buildBottomBar(this.height - 26);

        if (this.readOnly) {
            // 无管理权限：可编辑控件置灰。这里只影响交互，真正的拒改在服务端
            this.editableWidgets.forEach(widget -> widget.active = false);
        }
    }

    private void layout() {
        int totalW = COL_W * 2 + 16;
        this.leftX = Math.max(4, (this.width - totalW) / 2);
        this.rightX = this.leftX + COL_W + 16;
        this.rowsTop = HEADER_TOP + headerLines() * LINE_H + 6;

        // 先按"控件 + 说明 2 行 + 间隔"算能放几行；放不下就把说明压到 1 行，
        // 保证小窗口下每屏仍有 2 项以上 —— 有说明但一屏只有一项反而更难用
        int available = Math.max(0, this.height - BOTTOM_RESERVED - this.rowsTop);
        this.controlH = CONTROL_H;
        this.descLines = DESC_LINES;
        int rowsPerColumn = available / itemHeight(this.descLines);
        // 容差优化：若 4 行所需高度与 available 差距在 12px 以内（常见于 1080p Scale 4 下的微小四舍五入差异），
        // 允许紧凑排入 4 行（即每页 8 个槽位），避免让仅有 7~8 个项的分组孤立拆出单项新页
        if (rowsPerColumn == 3 && available >= 4 * itemHeight(this.descLines) - 12) {
            rowsPerColumn = 4;
        }
        if (rowsPerColumn < 2) {
            this.descLines = 1;
            rowsPerColumn = available / itemHeight(this.descLines);
        }
        rowsPerColumn = Math.max(1, rowsPerColumn);

        this.perColumn = rowsPerColumn;
        this.itemsPerPage = rowsPerColumn * 2;
    }

    private int itemHeight(int descLines) {
        return this.controlH + 1 + descLines * LINE_H + ITEM_GAP;
    }

    /**
     * 分页：装不下就切页，并且尽量让一页只属于一个分组。
     *
     * <p>为什么要对齐分组：分组是这批配置唯一的语义边界，一页里前半是"上下文采集"、
     * 后半是"对话历史"，翻页时玩家会不知道自己翻到哪了。
     */
    private void paginate() {
        List<Page> built = new ArrayList<>();
        List<RowRef> current = new ArrayList<>();
        AiConfigFields.Group currentGroup = null;

        for (RowEntry entry : entries()) {
            boolean groupChanged = entry.group() != currentGroup;
            boolean pageFull = current.size() >= this.itemsPerPage;
            if (!current.isEmpty() && (pageFull || groupChanged)) {
                built.add(new Page(List.copyOf(current), currentGroup));
                current.clear();
            }
            currentGroup = entry.group();
            current.add(entry.row());
        }
        if (!current.isEmpty()) {
            built.add(new Page(List.copyOf(current), currentGroup));
        }
        this.pages = List.copyOf(built);
    }

    /** 行 + 所属分组。固定行归到第一个字段所在的分组，这样它会落在第一页。 */
    private record RowEntry(RowRef row, AiConfigFields.Group group) {
    }

    private List<RowEntry> entries() {
        List<AiConfigFields.Field> screenFields = fields();
        AiConfigFields.Group firstGroup = screenFields.isEmpty()
                ? AiConfigFields.Group.BASE
                : screenFields.get(0).group();

        List<RowEntry> entries = new ArrayList<>();
        pinnedRows().forEach(row -> entries.add(new RowEntry(row, firstGroup)));
        for (AiConfigFields.Field field : screenFields) {
            entries.add(new RowEntry(new RowRef(field.labelKey(), field.descriptionKey(), field.key(),
                    (x, y) -> createFieldWidget(field, x, y)), field.group()));
        }
        return entries;
    }

    private void clampPageIndex() {
        if (this.pageIndex >= this.pages.size()) {
            this.pageIndex = Math.max(0, this.pages.size() - 1);
        }
    }

    private List<RowRef> currentRows() {
        return this.pages.isEmpty() ? List.of() : this.pages.get(this.pageIndex).rows();
    }

    /** 当前页所属分组（用于标题）；空页返回空。 */
    protected Optional<AiConfigFields.Group> currentGroup() {
        return this.pages.isEmpty() ? Optional.empty() : Optional.ofNullable(this.pages.get(this.pageIndex).group());
    }

    protected int pageIndex() {
        return this.pageIndex;
    }

    protected int pageCount() {
        return Math.max(1, this.pages.size());
    }

    /** 翻页；首尾相接，省掉"到头了按钮变灰"的状态处理。 */
    protected void turnPage(int delta) {
        if (this.pages.size() <= 1) {
            return;
        }
        this.pageIndex = Math.floorMod(this.pageIndex + delta, this.pages.size());
        // 只重建控件：已改但未提交的内容留在 form 里，翻回来还在
        rebuildWidgets();
    }

    private int currentLeftCount() {
        int total = currentRows().size();
        return Math.max(1, Math.min(this.perColumn, (total + 1) / 2));
    }

    private boolean isLeftColumn(int index) {
        return index < currentLeftCount();
    }

    private int rowX(int index) {
        return (isLeftColumn(index) ? this.leftX : this.rightX) + LABEL_W + 4;
    }

    /** 标签的 x：与控件同列，但贴着列首（控件让出 LABEL_W 的宽度给标签）。 */
    private int labelX(int index) {
        return isLeftColumn(index) ? this.leftX : this.rightX;
    }

    private int rowY(int index) {
        int leftCount = currentLeftCount();
        int row = isLeftColumn(index) ? index : index - leftCount;
        return this.rowsTop + row * itemHeight(this.descLines);
    }

    private int descY(int index) {
        return rowY(index) + this.controlH + 1;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.testingConnection && --this.testTimeoutTicks <= 0) {
            onTestTimeout();
        }
        AiConfigSnapshot current = AiConfigClientData.snapshot();
        if (current != this.appliedSnapshot) {
            onSnapshotChanged(current);
            // 重建控件让表单跟上权威值；init() 里会发现快照变了并重载表单
            rebuildWidgets();
        }
    }

    private void onSnapshotChanged(AiConfigSnapshot snapshot) {
        if (!this.awaitingResult) {
            this.status = Component.empty();
            return;
        }
        this.awaitingResult = false;
        if (snapshot == null) {
            this.status = Component.translatable("ai.ai_assisted.gui.status.failed");
        } else if (!snapshot.error().isEmpty()) {
            this.status = Component.translatable(snapshot.error());
        } else if (!snapshot.fieldErrors().isEmpty()) {
            // 字段错误由 drawStatus 专门绘制红字，此处清空避免重复绘制两行相同警告
            this.status = Component.empty();
        } else if (snapshot.adjusted()) {
            this.status = Component.translatable("ai.ai_assisted.gui.status.adjusted");
        } else {
            this.status = Component.translatable("ai.ai_assisted.gui.status.applied");
        }
    }

    /**
     * 提交本屏表单。
     *
     * <p><b>发的是本屏全部字段（不只当前页）</b>：翻页只是显示问题，玩家点「应用」时
     * 期待的是"这一屏改的都生效"。服务端是「出现即改写」语义，没出现的字段保持原值。
     */
    protected void submit() {
        JsonObject raw = new JsonObject();
        for (AiConfigFields.Field field : fields()) {
            String text = this.form.get(field.key());
            if (text != null) {
                putTyped(raw, field, text);
            }
        }
        appendExtraPayload(raw);
        if (raw.size() == 0) {
            // 服务端也会拒绝空包，不如就地提示，省掉一次无意义的往返
            this.status = Component.translatable("ai.ai_assisted.gui.status.nothing_to_apply");
            return;
        }

        this.awaitingResult = true;
        this.status = Component.translatable("ai.ai_assisted.gui.applying");
        AiPacketHandler.sendUpdateAiConfig(raw.toString());
    }

    /** 按字段类型放进 JSON：布尔要真的是布尔，服务端不认字符串形式的 "true"。 */
    private static void putTyped(JsonObject raw, AiConfigFields.Field field, String text) {
        if (field.kind() == AiConfigFields.Kind.BOOL) {
            raw.addProperty(field.key(), Boolean.parseBoolean(text));
        } else {
            raw.addProperty(field.key(), text);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, COLOR_TITLE);
        drawPageIndicator(graphics);

        AiConfigSnapshot snapshot = AiConfigClientData.snapshot();
        if (snapshot == null) {
            graphics.text(this.font, Component.translatable("ai.ai_assisted.gui.loading"),
                    this.leftX, HEADER_TOP, COLOR_DIM);
        } else {
            drawHeader(graphics, snapshot);
        }

        drawRows(graphics);
        drawStatus(graphics);
    }

    /** 每一项画三样：左侧标签、下方说明、（校验失败时）红色标签。 */
    private void drawRows(GuiGraphicsExtractor graphics) {
        List<RowRef> pageRows = currentRows();
        for (int i = 0; i < pageRows.size(); i++) {
            RowRef row = pageRows.get(i);
            String errorKey = row.errorKey() == null ? null : this.fieldErrors.get(row.errorKey());
            int labelY = rowY(i) + Math.max(0, (this.controlH - 9) / 2 + 1);
            graphics.text(this.font, Component.translatable(row.labelKey()),
                    labelX(i), labelY, errorKey == null ? COLOR_LABEL : COLOR_ERROR);

            if (row.descKey() != null) {
                drawDescription(graphics, row.descKey(), labelX(i), descY(i));
            }
        }
    }

    /**
     * 说明文字：按列宽自动折行，最多 {@link #DESC_LINES} 行，超出部分直接截断。
     *
     * <p>用 {@code font.split} 而不是自己按字数切：中英混排下按字符数切会切在半个词上，
     * 而 split 走的是 MC 自己的断行规则（含 CJK 逐字断行）。
     */
    private void drawDescription(GuiGraphicsExtractor graphics, String descKey, int x, int y) {
        List<FormattedCharSequence> lines = this.font.split(Component.translatable(descKey), COL_W);
        for (int line = 0; line < Math.min(lines.size(), this.descLines); line++) {
            graphics.text(this.font, lines.get(line), x, y + line * LINE_H, COLOR_DESC);
        }
        if (lines.size() > this.descLines) {
            // 说明被截断了就加个省略号，免得看起来像是写漏了半句
            int lastY = y + (this.descLines - 1) * LINE_H;
            graphics.text(this.font, Component.literal("…"), x + COL_W - 5, lastY, COLOR_DESC);
        }
    }

    private void drawStatus(GuiGraphicsExtractor graphics) {
        int y = this.height - 44;
        if (!this.fieldErrors.isEmpty()) {
            // 逐字段错误在标签上已经标红，这里给一句汇总，避免玩家以为"点了没反应"
            graphics.text(this.font, Component.translatable("ai.ai_assisted.gui.status.rejected"),
                    this.leftX, y, COLOR_ERROR);
            y += LINE_H;
        }
        if (this.status.getString().isEmpty()) {
            return;
        }
        if (!this.fieldErrors.isEmpty() &&
                this.status.getString().equals(Component.translatable("ai.ai_assisted.gui.status.rejected").getString())) {
            return;
        }
        graphics.text(this.font, this.status, this.leftX, y, COLOR_STATUS);
    }

    @Override
    public void onClose() {
        AiConfigClientData.setTestResultListener(null);
        super.onClose();
    }

    public boolean isPauseScreen() {
        // 单机下暂停会停掉集成服务端，我们的请求往返就永远回不来（见类注释）
        return false;
    }

    // ===== 翻页控件（右上角，与底部动作按钮分开）=====

    private void buildPageNav() {
        int navY = 4;
        int rightEdge = contentRight();
        int testX;
        if (this.pages.size() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> turnPage(-1))
                    .bounds(rightEdge - NAV_BUTTON_W * 2 - 4, navY, NAV_BUTTON_W, BUTTON_H).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> turnPage(1))
                    .bounds(rightEdge - NAV_BUTTON_W, navY, NAV_BUTTON_W, BUTTON_H).build());
            Component pageText = Component.translatable("ai.ai_assisted.gui.page", this.pageIndex + 1, this.pages.size());
            int textW = this.font.width(pageText);
            testX = rightEdge - (NAV_BUTTON_W * 2 + 4 + 8 + textW + 10) - TEST_BTN_W;
        } else {
            testX = rightEdge - TEST_BTN_W;
        }
        Component btnText = Component.translatable(this.testingConnection ? "ai.ai_assisted.gui.testing" : "ai.ai_assisted.gui.test_connection");
        this.testButton = Button.builder(btnText, b -> testConnection())
                .bounds(testX, navY, TEST_BTN_W, BUTTON_H).build();
        this.testButton.active = !this.testingConnection && !this.readOnly;
        if (this.lastTestResult != null) {
            this.testButton.setTooltip(Tooltip.create(Component.literal(
                    (this.lastTestResult.success() ? "✓ " : "✕ ") + this.lastTestResult.message())));
        }
        addRenderableWidget(this.testButton);
    }

    private void testConnection() {
        this.testingConnection = true;
        this.testTimeoutTicks = TEST_TIMEOUT_TICKS;
        if (this.testButton != null) {
            this.testButton.active = false;
            this.testButton.setMessage(Component.translatable("ai.ai_assisted.gui.testing"));
        }
        this.status = Component.translatable("ai.ai_assisted.gui.testing");
        JsonObject raw = new JsonObject();
        for (AiConfigFields.Field field : fields()) {
            String text = this.form.get(field.key());
            if (text != null) {
                raw.addProperty(field.key(), text);
            }
        }
        appendExtraPayload(raw);
        AiPacketHandler.sendTestAiConnection(raw.toString());
    }

    private void onTestResult(AiConfigClientData.TestResult result) {
        this.testingConnection = false;
        this.testTimeoutTicks = 0;
        this.lastTestResult = result;
        if (this.testButton != null) {
            this.testButton.active = !this.readOnly;
            this.testButton.setMessage(Component.translatable("ai.ai_assisted.gui.test_connection"));
            this.testButton.setTooltip(Tooltip.create(Component.literal(
                    (result.success() ? "✓ " : "✕ ") + result.message())));
        }
        if (result.success()) {
            this.status = Component.translatable("ai.ai_assisted.gui.test_success", result.latencyMs());
        } else {
            this.status = Component.translatable("ai.ai_assisted.gui.test_failed", result.message());
        }
    }

    /** 测试超时：就地下结论、归还按钮，不让「测试中…」永远挂着。 */
    private void onTestTimeout() {
        this.testingConnection = false;
        this.testTimeoutTicks = 0;
        if (this.testButton != null) {
            this.testButton.active = !this.readOnly;
            this.testButton.setMessage(Component.translatable("ai.ai_assisted.gui.test_connection"));
        }
        this.status = Component.translatable("ai.ai_assisted.gui.test_timeout");
    }


    /** 页码文字与翻页按钮一起画在右上角。 */
    private void drawPageIndicator(GuiGraphicsExtractor graphics) {
        if (this.pages.size() <= 1) {
            return;
        }
        Component text = Component.translatable("ai.ai_assisted.gui.page", this.pageIndex + 1, this.pages.size());
        int rightEdge = contentRight();
        graphics.text(this.font, text,
                rightEdge - NAV_BUTTON_W * 2 - 8 - this.font.width(text), 9, COLOR_DIM);
    }

    /** 单行灰字（子类画顶部信息区时用）。超宽会被截断，不会盖到右边那一列。 */
    protected void drawDimLine(GuiGraphicsExtractor graphics, Component text, int y) {
        graphics.text(this.font, this.font.plainSubstrByWidth(text.getString(), COL_W * 2 + 16),
                this.leftX, y, COLOR_DIM);
    }

    // ===== 控件工厂（子类复用）=====

    /** 字段表里的一项 → 控件：开关用按钮、温度用滑条、其余用输入框。 */
    protected AbstractWidget createFieldWidget(AiConfigFields.Field field, int x, int y) {
        // Provider 是枚举：用循环按钮让玩家点着选，避免拼错一个字母就跑不通
        if (AiConfigEdits.KEY_PROVIDER.equals(field.key())) {
            return addEditable(providerButton(x, y));
        }
        return switch (field.kind()) {
            case BOOL -> addEditable(toggleButton(x, y, field.key()));
            case DOUBLE -> addEditable(new FieldSlider(x, y, field));
            case TEXT -> addEditable(fieldBox(x, y, 1024, field));
            case INT -> {
                EditBox box = addEditable(fieldBox(x, y, 32, field));
                // 取值范围写进工具提示：界面必须让玩家知道会被夹到什么区间，否则"填了 9999 变成 32768"很莫名
                field.rangeText().ifPresent(range ->
                        box.setTooltip(Tooltip.create(Component.translatable("ai.ai_assisted.gui.range", range))));
                yield box;
            }
        };
    }

    protected <T extends AbstractWidget> T addEditable(T widget) {
        this.editableWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    /** 表单当前值（缺省空串）。 */
    protected String formOf(String key) {
        return this.form.getOrDefault(key, "");
    }

    protected void setForm(String key, String value) {
        this.form.put(key, value);
    }

    /** 造一个双向绑定到某字段的输入框（输入即写回表单，翻页也不丢）。 */
    protected EditBox fieldBox(int x, int y, int maxLength, AiConfigFields.Field field) {
        return boundBox(x, y, maxLength, formOf(field.key()), text -> {
            setForm(field.key(), text);
            this.fieldErrors.remove(field.key());
        });
    }

    /** 造一个不绑定字段的输入框（例如一次性输入的 API Key）。 */
    protected EditBox boundBox(int x, int y, int maxLength, String value,
                               java.util.function.Consumer<String> responder) {
        EditBox box = new EditBox(this.font, x, y, CONTROL_W, this.controlH, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setResponder(responder);
        return box;
    }

    private Button providerButton(int x, int y) {
        Button button = Button.builder(Component.literal(currentProvider()), b -> {
            String next = nextProvider(currentProvider());
            setForm(AiConfigEdits.KEY_PROVIDER, next);
            b.setMessage(Component.literal(next));
            b.setTooltip(Tooltip.create(providerTooltip(next)));
        }).bounds(x, y, CONTROL_W, this.controlH).build();
        button.setTooltip(Tooltip.create(providerTooltip(currentProvider())));
        return button;
    }

    /**
     * 协议名看不出报文差异，逐条说明放悬浮提示：不受字段说明「最多两行截断」的限制，
     * 键名直接拼 provider id（{@code desc.openai-compatible} 等），新增协议只需补一条语言条目。
     */
    private static Component providerTooltip(String providerId) {
        return Component.translatable("ai.ai_assisted.gui.provider.desc." + providerId);
    }

    private String currentProvider() {
        String current = formOf(AiConfigEdits.KEY_PROVIDER);
        return current.isEmpty() ? org.gwfx.aiassisted.core.llm.OpenAiCompatibleProvider.ID : current;
    }

    private static String nextProvider(String current) {
        List<String> known = ProviderRegistry.knownIds();
        int index = known.indexOf(current);
        return known.get((index + 1) % known.size());
    }

    private Button toggleButton(int x, int y, String key) {
        return Button.builder(toggleMessage(Boolean.parseBoolean(formOf(key))), b -> {
            boolean next = !Boolean.parseBoolean(formOf(key));
            setForm(key, Boolean.toString(next));
            b.setMessage(toggleMessage(next));
        }).bounds(x, y, CONTROL_W, this.controlH).build();
    }

    protected static Component toggleMessage(boolean on) {
        return Component.translatable(on ? "ai.ai_assisted.gui.on" : "ai.ai_assisted.gui.off");
    }

    /** 底部按钮的 x：从左往右第 index 个（0 起）。 */
    protected int bottomButtonX(int index) {
        return this.leftX + index * (BUTTON_W + 8);
    }

    /** 内容区右缘（右列控件的右边界）；右上角翻页与右对齐的底部按钮都以它为锚。 */
    protected int contentRight() {
        return this.rightX + COL_W;
    }

    /** 右对齐的底部按钮：x 贴内容区右缘，放「更多设置」这类次要入口。 */
    protected Button bottomButtonRight(String labelKey, int y, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable(labelKey), b -> action.run())
                .bounds(contentRight() - BUTTON_W, y, BUTTON_W, BUTTON_H).build());
    }

    /** 控件宽度（额外行自建控件时用）。 */
    protected int controlWidth() {
        return CONTROL_W;
    }

    /** 本次打开是否只读：无管理权限时为 true。 */
    protected boolean readOnly() {
        return this.readOnly;
    }

    /**
     * 造一个底部按钮。
     *
     * <p>刻意不自动参与"只读置灰"：应用按钮该灰、完成与翻页不该灰，
     * 这个区分只有子类知道，所以由子类按 {@link #readOnly()} 自行决定。
     */
    protected Button bottomButton(String labelKey, int index, int y, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable(labelKey), b -> action.run())
                .bounds(bottomButtonX(index), y, BUTTON_W, BUTTON_H).build());
    }

    /** 数值型字段的滑条；值实时写回表单。 */
    private final class FieldSlider extends AbstractSliderButton {

        private final AiConfigFields.Field field;

        private FieldSlider(int x, int y, AiConfigFields.Field field) {
            super(x, y, CONTROL_W, AiConfigFormScreen.this.controlH, Component.empty(),
                    toSlider(field, parse(formOf(field.key()), field.min())));
            this.field = field;
            updateMessage();
            setForm(field.key(), format(current()));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(format(current())));
        }

        @Override
        protected void applyValue() {
            updateMessage();
            setForm(this.field.key(), format(current()));
        }

        private double current() {
            return this.field.min() + this.value * (this.field.max() - this.field.min());
        }

        /** 与旧界面一致的显示精度：温度这类连续量保留一位小数。 */
        private String format(double value) {
            return String.format(Locale.ROOT, "%.1f", value);
        }

        private static double parse(String text, double fallback) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static double toSlider(AiConfigFields.Field field, double value) {
            double span = field.max() - field.min();
            if (span <= 0) {
                return 0.0D;
            }
            return Math.max(0.0D, Math.min(1.0D, (value - field.min()) / span));
        }
    }
}
