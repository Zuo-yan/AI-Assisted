package org.gwfx.aiassisted.core.llm;

import java.util.concurrent.CompletableFuture;

/**
 * LLM 服务提供方抽象。
 *
 * <p>实现必须满足两条硬性约束：
 * <ol>
 *   <li><b>不阻塞调用线程</b>：返回 {@link CompletableFuture}，网络等待发生在传输层线程池里。</li>
 *   <li><b>失败一律抛 {@link LlmException}</b>（作为 future 的异常完成），且 message 已脱敏。</li>
 * </ol>
 */
public interface LlmProvider {

    /** 提供方标识，对应配置项 {@code ai.provider}。 */
    String id();

    /** 发起一次非流式对话。 */
    CompletableFuture<ChatResponse> chat(ChatRequest request);

    /** 释放底层资源。默认无操作。 */
    default void close() {
    }
}
