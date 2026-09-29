package org.gwfx.aiassisted.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一段长文本切成若干页。
 *
 * <p>为什么必须分页：MC 的单条聊天/系统消息有长度上限，AI 回复很容易超；
 * 硬塞会被截断，一次性连发几十条又会被判定为刷屏。所以按「页」切，
 * 由上层按 tick 间隔逐页下发（见 {@code ReplyDispatcher}）。
 *
 * <p>切点优先级：<b>换行 &gt; 空格 &gt; 硬切</b>，并且只在页尾附近的窗口内找切点，
 * 否则会出现「每页只有几个字」的碎片。纯函数、零 MC 依赖，可单测。
 */
public final class TextPager {

    /** 只在页尾这一段比例内寻找切点，避免把页面切得过碎。 */
    private static final double BREAK_WINDOW_RATIO = 0.5D;

    private TextPager() {
    }

    /**
     * 切页。
     *
     * @param text     原文；null 或空串返回空列表
     * @param pageSize 单页字符上限（小于 1 时按 1 处理）
     * @return 按顺序排列的页面，不含首尾空白
     */
    public static List<String> paginate(String text, int pageSize) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int size = Math.max(1, pageSize);
        List<String> pages = new ArrayList<>();

        String remaining = text;
        while (!remaining.isEmpty()) {
            if (remaining.length() <= size) {
                String last = remaining.strip();
                if (!last.isEmpty()) {
                    pages.add(last);
                }
                break;
            }

            int cut = findBreak(remaining, size);
            if (cut < 1) {
                // 防御：切点必须前进，否则会死循环
                cut = 1;
            }
            String page = remaining.substring(0, cut).strip();
            if (!page.isEmpty()) {
                pages.add(page);
            }
            remaining = remaining.substring(cut).stripLeading();
        }
        return pages;
    }

    /**
     * 在 {@code limit} 附近选一个切点。
     *
     * <p>调用方保证 {@code text.length() > limit >= 1}。
     */
    static int findBreak(String text, int limit) {
        int window = Math.max(1, (int) (limit * BREAK_WINDOW_RATIO));
        int lowest = Math.max(1, limit - window);

        for (int i = limit - 1; i >= lowest; i--) {
            if (text.charAt(i) == '\n') {
                return i;
            }
        }
        for (int i = limit - 1; i >= lowest; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return limit;
    }
}
