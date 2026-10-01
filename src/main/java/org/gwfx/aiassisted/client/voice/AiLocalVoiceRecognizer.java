package org.gwfx.aiassisted.client.voice;

import com.mojang.logging.LogUtils;
import org.gwfx.aiassisted.core.voice.WavHelper;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 客户端本地系统语音识别（STT 离线降级方案）。
 *
 * <p>针对 Minecraft 常见游戏语境注入了专用短语文法与同音纠错，大幅消灭老旧引擎的误听。
 */
public final class AiLocalVoiceRecognizer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AI-Local-STT-Thread");
        t.setDaemon(true);
        return t;
    });

    private AiLocalVoiceRecognizer() {
    }

    /**
     * 将 WAV 录音字节数组识别为文本。
     */
    public static CompletableFuture<String> transcribe(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44 || !WavHelper.isValidWav(wavBytes)) {
            return CompletableFuture.completedFuture("");
        }

        return CompletableFuture.supplyAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                String raw = transcribeWindows(wavBytes);
                return correctCommonMistakes(raw);
            }
            return "";
        }, EXECUTOR);
    }

    private static String transcribeWindows(byte[] wavBytes) {
        File tempScript = null;
        File tempWav = null;
        try {
            tempScript = File.createTempFile("mc_stt_", ".ps1");
            tempWav = File.createTempFile("mc_stt_", ".wav");
            tempScript.deleteOnExit();
            tempWav.deleteOnExit();

            Files.write(tempWav.toPath(), wavBytes);

            String wavPath = tempWav.getAbsolutePath().replace("\\", "/");

            // 注入 Minecraft 专属短语文法（高权重 1.0）+ 兜底听写文法（权重 0.2）
            String script = "\uFEFF" +
                    "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8;\n" +
                    "Add-Type -AssemblyName System.Speech;\n" +
                    "$engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine;\n" +
                    "\n" +
                    "$choices = New-Object System.Speech.Recognition.Choices;\n" +
                    "$choices.Add([string[]]@(\n" +
                    "    '帮我建一个小木屋', '帮我造一个小木屋', '造一个小木屋', '建一个小木屋', '盖一个木屋', '建个小木屋', '造个木屋', '建木屋',\n" +
                    "    '造一个小屋', '建一个小屋', '造房子', '建房子', '盖房子', '造个房子', '建个房子',\n" +
                    "    '帮我看看周围有什么方块', '周围有什么方块', '附近有什么方块', '附近有什么实体', '查看周围', '附近有什么', '周围有什么', '帮我看看周围',\n" +
                    "    '确认', '确认建造', '确认执行', '好的造吧', '好的', '执行', '确定', '没问题', '行', '可以', '建吧', '造吧',\n" +
                    "    '取消', '算了', '不要了', '取消建造', '别建了', '别造了', '别执行', '放弃', '撤销',\n" +
                    "    '把时间改成白天', '调成白天', '变成白天', '清除下雨', '晴天', '白天', '黑夜', '晚上'\n" +
                    "));\n" +
                    "$builder = New-Object System.Speech.Recognition.GrammarBuilder($choices);\n" +
                    "$gameGrammar = New-Object System.Speech.Recognition.Grammar($builder);\n" +
                    "$gameGrammar.Weight = 1.0;\n" +
                    "$engine.LoadGrammar($gameGrammar);\n" +
                    "\n" +
                    "$dictGrammar = New-Object System.Speech.Recognition.DictationGrammar;\n" +
                    "$dictGrammar.Weight = 0.2;\n" +
                    "$engine.LoadGrammar($dictGrammar);\n" +
                    "\n" +
                    "$engine.SetInputToWaveFile('" + wavPath + "');\n" +
                    "$result = $engine.Recognize();\n" +
                    "if ($result -ne $null) {\n" +
                    "    Write-Output ('STT_RESULT:' + $result.Text);\n" +
                    "} else {\n" +
                    "    Write-Output 'STT_RESULT:';\n" +
                    "}\n" +
                    "$engine.Dispose();\n";

            Files.write(tempScript.toPath(), script.getBytes(StandardCharsets.UTF_8));

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", tempScript.getAbsolutePath()
            );

            // stderr 合并进 stdout：管道缓冲写满时 PowerShell 会阻塞，没人排空的 stderr
            // 会让下面等进程结束的那一步永久挂着（本识别器是单线程执行器，卡一次后续全排队）
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            // 输出必须在等进程结束之前就抽干：先 readLine 到 EOF 再 waitFor 的话，
            // 8 秒超时形同虚设，进程挂住时读取就永久阻塞
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("STT_RESULT:")) {
                            output.append(line.substring("STT_RESULT:".length()).strip());
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被强杀时流会中断：已读到的部分照常返回
                }
            }, "AI-Local-STT-Drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drain.join(1000);
                LOGGER.debug("[AI-Local-STT] 本地语音识别超时");
                return "";
            }
            drain.join(1000);

            return output.toString().strip();
        } catch (Exception e) {
            LOGGER.debug("[AI-Local-STT] 本地语音识别失败: {}", e.getMessage());
            return "";
        } finally {
            if (tempScript != null && tempScript.exists()) {
                tempScript.delete();
            }
            if (tempWav != null && tempWav.exists()) {
                tempWav.delete();
            }
        }
    }

    /**
     * 对老旧本地声学模型极易产生的同音/声母混淆进行智能纠错。
     */
    public static String correctCommonMistakes(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw;
        text = text.replaceAll("王文件", "帮我建")
                .replaceAll("当我见", "帮我建")
                .replaceAll("帮我见", "帮我建")
                .replaceAll("把文件", "帮我建")
                .replaceAll("帮我鉴", "帮我建")
                .replaceAll("帮我点一个小木屋", "帮我建一个小木屋")
                .replaceAll("见一个小木屋", "建一个小木屋")
                .replaceAll("造个小木屋", "造一个小木屋")
                .replaceAll("照一个小木屋", "造一个小木屋")
                .replaceAll("确然", "确认")
                .replaceAll("快来看周围", "帮我看看周围")
                .replaceAll("找房子", "造房子")
                .replaceAll("建房字", "建房子")
                .replaceAll("调臣白天", "调成白天")
                .replaceAll("晴填", "晴天");
        return text;
    }
}
