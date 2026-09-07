package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class CodeFindCodeTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeFindCodeTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected CodeFindCodeTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "code_find_code";
    }

    @Override
    public String description() {
        return "Find symbols (types, methods, fields, functions) in the structural code graph by fuzzy name.";
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
                "query", Schemas.str("Name or name fragment to search, eg. `readFile` or `ToolExecutionService`."),
                "kind", Schemas.str("Optional symbol kind filter: TYPE, METHOD, FIELD, FUNCTION, CONSTRUCTOR, INTERFACE, ENUM, ANNOTATION."),
                "max", Schemas.intProp("Maximum number of results, default 40.")), "query");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var query = arg(args, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("query is required");
        }
        final var kind = arg(args, "kind");
        final var max = (int) Math.max(1, Math.min(200, optInt(args, "max", 40)));
        final var q = query.toLowerCase(Locale.ROOT);
        final var qKind = kind == null || kind.isBlank() ? null : kind.toUpperCase(Locale.ROOT);
        final var g = codeGraph.graph();
        final var sb = new StringBuilder();
        var count = 0;
        for (final var s : g.symbols()) {
            if (count >= max) {
                break;
            }
            if (qKind != null && s.kind() != null && !s.kind().name().equals(qKind)) {
                continue;
            }
            final var lower = s.fqn() == null ? "" : s.fqn().toLowerCase(Locale.ROOT);
            final var simple = simple(s.fqn());
            if (!lower.contains(q) && !simple.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            count++;
            sb.append(s.kind()).append("  ").append(s.fqn())
                    .append("  (").append(s.file()).append(':').append(s.line()).append(")\n");
        }
        if (count == 0) {
            return ToolResult.ok("No symbol matching '" + query + "'.");
        }
        if (count >= max) {
            sb.append("... capped at ").append(max).append('\n');
        }
        return ToolResult.ok(count + " symbol(s)\n" + sb);
    }

    private String simple(final String fqn) {
        if (fqn == null) {
            return "";
        }
        final var dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
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
}