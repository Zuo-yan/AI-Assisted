package org.gwfx.aiassisted.core.agent;

import org.gwfx.aiassisted.core.llm.ToolCall;
import org.gwfx.aiassisted.core.llm.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static ToolSpec spec(String name) {
        return ToolSpec.builder(name, "d").stringParam("arg", "A", true).build();
    }

    @Test
    void specsFollowRegistrationOrder() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.isEmpty());
        assertTrue(registry.specs().isEmpty());

        registry.register(spec("a"), args -> ToolOutcome.ok("A"));
        registry.register(spec("b"), args -> ToolOutcome.ok("B"));

        assertFalse(registry.isEmpty());
        assertEquals(List.of("a", "b"), registry.specs().stream().map(ToolSpec::name).toList());
    }

    @Test
    void invokePassesParsedArgumentsToTheTool() {
        ToolRegistry registry = new ToolRegistry();
        String[] seen = new String[1];
        registry.register(spec("echo"), args -> {
            seen[0] = args.get("arg").getAsString();
            return ToolOutcome.ok("收到");
        });

        ToolOutcome outcome = registry.invoke(new ToolCall("id", "echo", "{\"arg\":\"v\"}"));

        assertEquals("v", seen[0]);
        assertTrue(outcome.ok());
        assertEquals("收到", outcome.text());
    }

    @Test
    void invokeRejectsUnknownToolInsteadOfThrowing() {
        ToolOutcome outcome = new ToolRegistry().invoke(new ToolCall("id", "nope", "{}"));

        assertFalse(outcome.ok());
        assertTrue(outcome.text().contains("nope"));
    }

    @Test
    void invokeTurnsToolExceptionIntoFailureOutcome() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(spec("boom"), args -> {
            throw new IllegalStateException("参数炸了");
        });

        ToolOutcome outcome = registry.invoke(new ToolCall("id", "boom", "{}"));

        // 一个写坏的工具不应该把整轮对话打断
        assertFalse(outcome.ok());
        assertTrue(outcome.text().contains("参数炸了"));
    }

    @Test
    void invokeAcceptsInvalidArgumentsJsonWithoutThrowing() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(spec("echo"), args -> ToolOutcome.ok("参数个数 " + args.size()));

        ToolOutcome outcome = registry.invoke(new ToolCall("id", "echo", "这不是 JSON"));

        assertTrue(outcome.ok());
        assertEquals("参数个数 0", outcome.text());
    }

    @Test
    void invokeHandlesNullOutcomeFromTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(spec("nil"), args -> null);

        assertFalse(registry.invoke(new ToolCall("id", "nil", "{}")).ok());
    }

    @Test
    void ignoresInvalidRegistrations() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolSpec("  ", "d", null), args -> ToolOutcome.ok("x"));
        registry.register(null, args -> ToolOutcome.ok("x"));
        registry.register(spec("ok"), null);

        assertTrue(registry.isEmpty());
    }

    @Test
    void emptyFactoryProducesRegistryWithoutSpecs() {
        assertTrue(ToolRegistry.empty().isEmpty());
    }
}
