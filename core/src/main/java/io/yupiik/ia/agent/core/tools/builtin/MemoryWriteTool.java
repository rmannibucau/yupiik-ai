package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.List;
import java.util.Map;

import static io.yupiik.ia.agent.core.tools.Schemas.str;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;

@ApplicationScoped
public class MemoryWriteTool implements EmbeddedTool {
    private final MemoryStore memoryStore;

    public MemoryWriteTool(final MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    protected MemoryWriteTool() {
        this.memoryStore = null;
    }

    @Override
    public String name() {
        return "memory_write";
    }

    @Override
    public String description() {
        return "Persist a durable fact, decision or user preference to memory (global or project scope).";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "fact", str("The fact to remember, one sentence."),
                "scope", str("`global` or `project`, default `project`.")), "fact");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var fact = arg(args, "fact");
        if (fact == null || fact.isBlank()) {
            return ToolResult.error("fact is required");
        }
        final var global = "global".equalsIgnoreCase(arg(args, "scope") == null ? "project" : arg(args, "scope"));
        final var added = memoryStore.append(global, List.of(fact));
        return added.isEmpty()
                ? ToolResult.ok("Already remembered (or skipped).")
                : ToolResult.ok("Remembered (" + (global ? "global" : "project") + "): " + added.get(0));
    }
}
