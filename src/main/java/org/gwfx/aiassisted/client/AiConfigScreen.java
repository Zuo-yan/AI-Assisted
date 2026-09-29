package org.gwfx.aiassisted.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
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

    /** API Key 输入框：只写，每次重建控件都从空开始（服务端从不回传密钥）。 */
    private EditBox apiKeyBox;

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
    protected void drawHeader(GuiGraphics graphics, AiConfigSnapshot snapshot) {
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
            graphics.drawString(this.font, Component.translatable("ai.ai_assisted.gui.read_only"),
                    this.leftX, y, COLOR_ERROR);
        }
    }

    @Override
    protected void buildBottomBar(int y) {
        Button apply = bottomButton("ai.ai_assisted.gui.apply", 0, y, this::submit);
        bottomButton("ai.ai_assisted.gui.done", 1, y, this::onClose);
        bottomButton("ai.ai_assisted.gui.more", 2, y, this::openAdvanced);
        // 只读时"应用"置灰：服务端一定会拒，与其让玩家点了收到一句报错，不如直接表明不可改
        apply.active = !readOnly();
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
            this.minecraft.setScreen(new AiAdvancedScreen(this));
        }
    }

    private AbstractWidget createApiKeyBox(int x, int y) {
        // 密钥框永远从空开始：服务端从不回传密钥，因此不存在"初值"可填
        EditBox box = boundBox(x, y, 256, "", text -> {
        });
        // 掩码：按输入长度画等长的 *，屏幕上不出现密钥本体（防录屏/截图时顺手泄露）
        // 刻意不设 setHint：这一行下面是完整的灰字说明，输入框里再挂一条长提示会把框塞满
        box.setFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
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
