package org.gwfx.aiassisted;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(AiAssistedMod.MODID)
public final class AiAssistedMod {

    public static final String MODID = "ai_assisted";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AiAssistedMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[AiAssistedMod] Initializing AI-Assisted Mod");
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "ai_assisted-common.toml");
    }
}
