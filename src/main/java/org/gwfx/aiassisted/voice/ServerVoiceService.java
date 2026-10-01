package org.gwfx.aiassisted.voice;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.gwfx.aiassisted.AiRuntime;
import org.gwfx.aiassisted.command.AiConfirmHandler;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.util.Locale;

/**
 * 服务端语音输入编排中心。
 *
 * <p>处理从客户端上行的麦克风 WAV 音频及识别文本：
 * 本地优先 / 云端按需 → 语音语义确认高危操作 → 聊天栏回显 → 驱动常规 Agent 对话。
 */
public final class ServerVoiceService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ServerVoiceService() {
    }

    public static void handleVoiceInput(ServerPlayer player, byte[] audioData) {
        handleVoiceInput(player, audioData, "");
    }

    /**
     * 处理客户端上报的录音与识别数据。
     */
    public static void handleVoiceInput(ServerPlayer player, byte[] audioData, String clientRecognizedText) {
        if (player == null || player.hasDisconnected()) {
            return;
        }

        if (!AiRuntime.isInitialized()) {
            player.sendSystemMessage(Component.translatable("ai.ai_assisted.error.generic"));
            return;
        }

        AiRuntime runtime = AiRuntime.get();
        AiConfig config = runtime.config();

        if (!config.enabled()) {
            player.sendSystemMessage(Component.translatable("ai.ai_assisted.error.disabled"));
            return;
        }

        String apiKey = runtime.keyStore().resolved().orElse("");
        if (config.requiresApiKey() && apiKey.isEmpty()) {
            player.sendSystemMessage(Component.translatable("ai.ai_assisted.error.no_key"));
            return;
        }

        // 1. 若客户端本地已成功识别出文本（离线系统识别），直接使用该文本，零延迟、免云端 503/404 错误
        String directText = (clientRecognizedText == null) ? "" : clientRecognizedText.strip();
        if (!directText.isEmpty()) {
            player.sendSystemMessage(Component.translatable("ai.ai_assisted.voice.transcribed", directText));

            // 语音意图识别：优先检查是否在语音确认/取消待办操作（建造方案或管理员指令）
            if (handlePendingActionByVoice(player, directText)) {
                return;
            }

            runtime.chatService().request(player, directText);
            return;
        }

        // 2. 客户端未能本地识别（如无本地引擎或环境限制），检查是否可以调用云端 Whisper
        if (audioData == null || audioData.length < 44) {
            player.sendSystemMessage(Component.translatable("ai.ai_assisted.voice.too_short"));
            return;
        }

        // 若主模型是已知纯文本提供商（如 DeepSeek），提示说话清晰或配置独立 Whisper
        if (isKnownTextOnlyProvider(config.baseUrl())) {
            String domain = extractHost(config.baseUrl());
            player.sendSystemMessage(Component.literal("§c[AI 语音] 本地未识别出声音，且主模型 API (" + domain + ") 不支持云端语音识别。"));
            player.sendSystemMessage(Component.literal("§e请说话时稍靠近麦克风并吐字清晰，系统将自动通过离线语音转录。"));
            return;
        }

        player.sendSystemMessage(Component.translatable("ai.ai_assisted.voice.transcribing"));

        String effectiveUrl = config.baseUrl();
        // 回调跑在传输线程：server 必须在异步发起前捕获，回调里只允许 server.execute，绝不碰世界
        MinecraftServer server = player.level().getServer();
        runtime.voiceClient().transcribe(
                audioData,
                effectiveUrl,
                "whisper-1",
                apiKey,
                config.timeout()
        ).whenComplete((text, error) -> {
            if (server != null) {
                server.execute(() -> {
                    if (player.hasDisconnected()) {
                        return;
                    }
                    if (error != null) {
                        LOGGER.warn("[AI-Voice] 语音识别失败: {}", error.getMessage());
                        player.sendSystemMessage(describeVoiceError(error, effectiveUrl));
                        return;
                    }
                    if (text == null || text.isBlank()) {
                        player.sendSystemMessage(Component.translatable("ai.ai_assisted.voice.no_speech"));
                        return;
                    }

                    player.sendSystemMessage(Component.translatable("ai.ai_assisted.voice.transcribed", text));

                    // 语音意图识别：优先检查是否在语音确认/取消待办操作
                    if (handlePendingActionByVoice(player, text)) {
                        return;
                    }

                    runtime.chatService().request(player, text);
                });
            }
        });
    }

    /**
     * 语音确认/取消待办操作（高危指令或建造方案）。
     *
     * @return true 表示命中确认或取消语义并已执行；false 表示常规发言
     */
    private static boolean handlePendingActionByVoice(ServerPlayer player, String text) {
        if (!AiConfirmHandler.hasPending(player)) {
            return false;
        }

        if (isConfirmIntent(text)) {
            AiConfirmHandler.Result result = AiConfirmHandler.confirm(player);
            player.sendSystemMessage(result.chatMessage());
            return true;
        }

        if (isCancelIntent(text)) {
            AiConfirmHandler.Result result = AiConfirmHandler.cancel(player);
            player.sendSystemMessage(result.chatMessage());
            return true;
        }

        return false;
    }

    public static boolean isConfirmIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String clean = text.replaceAll("[\\p{Punct}\\p{Space}，。！？、“”‘’]", "").toLowerCase(Locale.ROOT);
        if (clean.contains("吗") || clean.contains("不确定") || clean.contains("不确认") || clean.startsWith("别") || clean.startsWith("不")) {
            return false;
        }
        if (clean.equals("确认") || clean.equals("确定") || clean.equals("执行") || clean.equals("同意")
                || clean.equals("好的") || clean.equals("可以") || clean.equals("行") || clean.equals("没问题")
                || clean.equals("准了") || clean.equals("建吧") || clean.equals("造吧") || clean.equals("弄吧")
                || clean.equals("好的造吧") || clean.equals("确认执行") || clean.equals("确认建造")
                || clean.equals("好的执行吧") || clean.equals("confirm") || clean.equals("yes") || clean.equals("ok")
                || clean.equals("goahead") || clean.equals("doit")) {
            return true;
        }
        return clean.contains("确认建造") || clean.contains("确认执行") || clean.contains("好的造吧")
                || clean.contains("同意建造") || clean.contains("同意执行") || clean.contains("帮我确认")
                || (clean.contains("确认") && !clean.contains("取消"));
    }

    public static boolean isCancelIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String clean = text.replaceAll("[\\p{Punct}\\p{Space}，。！？、“”‘’]", "").toLowerCase(Locale.ROOT);
        return clean.equals("取消") || clean.equals("算了") || clean.equals("不要了") || clean.equals("别建了")
                || clean.equals("别造了") || clean.equals("别执行") || clean.equals("放弃") || clean.equals("撤销")
                || clean.equals("cancel") || clean.equals("abort") || clean.equals("no") || clean.equals("stop")
                || clean.contains("取消建造") || clean.contains("取消执行") || clean.contains("别建") || clean.contains("不要了");
    }

    public static boolean isKnownTextOnlyProvider(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("deepseek.com")
                || lower.contains("anthropic.com")
                || lower.contains("moonshot.cn")
                || lower.contains("minimax.chat");
    }

    private static String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }

    private static Component describeVoiceError(Throwable error, String url) {
        Throwable cause = unwrap(error);
        String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();

        if (msg.contains("503") || msg.contains("No available channel")) {
            return Component.literal("§c[AI 语音] 中转服务商渠道错误 (503): 当前 API Key 分组未添加 Whisper 渠道。请直接普通说话由系统离线识别。");
        }
        if (msg.contains("404")) {
            return Component.literal("§c[AI 语音] 接口返回 404 (端点不存在)。当前服务商不支持 Whisper，请直接普通说话由系统离线识别。");
        }
        if (msg.contains("401") || msg.contains("403")) {
            return Component.literal("§c[AI 语音] 密钥验证失败 (401/403)。请检查 API Key 是否有效。");
        }
        if (msg.contains("429")) {
            return Component.literal("§c[AI 语音] 请求受限 (429)。语音 API 额度不足或达到频率限制。");
        }
        return Component.literal("§c[AI 语音识别失败] " + msg);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
