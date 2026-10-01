package org.gwfx.aiassisted.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.gwfx.aiassisted.AiAssistedMod;

/**
 * AI 按键注册（默认 <b>O</b> 打开配置界面、<b>V</b> 按住说话）。
 */
@EventBusSubscriber(modid = AiAssistedMod.MODID, value = Dist.CLIENT)
public final class AiConfigKeyHandler {

    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(AiAssistedMod.MODID, "general"));

    public static final KeyMapping OPEN_AI_CONFIG =
            new KeyMapping("key.ai_assisted.open_ai_config", InputConstants.KEY_O, CATEGORY);

    public static final KeyMapping VOICE_PTT =
            new KeyMapping("key.ai_assisted.voice_ptt", InputConstants.KEY_V, CATEGORY);

    private AiConfigKeyHandler() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_AI_CONFIG);
        event.register(VOICE_PTT);
    }
}
