package org.gwfx.aiassisted.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * AI 回复的客户端显示入口。
 *
 * <p>只做一件事：把服务端下发的一页文本落到本地聊天栏。
 * 之所以单独开一个类而不是写在 payload 里，是因为 payload 是双端类 ——
 * common 代码一旦直接引用 {@code net.minecraft.client.*}，
 * 专用服务端会在<b>字节码校验期</b>就 {@code NoClassDefFoundError: Screen} 直接崩模组
 * （本仓库踩过的硬坑，见任务文档「关键设计决策 6」）。
 *
 * <p>将来要做真正的 AI 聊天界面（T001-9 之后），改这里的落点即可，
 * 服务端与 payload 都不用动。
 */
public final class AiChatClient {

    private AiChatClient() {
    }

    /** 在本地聊天栏显示一条 AI 消息。 */
    public static void display(Component text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            // 玩家还没进世界（例如刚断线），丢弃即可，不需要报错
            return;
        }
        // 26.3 的 LocalPlayer 上没有 displayClientMessage(Component, boolean)，
        // 走聊天组件本身：文本确实来自服务端，因此用 addServerSystemMessage
        minecraft.gui.hud.getChat().addServerSystemMessage(text);
    }
}
