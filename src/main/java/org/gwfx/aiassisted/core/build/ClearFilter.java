package org.gwfx.aiassisted.core.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 方块匹配过滤器（零 MC 依赖，纯 Java 逻辑）。
 *
 * <p>支持逗号分隔的方块名列表、完整命名空间、以及常见类别别名（如 "leaves", "wood", "stone", "all", "*"）。
 */
public final class ClearFilter {

    private final Set<String> exactIds;
    private final List<String> patterns;
    private final boolean matchAll;

    public ClearFilter(String filterExpression) {
        String trimmed = filterExpression == null ? "" : filterExpression.strip();
        if (trimmed.isEmpty() || "all".equalsIgnoreCase(trimmed) || "*".equals(trimmed)) {
            this.matchAll = true;
            this.exactIds = Set.of();
            this.patterns = List.of();
            return;
        }

        boolean all = false;
        Set<String> ids = new HashSet<>();
        List<String> pats = new ArrayList<>();

        String[] parts = trimmed.split("[,;]");
        for (String raw : parts) {
            String token = raw.strip().toLowerCase();
            if (token.isEmpty()) {
                continue;
            }
            if ("*".equals(token) || "all".equals(token)) {
                all = true;
                break;
            }
            if (token.contains(":")) {
                ids.add(token);
            } else {
                ids.add("minecraft:" + token);
                pats.add(token);
            }
        }

        this.matchAll = all;
        this.exactIds = all ? Set.of() : Collections.unmodifiableSet(ids);
        this.patterns = all ? List.of() : Collections.unmodifiableList(pats);
    }

    public boolean matches(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        if (this.matchAll) {
            return true;
        }
        String normalized = blockId.strip().toLowerCase();
        if (this.exactIds.contains(normalized)) {
            return true;
        }
        // 模式别名匹配（例如 "leaves" 匹配 "minecraft:oak_leaves"）
        for (String pat : this.patterns) {
            if (normalized.contains(pat)) {
                return true;
            }
        }
        return false;
    }

    public boolean isMatchAll() {
        return this.matchAll;
    }
}
