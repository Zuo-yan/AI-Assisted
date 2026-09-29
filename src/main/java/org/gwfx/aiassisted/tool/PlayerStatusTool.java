package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 工具 {@code player_status}：玩家的自身状态（血量、饥饿、经验、状态标记、药水效果）。
 *
 * <p>补的缺口：{@code ContextSnapshot} 只描述<b>外部世界</b>（位置/天气/附近/背包），
 * 不含玩家自身状态。「我血量多少」「我中了什么 debuff」原本无从回答。
 *
 * <p><b>隐私取舍</b>：只读发起者<b>自己</b>的状态 —— 施动者与观察对象是同一人，
 * 不存在把别的玩家信息发给第三方的问题。
 *
 * <p><b>必须在服务端主线程调用</b>。
 */
public final class PlayerStatusTool {

    public static final String NAME = "player_status";

    private PlayerStatusTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME, "读取玩家自己的当前状态：血量、饥饿值、经验等级、"
                        + "是否着火/在水中/潜行/疾跑，以及身上的药水效果。无参数。").build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config, JsonObject args) {
        StringBuilder out = new StringBuilder(256);
        BlockPos pos = player.blockPosition();

        out.append("玩家当前状态：\n");
        out.append("- 血量: ").append(oneDecimal(player.getHealth()))
                .append(" / ").append(oneDecimal(player.getMaxHealth())).append('\n');
        out.append("- 饥饿值: ").append(player.getFoodData().getFoodLevel()).append(" / 20")
                .append("，饱和度: ").append(oneDecimal(player.getFoodData().getSaturationLevel())).append('\n');
        out.append("- 经验等级: ").append(player.experienceLevel).append('\n');
        out.append("- 位置: ").append(player.level().dimension().location())
                .append(' ').append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                .append('\n');
        out.append("- 状态: ").append(describeFlags(player)).append('\n');
        out.append("- 药水效果: ").append(describeEffects(player, config.toolMaxResults())).append('\n');

        return ToolOutcome.ok(out.toString());
    }

    /** 只列成立的项；都不成立时明确说「无」，不要让模型去猜某个字段缺省代表什么。 */
    private static String describeFlags(ServerPlayer player) {
        List<String> flags = new ArrayList<>(4);
        if (player.isOnFire()) {
            flags.add("着火");
        }
        if (player.isInWater()) {
            flags.add("在水中");
        }
        if (player.isShiftKeyDown()) {
            flags.add("潜行");
        }
        if (player.isSprinting()) {
            flags.add("疾跑");
        }
        return flags.isEmpty() ? "无特殊状态" : String.join("、", flags);
    }

    private static String describeEffects(ServerPlayer player, int maxResults) {
        List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());
        if (effects.isEmpty()) {
            return "无";
        }
        // 先按剩余时间排序：快过期的更值得模型提醒玩家
        effects.sort(Comparator.comparingInt(MobEffectInstance::getDuration));
        int limit = Math.min(effects.size(), Math.max(1, maxResults));

        List<String> parts = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            MobEffectInstance effect = effects.get(i);
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
            parts.add((id == null ? "(未知效果)" : id.toString())
                    + " " + romanLevel(effect.getAmplifier())
                    + "（剩 " + describeDuration(effect.getDuration()) + "）");
        }
        String text = String.join("、", parts);
        return effects.size() > limit ? text + "，另有 " + (effects.size() - limit) + " 项被省略" : text;
    }

    /** 时长为负（无限效果）时如实说无限，不要算成「剩 -1 秒」。 */
    private static String describeDuration(int ticks) {
        if (ticks < 0) {
            return "无限";
        }
        // 向上取整：剩 1 刻说「0 秒」会让玩家以为已经结束了
        int seconds = (int) Math.ceil(ticks / 20.0D);
        return seconds + " 秒";
    }

    /** 药水等级用罗马数字，和游戏内提示一致（amplifier 0 = I 级）。 */
    private static String romanLevel(int amplifier) {
        return switch (amplifier) {
            case 0 -> "I";
            case 1 -> "II";
            case 2 -> "III";
            case 3 -> "IV";
            default -> String.valueOf(amplifier + 1);
        };
    }

    /** 固定用 Locale.ROOT：某些区域的默认 locale 会把小数点渲染成逗号。 */
    private static String oneDecimal(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
