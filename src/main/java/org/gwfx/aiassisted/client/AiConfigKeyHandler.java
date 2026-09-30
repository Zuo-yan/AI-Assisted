package org.gwfx.aiassisted.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.gwfx.aiassisted.AiAssistedMod;

/**
 * AI 按键注册（MOD 总线）。
 */
@Mod.EventBusSubscriber(modid = AiAssistedMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AiConfigKeyHandler {

    /** 自定义按键分类，语言键 {@code key.categories.ai_assisted}。 */
    public static final String CATEGORY = "key.categories.ai_assisted";

    public static final KeyMapping OPEN_AI_CONFIG =
            new KeyMapping("key.ai_assisted.open_ai_config", InputConstants.Type.KEYSYM,
                    InputConstants.KEY_O, CATEGORY);

    public static final KeyMapping VOICE_PTT =
            new KeyMapping("key.ai_assisted.voice_ptt", InputConstants.Type.KEYSYM,
                    InputConstants.KEY_V, CATEGORY);

    private AiConfigKeyHandler() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_AI_CONFIG);
        event.register(VOICE_PTT);
    }
}
