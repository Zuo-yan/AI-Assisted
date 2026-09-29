package org.gwfx.aiassisted.core.agent;

import com.google.gson.JsonObject;

/**
 * 一个可被模型调用的工具。
 *
 * <p>入参一律按<b>不可信数据</b>对待：工具实现必须先校验再使用，
 * 且不得抛异常出界（{@link ToolRegistry#invoke} 会兜底，但兜底只是保险，不是许可证）。
 *
 * <p><b>默认必须只读且幂等</b>。唯一允许"作用于世界"的是 T002 引入的<b>危险级</b>工具，
 * 它必须同时满足 FR-05 末条的三条要求：<b>默认关闭</b>（配置开关）、<b>只能提议</b>
 * 而由玩家在游戏内确认后才执行、执行时<b>使用调用者自己的权限集</b>。
 * 少任何一条都不该合并进来 —— 那等于让模型直接改世界。
 */
@FunctionalInterface
public interface ToolInvoker {

    /** 执行工具并返回结果。参数已在 {@link ToolRegistry#invoke} 里解析成 JSON 对象。 */
    ToolOutcome invoke(JsonObject args);
}
