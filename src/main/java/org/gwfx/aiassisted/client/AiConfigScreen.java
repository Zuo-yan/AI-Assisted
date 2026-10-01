package org.gwfx.aiassisted.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.gwfx.aiassisted.core.config.AiConfigEdits;
import org.gwfx.aiassisted.core.config.AiConfigFields;
import org.gwfx.aiassisted.core.config.AiConfigSnapshot;

import java.util.List;
import java.util.Locale;

/**
 * AI 配置主界面（默认按 O 打开）：常用项 + 一个跳「更多设置」的入口。
 *
 * <p><b>为什么分两块</b>：AI 配置有 30 多项，其中大部分（超时、重试、上下文半径、扫描预算…）
 * 装好服务器之后一年也未必动一次。所以主界面只留"连得上、看得见"的常用项，
 * 其余进「更多设置」。两块共用 {@link AiConfigFormScreen} 的分页、渲染与提交，
 * 也共用服务端的同一套校验。
 *
 * <p><b>无管理权限时只读</b>：所有编辑控件与「应用」按钮置灰，顶部提示需要权限；
 * 但模型、地址、温度、Key 来源照常展示 —— 玩家最常问的就是"服务端到底配没配好"。
 * 判定在服务端（配置 {@code ai.permission.adminLevel}），客户端只拿结果、不自己算。
 *
 * <p><b>密钥框是"只写"框</b>：值一路只向上走（客户端 → 服务端 → KeyStore），
 * 显示时打掩码、不写进快照、服务端也不回显；<b>留空表示不改动现有密钥</b>
 * （清除请用 {@code /ai key clear}）。它被固定在<b>第一页最前面</b>，
 * 免得"想换密钥"要先翻几页去找。
 */
public class AiConfigScreen extends AiConfigFormScreen {

    /** 顶部三行：生效 Provider / Key 来源与文件 / 只读提示。 */
    private static final int HEADER_LINES = 3;

    /** API Key 输入框：只写（服务端从不回传密钥，因此不存在"初值"可回填）。 */
    private EditBox apiKeyBox;

    /**
     * 密钥草稿：翻页/缩放窗口都会重建全部控件，不在这里暂存的话，
     * 玩家刚输入的密钥会被一个新空框静默顶掉（留空=不改动，等于白输）。
     * 只活在本屏内存里，关闭即丢弃；不会写进快照，也不会出现在日志。
     */
    private String apiKeyDraft = "";

    public AiConfigScreen() {
        super(Component.translatable("ai.ai_assisted.gui.title"));
    }

    @Override
    protected List<AiConfigFields.Field> fields() {
        return AiConfigFields.byGroup(AiConfigFields.Group.BASE);
    }

    @Override
    protected List<RowRef> pinnedRows() {
        // 密钥不是 TOML 配置项，没有"当前值"可填，因此不走字段表，固定成第一页的第一行
        return List.of(new RowRef("ai.ai_assisted.gui.api_key", "ai.ai_assisted.gui.api_key_desc",
                null, this::createApiKeyBox));
    }

    @Override
    protected int headerLines() {
        return HEADER_LINES;
    }

    @Override
    protected void drawHeader(GuiGraphicsExtractor graphics, AiConfigSnapshot snapshot) {
        int y = HEADER_TOP;
        String configured = snapshot.value(AiConfigEdits.KEY_PROVIDER);
        drawDimLine(graphics, Component.translatable("ai.ai_assisted.gui.effective_provider",
                configured.isEmpty() ? "(空)" : configured, snapshot.effectiveProvider()), y);
        y += LINE_H;

        // 来源与文件路径合成一行：既省一行留给配置项，也避免长路径单独成行时溢出到右列
        String file = snapshot.keyFilePath().isEmpty() ? "" : snapshot.keyFilePath();
        String displayFile = compactPath(file, 220);
        drawDimLine(graphics, Component.translatable(
                file.isEmpty() ? "ai.ai_assisted.gui.key_source" : "ai.ai_assisted.gui.key_source_file",
                Component.translatable(keySourceKey(snapshot.keySource())), displayFile), y);
        y += LINE_H;

        if (readOnly()) {
            graphics.text(this.font, Component.translatable("ai.ai_assisted.gui.read_only"),
                    this.leftX, y, COLOR_ERROR);
        }
    }

    @Override
    protected void buildBottomBar(int y) {
        Button apply = bottomButton("ai.ai_assisted.gui.apply", 0, y, this::submit);
        bottomButton("ai.ai_assisted.gui.done", 1, y, this::onClose);
        // 「更多设置」右置、与左侧动作拉开：它是另一份表单的入口，不是"应用后继续"，
        // 贴在一起容易让人以为左边的「应用」会顺带保存那边的改动
        bottomButtonRight("ai.ai_assisted.gui.more", y, this::openAdvanced);
        // 只读时"应用"置灰：服务端一定会拒，与其让玩家点了收到一句报错，不如直接表明不可改
        apply.active = !readOnly();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawMoreHint(graphics);
    }

    /**
     * 「更多设置」按钮左侧的提示：那边是独立的另一份表单，本屏的「应用」不带它，
     * 改完必须在那边单独点「应用」。与按钮同行、右对齐贴着按钮左缘；
     * 空间只有「完成」到按钮之间的空档，文案要短，超宽按剩余宽度截断。
     */
    private void drawMoreHint(GuiGraphicsExtractor graphics) {
        Component hint = Component.translatable("ai.ai_assisted.gui.more_hint");
        int moreX = contentRight() - BUTTON_W;
        int maxW = moreX - 6 - (bottomButtonX(1) + BUTTON_W + 8);
        String text = this.font.plainSubstrByWidth(hint.getString(), Math.max(0, maxW)).strip();
        if (text.isEmpty()) {
            return;
        }
        // 与按钮行同一水平线（按钮 y = height-26，见基类 init），垂直居中于 20px 高的按钮
        graphics.text(this.font, text, moreX - 6 - this.font.width(text),
                this.height - 26 + 6, COLOR_DIM);
    }

    @Override
    protected void appendExtraPayload(JsonObject raw) {
        String apiKey = this.apiKeyBox == null ? "" : this.apiKeyBox.getValue().strip();
        if (!apiKey.isEmpty()) {
            raw.addProperty(AiConfigEdits.KEY_API_KEY, apiKey);
        }
    }

    private void openAdvanced() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(new AiAdvancedScreen(this));
        }
    }

    private AbstractWidget createApiKeyBox(int x, int y) {
        // 初值取草稿：控件重建（翻页/缩放）后玩家已输入的密钥不会丢
        EditBox box = boundBox(x, y, 256, this.apiKeyDraft, text -> this.apiKeyDraft = text);
        // 掩码：按输入长度画等长的 *，屏幕上不出现密钥本体（防录屏/截图时顺手泄露）
        // 刻意不设 setHint：这一行下面是完整的灰字说明，输入框里再挂一条长提示会把框塞满
        box.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        this.apiKeyBox = box;
        return addEditable(box);
    }

    private String compactPath(String path, int maxPixelWidth) {
        if (path == null || path.isEmpty() || this.font.width(path) <= maxPixelWidth) {
            return path;
        }
        int len = path.length();
        int head = 14;
        int tail = 22;
        if (len <= head + tail + 3) {
            return path;
        }
        return path.substring(0, head) + "..." + path.substring(len - tail);
    }

    private static String keySourceKey(String source) {
        String suffix = source == null || source.isEmpty() ? "none" : source.toLowerCase(Locale.ROOT);
        return "ai.ai_assisted.key_source." + suffix;
    }
}
