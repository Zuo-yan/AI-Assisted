package org.gwfx.aiassisted;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.gwfx.aiassisted.net.AiPacketHandler;
import org.slf4j.Logger;

@Mod(AiAssistedMod.MODID)
public final class AiAssistedMod {

    public static final String MODID = "ai_assisted";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AiAssistedMod(FMLJavaModLoadingContext context) {
        LOGGER.info("[AiAssistedMod] Initializing AI-Assisted Mod");
        // 47.4.x 里 ModLoadingContext.get() 标了废弃（但仍是 1.20.1 唯一的注册入口，只是警告）
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "ai_assisted-common.toml");

        // 网络通道必须在构造期注册（1.20.1 没有 26.3 的 payload 注册事件）
        AiPacketHandler.register();

        context.getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[AiAssistedMod] Common setup done");
    }
}
