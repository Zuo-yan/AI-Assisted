package org.gwfx.aiassisted.secret;

import org.gwfx.aiassisted.core.text.KeyRedactor;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * API Key 存储与解析。
 *
 * <p><b>解析优先级</b>（与任务文档 FR-04 一致）：
 * <ol>
 *   <li>环境变量 {@link #ENV_VAR}（最安全，推荐给服务器管理员）</li>
 *   <li>运行目录下的加密文件 {@code config/zuoyanmod/ai-secret.dat}</li>
 *   <li>本次会话的内存覆盖（仅当文件写入失败时的兜底，不落盘）</li>
 * </ol>
 *
 * <p><b>加密强度说明（务必如实告知用户）</b>：这里用的是 JDK 内置
 * PBKDF2 + AES-GCM，口令是编译进 jar 的常量。它解决的是「密钥被明文留在配置文件里、
 * 顺手粘进 issue / 误提交进版本库」这类事故，<b>不是抗逆向的强保护</b> ——
 * 拿到 jar 和文件的人仍有可能还原。真正在意安全性请用环境变量注入。
 *
 * <p>文件内容只在首次读取时解密一次并缓存在内存中（PBKDF2 有 65536 轮，
 * 不能每轮对话都在服务端主线程上重算），{@link #store} / {@link #clear} 会同步刷新缓存。
 *
 * <p>本类只对外暴露 {@link #resolved()} 一个取密钥入口，并在产生返回值时
 * 顺手把它登记进 {@link KeyRedactor}，避免各处忘记脱敏。
 */
public final class KeyStore {

    /** 环境变量名。 */
    public static final String ENV_VAR = "ZUOYAN_AI_API_KEY";

    /** 文件头，用于识别本模组写出的密钥文件并预留格式升级空间。 */
    private static final String FILE_HEADER = "AIASSISTED1:";

    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** 编译期常量口令：只提供混淆级保护，详见类注释的强度说明。 */
    private static final char[] APP_SECRET = "ai_assisted/ai/secret-store/v1".toCharArray();

    /** 密钥来源，供 {@code /ai status} 用 lang 键翻译后展示。 */
    public enum Source {
        NONE,
        ENVIRONMENT,
        FILE,
        SESSION
    }

    private final Path file;
    private final KeyRedactor redactor;
    private final SecureRandom random = new SecureRandom();

    /** 文件密钥的惰性缓存：{@code probed} 为 true 后 {@code fileKey} 即为权威值（可为 null）。 */
    private volatile boolean fileProbed;
    private volatile String fileKey;

    /** 仅内存、不落盘的兜底密钥（文件写入失败时使用）。 */
    private volatile String sessionKey;

    /** @param configDir 运行目录下的 config 目录，例如 {@code <gamedir>/config} */
    public KeyStore(Path configDir, KeyRedactor redactor) {
        this.file = configDir.resolve("zuoyanmod").resolve("ai-secret.dat");
        this.redactor = redactor;
    }

    /** 密钥文件路径（供 {@code /ai status} 展示与自检用，不含内容）。 */
    public Path file() {
        return file;
    }

    /** 当前生效的密钥。取到即登记脱敏。 */
    public Optional<String> resolved() {
        Optional<String> fromEnv = fromEnvironment();
        if (fromEnv.isPresent()) {
            redactor.registerSecret(fromEnv.get());
            return fromEnv;
        }
        Optional<String> fromFile = fromFile();
        if (fromFile.isPresent()) {
            redactor.registerSecret(fromFile.get());
            return fromFile;
        }
        String session = this.sessionKey;
        if (session != null && !session.isBlank()) {
            redactor.registerSecret(session);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    /** 密钥来源，用于向玩家解释「这个 Key 是哪来的」。 */
    public Source source() {
        if (fromEnvironment().isPresent()) {
            return Source.ENVIRONMENT;
        }
        if (fromFile().isPresent()) {
            return Source.FILE;
        }
        return this.sessionKey != null && !this.sessionKey.isBlank() ? Source.SESSION : Source.NONE;
    }

    /** 环境变量里的密钥（去空白；空串视为未配置）。 */
    public Optional<String> fromEnvironment() {
        String raw = System.getenv(ENV_VAR);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(raw.strip());
    }

    /**
     * 把密钥加密写入运行目录。
     *
     * @return 写入成功返回 true；失败（目录只读等）返回 false，
     *         此时调用方应改用 {@link #setSessionOnly} 以免使用方被卡住。
     */
    public boolean store(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String key = apiKey.strip();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, FILE_HEADER + encrypt(key), StandardCharsets.UTF_8);
            tightenPermissions();
            this.fileKey = key;
            this.fileProbed = true;
            this.sessionKey = null;
            redactor.registerSecret(key);
            return true;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** 仅保存在内存中，不落盘。 */
    public void setSessionOnly(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        this.sessionKey = apiKey.strip();
        redactor.registerSecret(this.sessionKey);
    }

    /**
     * 清除已存密钥（文件 + 会话内存）。
     *
     * @return 是否确实删掉了文件
     */
    public boolean clear() {
        this.sessionKey = null;
        this.fileKey = null;
        this.fileProbed = true;
        redactor.clear();
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }

    /** 惰性读取 + 解密一次，之后走缓存。 */
    private Optional<String> fromFile() {
        if (!fileProbed) {
            this.fileKey = readFile();
            this.fileProbed = true;
        }
        String cached = this.fileKey;
        return cached == null || cached.isBlank() ? Optional.empty() : Optional.of(cached);
    }

    /** @return 解密出的密钥；文件不存在、格式不符或解密失败时返回 null。 */
    private String readFile() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!content.startsWith(FILE_HEADER)) {
                return null;
            }
            String decrypted = decrypt(content.substring(FILE_HEADER.length()));
            return decrypted.isBlank() ? null : decrypted;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // 文件损坏或换了机器：按未配置处理，不抛异常（否则会让整个 /ai 不可用）
            return null;
        }
    }

    private String encrypt(String plaintext) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[salt.length + nonce.length + ciphertext.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(nonce, 0, combined, salt.length, nonce.length);
        System.arraycopy(ciphertext, 0, combined, salt.length + nonce.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String base64) throws GeneralSecurityException {
        byte[] combined = Base64.getDecoder().decode(base64);
        if (combined.length <= SALT_BYTES + NONCE_BYTES) {
            throw new GeneralSecurityException("密钥文件长度不合法");
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = new byte[combined.length - SALT_BYTES - NONCE_BYTES];
        System.arraycopy(combined, 0, salt, 0, SALT_BYTES);
        System.arraycopy(combined, SALT_BYTES, nonce, 0, NONCE_BYTES);
        System.arraycopy(combined, SALT_BYTES + NONCE_BYTES, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(APP_SECRET, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM).generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /** 收紧文件权限（仅 POSIX 文件系统；Windows 上是 no-op，文件 ACL 由用户目录继承）。 */
    private void tightenPermissions() {
        try {
            if (!Files.getFileStore(file).supportsFileAttributeView("posix")) {
                return;
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // 权限收紧失败不该让「保存密钥」这件事整体失败
        }
    }
}
