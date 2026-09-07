package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@ApplicationScoped
public class CodeRepoMapTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeRepoMapTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected CodeRepoMapTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "code_repo_map";
    }

    @Override
    public String description() {
        return "Produce a map of the repository: subsystems grouped by package, with sizes and optional concept-node summaries.";
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
                "includeSummaries", Schemas.bool("Include LLM concept-node summaries when available, default true."),
                "max", Schemas.intProp("Maximum number of subsystems to list, default 80.")), "includeSummaries", "max");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var includeSummaries = optBool(args, "includeSummaries", true);
        final var max = (int) Math.max(1, Math.min(300, optInt(args, "max", 80)));
        final var g = codeGraph.graph();
        final var subsystems = new TreeMap<String, Map<GraphModel.Kind, Integer>>();
        for (final var s : g.symbols()) {
            final var key = subsystem(s.fqn());
            subsystems.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .merge(s.kind() == null ? GraphModel.Kind.TYPE : s.kind(), 1, Integer::sum);
        }
        final var sb = new StringBuilder();
        var shown = 0;
        for (final var entry : subsystems.entrySet()) {
            if (shown++ >= max) {
                sb.append("... capped at ").append(max).append(" subsystems\n");
                break;
            }
            sb.append("### ").append(entry.getKey()).append('\n');
            final var counts = entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
                    .toList();
            for (final var c : counts) {
                sb.append("- ").append(c.getValue()).append(' ').append(c.getKey().name().toLowerCase(Locale.ROOT)).append('\n');
            }
            if (includeSummaries) {
                final var summary = readSummary(entry.getKey());
                if (summary != null) {
                    sb.append(summary);
                }
            }
            sb.append('\n');
        }
        sb.append(subsystems.size()).append(" subsystem(s), ")
                .append(g.symbols().size()).append(" symbol(s), ")
                .append(g.edges().size()).append(" edge(s)\n");
        return ToolResult.ok(sb.toString());
    }

    private String subsystem(final String fqn) {
        if (fqn == null) {
            return "(root)";
        }
        final var idx = fqn.lastIndexOf('.');
        if (idx <= 0) {
            return fqn;
        }
        return fqn.substring(0, idx);
    }

    private String readSummary(final String subsystem) {
        final var file = codeGraph.storeRoot().resolve("nodes").resolve(sanitize(subsystem) + ".md");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final var content = Files.readString(file);
            final var lines = content.lines().filter(l -> !l.startsWith("<!-- hash=")).toList();
            if (lines.isEmpty()) {
                return null;
            }
            return String.join("\n", lines).stripTrailing() + "\n";
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String sanitize(final String name) {
        return name.replace('/', '_').replace('\\', '_').replace('.', '_').replace('*', '_');
    }

    private String arg(final Map<String, Object> args, final String key) {
        return args.get(key) == null ? null : args.get(key).toString();
    }

    private long optInt(final Map<String, Object> args, final String key, final long dflt) {
        final var v = args.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? dflt : Long.parseLong(v.toString());
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }

    private boolean optBool(final Map<String, Object> args, final String key, final boolean dflt) {
        final var v = args.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }
}