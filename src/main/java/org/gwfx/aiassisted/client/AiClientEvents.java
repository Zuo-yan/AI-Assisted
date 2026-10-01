package org.gwfx.aiassisted.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.client.voice.AiVoiceClientManager;
import org.gwfx.aiassisted.net.AiPacketHandler;

/**
 * 客户端每 tick 事件与 HUD 浮层渲染（语音按住说话、录音状态指示）。
 */
@EventBusSubscriber(modid = AiAssistedMod.MODID, value = Dist.CLIENT)
public final class AiClientEvents {

    private static boolean wasVoiceKeyDown = false;

    private AiClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            wasVoiceKeyDown = false;
            AiVoiceClientManager.get().cancelRecording();
            return;
        }

        if (minecraft.gui.screen() != null) {
            if (wasVoiceKeyDown) {
                wasVoiceKeyDown = false;
                AiVoiceClientManager.get().cancelRecording();
            }
            return;
        }

        if (AiConfigKeyHandler.OPEN_AI_CONFIG.consumeClick()) {
            // 先清掉上一份快照再请求：否则界面会先闪出上一次（可能来自别的服务器）的配置
            AiConfigClientData.clear();
            AiPacketHandler.sendRequestAiConfig();
            minecraft.gui.setScreen(new AiConfigScreen());
        }

        // 语音按住说话 (Push-to-Talk) 状态追踪
        boolean isDown = AiConfigKeyHandler.VOICE_PTT.isDown();
        if (isDown && !wasVoiceKeyDown) {
            wasVoiceKeyDown = true;
            AiVoiceClientManager.get().onKeyPressed();
        } else if (!isDown && wasVoiceKeyDown) {
            wasVoiceKeyDown = false;
            AiVoiceClientManager.get().onKeyReleased();
        }
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.CHAT.equals(event.getName())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) {
            return;
        }

        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        if (AiVoiceClientManager.get().isRecording()) {
            boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
            String dot = blink ? "§c● " : "§8● ";
            String text = dot + "§f正在录音... 松开完成输入";
            int textWidth = font.width(text);
            int x = (screenWidth - textWidth) / 2;
            int y = screenHeight - 65;
            graphics.fill(x - 4, y - 2, x + textWidth + 4, y + 10, 0x88000000);
            graphics.text(font, text, x, y, 0xFFFFFF);
        }
    }
}
