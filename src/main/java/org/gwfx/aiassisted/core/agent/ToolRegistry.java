package org.gwfx.aiassisted.core.agent;

import com.google.gson.JsonObject;
import org.gwfx.aiassisted.core.llm.ToolCall;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：名字 → 声明 + 实现。
 *
 * <p>用 {@link LinkedHashMap} 保持注册顺序 —— 工具列表会原样出现在请求体里，
 * 顺序固定便于对比不同版本发出去的报文。
 *
 * <p>{@link #invoke} 是「查表 → 解析入参 → 执行」的唯一入口，也是本层唯一的异常边界：
 * 未知工具名、实现内部异常都在这里转成失败结果回传给模型，<b>绝不向上抛</b>，
 * 否则一个写坏的工具就能把整轮对话打断。
 */
public final class ToolRegistry {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** 一条注册项：声明（给模型看）+ 实现（真正执行）。 */
    public record Entry(ToolSpec spec, ToolInvoker invoker) {
    }

    /** 注册一个工具；名为空或参数缺失的注册项直接忽略（宁可少一个工具，也不要一个空名字的工具）。 */
    public void register(ToolSpec spec, ToolInvoker invoker) {
        if (spec == null || invoker == null || spec.name().isBlank()) {
            return;
        }
        entries.put(spec.name(), new Entry(spec, invoker));
    }

    /** 供 {@code ChatRequest} 使用的声明列表；为空时请求体里不会出现 tools 字段。 */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            specs.add(entry.spec());
        }
        return specs;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Optional<ToolInvoker> find(String name) {
        Entry entry = name == null ? null : entries.get(name);
        return Optional.ofNullable(entry).map(Entry::invoker);
    }

    /**
     * 执行一次工具调用，并把一切异常收敛为 {@link ToolOutcome}。
     *
     * <p>调用方负责保证线程正确性（工具要读世界，必须在本模组约定的服务端主线程上调用）。
     */
    public ToolOutcome invoke(ToolCall call) {
        Optional<ToolInvoker> invoker = find(call.name());
        if (invoker.isEmpty()) {
            return ToolOutcome.unknownTool(call.name());
        }
        JsonObject args = ToolArgs.parse(call.argumentsJson());
        try {
            ToolOutcome outcome = invoker.get().invoke(args);
            return outcome == null ? ToolOutcome.error("工具没有返回结果") : outcome;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            return ToolOutcome.error("工具执行失败：" + message);
        }
    }

    /** 空注册表：关闭工具调用时用它，{@link #specs()} 为空 → 报文里不出现 tools。 */
    public static ToolRegistry empty() {
        return new ToolRegistry();
    }
}
