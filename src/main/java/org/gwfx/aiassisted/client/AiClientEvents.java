package org.gwfx.aiassisted.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.gwfx.aiassisted.AiAssistedMod;
import org.gwfx.aiassisted.net.AiPacketHandler;

/**
 * 客户端每 tick 的按键判定（FORGE 游戏总线；1.20.1 的 ClientTickEvent 不在 MOD 总线上，
 * 与 {@link AiConfigKeyHandler} 的按键注册分属两个总线，所以拆成两个类）。
 */
@Mod.EventBusSubscriber(modid = AiAssistedMod.MODID, value = Dist.CLIENT)
public final class AiClientEvents {

    private AiClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        while (AiConfigKeyHandler.OPEN_AI_CONFIG.consumeClick()) {
            // 先清掉上一份快照再请求：否则界面会先闪出上一次（可能来自别的服务器）的配置
            AiConfigClientData.clear();
            AiPacketHandler.sendRequestAiConfig();
            minecraft.setScreen(new AiConfigScreen());
        }
    }
}
