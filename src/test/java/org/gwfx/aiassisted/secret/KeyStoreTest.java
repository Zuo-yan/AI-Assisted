package org.gwfx.aiassisted.secret;

import org.gwfx.aiassisted.core.text.KeyRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KeyStoreTest {

    private static final String FAKE_KEY = "sk-fake-0123456789abcdef";

    /**
     * 环境变量优先级最高，若 CI 或开发者机器上真的设置了它，本类中依赖密钥文件的断言就会失真。
     * 这种情况下直接跳过，而不是给出误导性的红。
     */
    private void requireNoEnvironmentKey() {
        assumeTrue(System.getenv(KeyStore.ENV_VAR) == null,
                "测试环境设置了 " + KeyStore.ENV_VAR + "，密钥文件相关断言不适用");
    }

    @Test
    void storesAndReadsBackKey(@TempDir Path configDir) {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());

        assertTrue(store.store(FAKE_KEY));
        assertEquals(FAKE_KEY, store.resolved().orElseThrow());
        assertEquals(KeyStore.Source.FILE, store.source());
    }

    @Test
    void writesFileThatIsNotPlaintext(@TempDir Path configDir) throws IOException {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());
        store.store(FAKE_KEY);

        Path file = store.file();
        assertTrue(Files.isRegularFile(file), "密钥文件应写入 " + file);

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(raw.contains(FAKE_KEY), "密钥绝不能以明文形式落盘");
        assertTrue(raw.startsWith("AIASSISTED1:"), "应带格式头，便于识别与将来升级格式");
    }

    @Test
    void survivesRestartByReloadingFromFile(@TempDir Path configDir) {
        requireNoEnvironmentKey();
        new KeyStore(configDir, new KeyRedactor()).store(FAKE_KEY);

        // 换一个实例 = 模拟服务端重启
        KeyStore afterRestart = new KeyStore(configDir, new KeyRedactor());

        assertEquals(FAKE_KEY, afterRestart.resolved().orElseThrow());
    }

    @Test
    void registersSecretWithRedactorSoLogsAreMasked(@TempDir Path configDir) {
        requireNoEnvironmentKey();
        KeyRedactor redactor = new KeyRedactor();
        KeyStore store = new KeyStore(configDir, redactor);
        store.store(FAKE_KEY);

        // 取一次密钥后，脱敏器里就应当有它，后续日志不会漏
        assertTrue(store.resolved().isPresent());
        assertFalse(redactor.redact("Authorization: Bearer " + FAKE_KEY).contains(FAKE_KEY));
    }

    @Test
    void clearDeletesFileAndFallsBackToNone(@TempDir Path configDir) {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());
        store.store(FAKE_KEY);

        assertTrue(store.clear(), "clear 应报告文件已被删除");
        assertFalse(Files.exists(store.file()));
        assertTrue(store.resolved().isEmpty());
        assertEquals(KeyStore.Source.NONE, store.source());
    }

    @Test
    void clearOnMissingFileIsNotAnError(@TempDir Path configDir) {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());

        assertFalse(store.clear());
        assertEquals(KeyStore.Source.NONE, store.source());
    }

    @Test
    void rejectsBlankKey(@TempDir Path configDir) {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());

        assertFalse(store.store(null));
        assertFalse(store.store("   "));
        assertTrue(store.resolved().isEmpty());
    }

    @Test
    void sessionOnlyKeyDoesNotTouchDisk(@TempDir Path configDir) {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());
        store.setSessionOnly(FAKE_KEY);

        assertEquals(FAKE_KEY, store.resolved().orElseThrow());
        assertEquals(KeyStore.Source.SESSION, store.source());
        assertFalse(Files.exists(store.file()), "仅会话密钥不该落盘");
    }

    @Test
    void corruptFileDegradesToNoKeyInsteadOfThrowing(@TempDir Path configDir) throws IOException {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());
        Path file = store.file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "AIASSISTED1:这不是合法的 base64!!!", StandardCharsets.UTF_8);

        // 文件损坏时按未配置处理：否则整个 /ai 会不可用，用户也没法用命令覆盖
        assertTrue(store.resolved().isEmpty());
        assertEquals(KeyStore.Source.NONE, store.source());
    }

    @Test
    void foreignFileHeaderIsIgnored(@TempDir Path configDir) throws IOException {
        requireNoEnvironmentKey();
        KeyStore store = new KeyStore(configDir, new KeyRedactor());
        Path file = store.file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not-our-format\n", StandardCharsets.UTF_8);

        assertTrue(store.resolved().isEmpty());
    }

    @Test
    void sameKeyEncryptsToDifferentCiphertext(@TempDir Path configDir) throws IOException {
        requireNoEnvironmentKey();
        KeyStore first = new KeyStore(configDir, new KeyRedactor());
        first.store(FAKE_KEY);
        String firstContent = Files.readString(first.file(), StandardCharsets.UTF_8);

        KeyStore second = new KeyStore(configDir, new KeyRedactor());
        second.store(FAKE_KEY);
        String secondContent = Files.readString(second.file(), StandardCharsets.UTF_8);

        // 每次写入使用新的 salt + nonce，因此密文不应相同
        assertFalse(firstContent.equals(secondContent), "相同明文不应产生相同密文");
    }
}
