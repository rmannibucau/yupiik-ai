package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.Map;

@ApplicationScoped
public class CodeCheckGraphTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeCheckGraphTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected CodeCheckGraphTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "code_check_graph";
    }

    @Override
    public String description() {
        return "Report the freshness and health of the structural code graph (symbol/file counts, missing files, store location).";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of());
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        return ToolResult.ok(codeGraph.freshness());
    }
}