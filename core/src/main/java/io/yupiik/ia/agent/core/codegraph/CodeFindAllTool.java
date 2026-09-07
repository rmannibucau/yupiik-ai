package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class CodeFindAllTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeFindAllTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected CodeFindAllTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "code_find_all";
    }

    @Override
    public String description() {
        return "List all symbols of the structural code graph, optionally filtered by kind or package/path prefix.";
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
                "kind", Schemas.str("Symbol kind filter: TYPE, METHOD, FIELD, FUNCTION, CONSTRUCTOR, INTERFACE, ENUM, ANNOTATION."),
                "prefix", Schemas.str("Symbol fully-qualified-name prefix, eg. `io.yupiik.ia` or a package name."),
                "max", Schemas.intProp("Maximum number of results, default 100.")), "prefix");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var prefix = arg(args, "prefix");
        final var kind = arg(args, "kind");
        final var max = (int) Math.max(1, Math.min(500, optInt(args, "max", 100)));
        if (prefix == null || prefix.isBlank()) {
            return ToolResult.error("prefix is required");
        }
        final var qKind = kind == null || kind.isBlank() ? null : kind.toUpperCase(Locale.ROOT);
        final var g = codeGraph.graph();
        final var sb = new StringBuilder();
        var count = 0;
        for (final var s : g.symbols()) {
            if (count >= max) {
                break;
            }
            final var fqn = s.fqn() == null ? "" : s.fqn();
            if (!fqn.startsWith(prefix)) {
                continue;
            }
            if (qKind != null && s.kind() != null && !s.kind().name().equals(qKind)) {
                continue;
            }
            count++;
            sb.append(s.kind()).append("  ").append(fqn)
                    .append("  (").append(s.file()).append(':').append(s.line()).append(")\n");
        }
        if (count == 0 && prefix.endsWith(".*")) {
            return ToolResult.ok("Treating '" + prefix + "' as everything; no result.\n"
                    + "Tip: pass a concrete package or FQN prefix.");
        }
        if (count == 0) {
            return ToolResult.ok("No symbols match prefix '" + prefix + "'.");
        }
        if (count >= max) {
            sb.append("... capped at ").append(max).append('\n');
        }
        return ToolResult.ok(count + " symbol(s)\n" + sb);
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