package org.gwfx.aiassisted.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.gwfx.aiassisted.AiAssistedMod;

/**
 * AI 配置界面的按键（默认 <b>O</b>）。
 *
 * <p>1.20.1 的按键分类是纯字符串（26.3 是 {@code KeyMapping.Category} 对象），
 * 分类名本身就是一个语言文件键，见 {@link #CATEGORY}。
 * 注册在 MOD 总线（{@link RegisterKeyMappingsEvent}）；按下判定在 {@link AiClientEvents}。
 */
@Mod.EventBusSubscriber(modid = AiAssistedMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AiConfigKeyHandler {

    /** 自定义按键分类（控制界面的分组标题），语言键 {@code key.categories.ai_assisted}。 */
    public static final String CATEGORY = "key.categories.ai_assisted";

    public static final KeyMapping OPEN_AI_CONFIG =
            new KeyMapping("key.ai_assisted.open_ai_config", InputConstants.Type.KEYSYM,
                    InputConstants.KEY_O, CATEGORY);

    private AiConfigKeyHandler() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_AI_CONFIG);
    }
}
