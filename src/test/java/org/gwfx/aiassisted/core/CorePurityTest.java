package org.gwfx.aiassisted.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 「ai.core 零 Minecraft 依赖」这条设计决策的唯一自动化验证手段。
 *
 * <p>本模组是单模块工程（不新建 Gradle 子模块），所以包边界只能靠约定 ——
 * 这个测试就是那条约定的执行者：一旦有人在 {@code ai.core} 里写了
 * {@code import net.minecraft.*}，这里立刻红。
 *
 * <p>同时扫描源码与编译产物：源码扫描能给出精确的文件名，字节码扫描能抓住
 * 通过完全限定名（而非 import）隐式引用 MC 的情况。
 */
class CorePurityTest {

    private static final Path CORE_CLASSES = Path.of(
            "build", "classes", "java", "main", "org", "gwfx", "aiassisted", "core");
    private static final Path CORE_SOURCES = Path.of(
            "src", "main", "java", "org", "gwfx", "aiassisted", "core");

    @Test
    void coreSourcesDoNotImportMinecraftOrLoader() throws IOException {
        assumeTrue(Files.isDirectory(CORE_SOURCES), "找不到 ai.core 源码目录: " + CORE_SOURCES.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CORE_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("import net.minecraft") || text.contains("import net.neoforged") || text.contains("import net.minecraftforge")) {
                    violations.add(CORE_SOURCES.relativize(file).toString());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "ai.core 源码不得 import Minecraft / 加载器（NeoForge、Forge），违规文件：\n" + String.join("\n", violations));
    }

    @Test
    void coreBytecodeDoesNotReferenceMinecraft() throws IOException {
        assumeTrue(Files.isDirectory(CORE_CLASSES),
                "ai.core 尚未编译，先运行 ./gradlew compileJava（当前目录: " + Path.of(".").toAbsolutePath() + "）");

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(CORE_CLASSES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                scanned++;
                // 常量池里的类名/描述符是 UTF-8，用 ISO-8859-1 逐字节映射即可安全地做子串匹配
                String literal = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (literal.contains("net/minecraft")) {
                    violations.add(CORE_CLASSES.relativize(file).toString());
                }
            }
        }

        assertTrue(scanned > 0, "ai.core 下应当已经编译出 class 文件");
        assertTrue(violations.isEmpty(),
                "ai.core 的字节码不得引用 net/minecraft，违规类：\n" + String.join("\n", violations));
    }
}
