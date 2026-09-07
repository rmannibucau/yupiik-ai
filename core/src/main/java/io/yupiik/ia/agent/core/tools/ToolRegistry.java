package io.yupiik.ia.agent.core.tools;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.mcp.McpManager;

import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class ToolRegistry {
    private final McpManager mcpManager;

    public ToolRegistry(final McpManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    protected ToolRegistry() {
        this.mcpManager = null;
    }

    public void onStart(@OnEvent final Start start) {
        mcpManager.start();
    }

    public List<EmbeddedTool> tools() {
        return mcpManager.tools().stream()
                .sorted(Comparator.comparing(EmbeddedTool::name))
                .toList();
    }

    public EmbeddedTool tool(final String name) {
        return tools().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    public List<Tool> toWireTools() {
        return tools().stream()
                .map(t -> Tool.function(t.name(), t.description(), t.schema()))
                .toList();
    }

    public String describe() {
        final var sb = new StringBuilder();
        for (final var t : tools()) {
            sb.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
        }
        return sb.toString();
    }
}
