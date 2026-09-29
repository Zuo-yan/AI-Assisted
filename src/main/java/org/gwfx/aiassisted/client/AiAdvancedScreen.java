package org.gwfx.aiassisted.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.gwfx.aiassisted.core.config.AiConfigFields;
import org.gwfx.aiassisted.core.config.AiConfigSnapshot;

import java.util.List;

/**
 * 「更多设置」：主界面之外的 AI 配置，跨分组分页浏览。
 *
 * <p><b>为什么是分页而不是滚动列表</b>：MC 的 Screen 里做滚动要自己处理控件裁剪、
 * 滚轮事件与点击区域错位，收益却只是"少按两次按钮"。分页由基类按窗口高度实时算每页几项，
 * 一页放不下就把该分组拆成多页，窗口拉大则自动合并。
 *
 * <p><b>分组在这里只是"页的标题"</b>：分页时按分组对齐（一页尽量只属于一个分组），
 * 所以玩家始终知道自己在看哪一类配置；翻页与分组切换合成同一个动作 ——
 * 上一页/下一页按钮在右上角，底部只有"应用/返回主页"。
 *
 * <p><b>与主界面共用一切</b>：渲染、提交、错误高亮、只读置灰都在
 * {@link AiConfigFormScreen} 里，这里只回答"显示哪些字段"和"底部有哪些按钮"，
 * 编辑到一半的内容在翻页时不会丢。
 */
public class AiAdvancedScreen extends AiConfigFormScreen {

    /** 顶部两行：分组标题 / 只读提示。 */
    private static final int HEADER_LINES = 2;

    /** 从哪个界面进来的；"返回"与 ESC 都回到它，而不是直接回游戏。 */
    private final AiConfigScreen parent;

    public AiAdvancedScreen(AiConfigScreen parent) {
        super(Component.translatable("ai.ai_assisted.gui.advanced.title"));
        this.parent = parent;
    }

    @Override
    protected List<AiConfigFields.Field> fields() {
        // 主界面已经放过的（BASE 组）不再重复；其余按表里的顺序，基类按分组对齐分页
        return AiConfigFields.all().stream()
                .filter(field -> field.group() != AiConfigFields.Group.BASE)
                .toList();
    }

    @Override
    protected int headerLines() {
        return HEADER_LINES;
    }

    @Override
    protected void drawHeader(GuiGraphicsExtractor graphics, AiConfigSnapshot snapshot) {
        String group = currentGroup()
                .map(found -> Component.translatable(found.labelKey()).getString())
                .orElse("");
        drawDimLine(graphics, Component.translatable("ai.ai_assisted.gui.advanced.header", group), HEADER_TOP);

        if (readOnly()) {
            graphics.text(this.font, Component.translatable("ai.ai_assisted.gui.read_only"),
                    this.leftX, HEADER_TOP + LINE_H, COLOR_ERROR);
        }
    }

    @Override
    protected void buildBottomBar(int y) {
        Button apply = bottomButton("ai.ai_assisted.gui.apply", 0, y, this::submit);
        bottomButton("ai.ai_assisted.gui.back", 1, y, this::onClose);
        apply.active = !readOnly();
    }

    @Override
    public void onClose() {
        // ESC 与"返回"都回主界面：两页本来就是同一份配置的两半，直接退出游戏会显得莫名
        // （26.3 的开屏入口在 Minecraft.gui 上，见 DeathNoteScreen 的同款写法）
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }
}
