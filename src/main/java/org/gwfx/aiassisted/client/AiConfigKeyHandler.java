package org.gwfx.aiassisted.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.net.AiPacketHandler;

/**
 * AI 配置界面的按键（默认 <b>O</b>）。
 */
@EventBusSubscriber(modid = AiAssistedMod.MODID, value = Dist.CLIENT)
public final class AiConfigKeyHandler {

    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(AiAssistedMod.MODID, "general"));

    public static final KeyMapping OPEN_AI_CONFIG =
            new KeyMapping("key.ai_assisted.open_ai_config", InputConstants.KEY_O, CATEGORY);

    private AiConfigKeyHandler() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_AI_CONFIG);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gui.screen() != null) {
            return;
        }
        if (OPEN_AI_CONFIG.consumeClick()) {
            // 先清掉上一份快照再请求：否则界面会先闪出上一次（可能来自别的服务器）的配置
            AiConfigClientData.clear();
            AiPacketHandler.sendRequestAiConfig();
            minecraft.gui.setScreen(new AiConfigScreen());
        }
    }
}
