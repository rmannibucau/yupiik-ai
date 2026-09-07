package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CodeFileApiTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeFileApiTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected CodeFileApiTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "code_file_api";
    }

    @Override
    public String description() {
        return "List the symbols defined in one file of the structural code graph, eg. to know what a file exposes.";
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
        return Schemas.object(Map.of(
                "path", Schemas.str("Path of the file, relative to the working directory or absolute.")), "path");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var path = arg(args, "path");
        if (path == null || path.isBlank()) {
            return ToolResult.error("path is required");
        }
        final var g = codeGraph.graph();
        final var file = codeGraph.rel(java.nio.file.Path.of(path));
        final List<GraphModel.Symbol> inFile = new ArrayList<>();
        for (final var s : g.symbols()) {
            if (file.equals(s.file())) {
                inFile.add(s);
            }
        }
        if (inFile.isEmpty()) {
            return ToolResult.ok("No symbols indexed for " + path + ".");
        }
        inFile.sort(Comparator.comparingInt(GraphModel.Symbol::line));
        final var sb = new StringBuilder();
        for (final var s : inFile) {
            sb.append(String.format("%5d  %s  %s%n", s.line(), s.kind(), s.fqn()));
        }
        return ToolResult.ok(inFile.size() + " symbol(s) in " + path + "\n" + sb);
    }

    private String arg(final Map<String, Object> args, final String key) {
        return args.get(key) == null ? null : args.get(key).toString();
    }
}