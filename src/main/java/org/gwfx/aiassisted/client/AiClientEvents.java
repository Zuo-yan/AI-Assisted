package org.gwfx.aiassisted.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.client.voice.AiVoiceClientManager;
import org.gwfx.aiassisted.net.AiPacketHandler;

/**
 * 客户端每 tick 事件与 HUD 浮层渲染（FORGE 游戏总线）。
 */
@Mod.EventBusSubscriber(modid = AiAssistedMod.MODID, value = Dist.CLIENT)
public final class AiClientEvents {

    private static boolean wasVoiceKeyDown = false;

    private AiClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            wasVoiceKeyDown = false;
            AiVoiceClientManager.get().cancelRecording();
            return;
        }

        if (minecraft.screen != null) {
            if (wasVoiceKeyDown) {
                wasVoiceKeyDown = false;
                AiVoiceClientManager.get().cancelRecording();
            }
            return;
        }

        while (AiConfigKeyHandler.OPEN_AI_CONFIG.consumeClick()) {
            AiConfigClientData.clear();
            AiPacketHandler.sendRequestAiConfig();
            minecraft.setScreen(new AiConfigScreen());
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
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (!VanillaGuiOverlay.CHAT_PANEL.id().equals(event.getOverlay().id())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        int screenWidth = event.getWindow().getGuiScaledWidth();
        int screenHeight = event.getWindow().getGuiScaledHeight();

        if (AiVoiceClientManager.get().isRecording()) {
            boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
            String dot = blink ? "§c● " : "§8● ";
            String text = dot + "§f正在录音... 松开完成输入";
            int textWidth = font.width(text);
            int x = (screenWidth - textWidth) / 2;
            int y = screenHeight - 65;
            graphics.fill(x - 4, y - 2, x + textWidth + 4, y + 10, 0x88000000);
            graphics.drawString(font, text, x, y, 0xFFFFFF);
        }
    }
}
