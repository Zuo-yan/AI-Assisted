package org.gwfx.aiassisted.core.text;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 统一脱敏器。
 *
 * <p>本模组的密钥是用户自费的，任何泄漏都会造成实际损失，因此约定：
 * <b>凡是可能含密钥、URL 或请求体的文本，在写进日志/聊天/异常之前都要过一遍这里。</b>
 *
 * <p>两道防线：
 * <ol>
 *   <li>登记式替换：{@link #registerSecret} 把实际解析出的密钥登记进来，命中即替换为 ***；</li>
 *   <li>模式式替换：{@link #redactUrl} 按 query 参数名（key / token / ...）兜底，
 *       即使某条密钥没来得及登记也不会原样落盘。</li>
 * </ol>
 */
public final class KeyRedactor {

    /** 过短的"密钥"不做登记，避免把 "1" 这种字符串当成密钥把正常日志改成 ***。 */
    private static final int MIN_SECRET_LENGTH = 8;

    private static final String MASK = "***";

    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)\\b(api[_-]?key|apikey|key|token|access[_-]?token|auth|authorization|secret|password)=([^&\\s\"']+)");

    private final Set<String> secrets = ConcurrentHashMap.newKeySet();

    /** 登记一条需要脱敏的密钥。空白或过短的输入被忽略。 */
    public void registerSecret(String secret) {
        if (secret != null && secret.strip().length() >= MIN_SECRET_LENGTH) {
            secrets.add(secret.strip());
        }
    }

    /** 清空登记表（配置热重载换密钥时调用）。 */
    public void clear() {
        secrets.clear();
    }

    /** 通用脱敏：先替换已登记的密钥，再按 URL 参数名兜底。 */
    public String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        for (String secret : secrets) {
            result = result.replace(secret, MASK);
        }
        return redactUrl(result);
    }

    /**
     * 只做 URL / query 级别的脱敏，不依赖登记表。
     *
     * <p>静态版也暴露出来，便于在还没有脱敏器实例的地方（例如极早期启动阶段）直接调用。
     */
    public static String redactUrl(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return SENSITIVE_QUERY.matcher(input).replaceAll(matchResult -> matchResult.group(1) + "=" + MASK);
    }

    /** 当前登记了几条密钥（仅供测试与自检，不暴露内容）。 */
    public int registeredCount() {
        return secrets.size();
    }
}
