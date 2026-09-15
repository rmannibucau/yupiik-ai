/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CodeTraceCallsTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeTraceCallsTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected CodeTraceCallsTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "code_trace_calls";
    }

    @Override
    public String description() {
        return "Trace relations of a symbol in the structural code graph (breadth-first, optional depth limit): "
                + "callers/callees via calls, implementors (edgeTypes=implements), extensions (extends), usages (uses) "
                + "or imports. Default follows calls; narrow with the edgeTypes parameter (eg. edgeTypes=implements). "
                + "Returns each found symbol resolved to its kind and file location. The returned list is complete and "
                + "authoritative for the traced relation: do not grep or re-read the referenced sources simply to "
                + "confirm membership, only read a file if you need method/body detail beyond the trace.";
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
        return Schemas.object(
                Map.of(
                        "symbol",
                                Schemas.str(
                                        "Symbol to trace, eg. `io.yupiik.ia.agent.core.tools.ToolExecutionService`."),
                        "direction",
                                Schemas.str(
                                        "`callers` (who calls it), `callees` (what it calls) or `both`, default `both`."),
                        "edgeTypes",
                                Schemas.str(
                                        "Comma-separated relation types to follow, default `calls`. Accepted: calls, "
                                                + "implements, extends, uses, imports, all. Eg. `implements` finds "
                                                + "implementors of an interface."),
                        "depth",
                                Schemas.intProp(
                                        "Maximum traversal depth. Unlimited (0) by default; set a positive value to bound it."),
                        "max", Schemas.intProp("Maximum number of edges to report, default 80.")),
                "symbol");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var symbol = arg(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return ToolResult.error("symbol is required");
        }
        final var direction = arg(args, "direction");
        final var edgeTypes = parseEdgeTypes(arg(args, "edgeTypes"));
        final var depth = optInt(args, "depth", 0);
        final var max = Math.clamp(optInt(args, "max", 80), 1, 500);
        final var g = codeGraph.graph();
        if (g.symbols().isEmpty() && g.edges().isEmpty()) {
            return ToolResult.ok("Structural code graph is empty.");
        }
        final var dir = direction == null ? "both" : direction.toLowerCase(java.util.Locale.ROOT);
        final var target = resolveExact(symbol, g);
        if (target == null) {
            return ToolResult.error("Symbol not found: " + symbol + ". List candidate symbols with the code_find_code "
                    + "tool, then retrace using the fully-qualified name (or grep the graph docs at path="
                    + codeGraph.storeRoot().resolve("docs") + ").");
        }
        final var neighborIndex = buildNeighborIndex(g, edgeTypes, dir);
        final var byFqn = new HashMap<String, GraphModel.Symbol>();
        for (final var s : g.symbols()) {
            byFqn.put(s.fqn(), s);
        }

        final var sb = new StringBuilder();
        var count = 0;
        final var found = new ArrayList<String>();
        final var visited = new HashSet<String>();
        final Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[] {target, "0"});
        visited.add(target);
        while (!queue.isEmpty() && count < max) {
            final var cur = queue.poll();
            final var name = cur[0];
            final var level = Integer.parseInt(cur[1]);
            if (level > 0) {
                sb.repeat("  ", level - 1).append('-').append(name).append('\n');
                found.add(name);
                count++;
                if (count >= max) {
                    break;
                }
            }
            if (depth > 0 && level >= depth) {
                continue;
            }
            for (final var n : neighborIndex.getOrDefault(name, Set.of())) {
                if (visited.add(n)) {
                    queue.add(new String[] {n, Integer.toString(level + 1)});
                }
            }
        }
        sb.append("\nresolved ")
                .append(symbol)
                .append(" (")
                .append(edgeTypesLabel(edgeTypes))
                .append("), ")
                .append(found.size())
                .append(" distinct symbol(s) found:\n");
        for (final var name : found) {
            final var s = byFqn.get(name);
            if (s == null || s.file() == null) {
                sb.append("- ").append(name).append('\n');
                continue;
            }
            sb.append("- ")
                    .append(s.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .append(' ')
                    .append(name)
                    .append(" (")
                    .append(s.file())
                    .append(':')
                    .append(s.startLine())
                    .append('-')
                    .append(s.endLine())
                    .append(")\n");
        }
        if (count >= max) {
            sb.append("(truncated to ").append(max).append(" edges; raise max and narrow edgeTypes for more)\n");
        }
        return ToolResult.ok(sb.toString());
    }

    private String edgeTypesLabel(final Set<GraphModel.EdgeType> edgeTypes) {
        return edgeTypes.stream()
                .map(t -> t.name().toLowerCase(java.util.Locale.ROOT))
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    private String resolveExact(final String symbol, final GraphStore g) {
        for (final var s : g.symbols()) {
            if (symbol.equals(s.fqn())) {
                return s.fqn();
            }
        }
        final var candidates = new ArrayList<String>();
        for (final var s : g.symbols()) {
            final var fqn = s.fqn();
            if (fqn != null && fqn.endsWith("." + symbol)) {
                candidates.add(fqn);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * Build a symbol-to-neighbors index in a single pass over the edges, so each BFS level is an O(1) lookup
     * instead of re-scanning the whole edge list. Caller/callee adjacency is deduped by construction via {@link Set}.
     */
    private Map<String, Set<String>> buildNeighborIndex(
            final GraphStore g, final Set<GraphModel.EdgeType> edgeTypes, final String direction) {
        final var wantCallers = "callers".equals(direction) || "both".equals(direction);
        final var wantCallees = "callees".equals(direction) || "both".equals(direction);
        final var index = new HashMap<String, Set<String>>();
        for (final var e : g.edges()) {
            if (!edgeTypes.contains(e.type())) {
                continue;
            }
            if (wantCallers && e.to() != null && e.from() != null) {
                index.computeIfAbsent(e.to(), name -> new HashSet<>()).add(e.from());
            }
            if (wantCallees && e.from() != null && e.to() != null) {
                index.computeIfAbsent(e.from(), name -> new HashSet<>()).add(e.to());
            }
        }
        return index;
    }

    private Set<GraphModel.EdgeType> parseEdgeTypes(final String raw) {
        if (raw == null || raw.isBlank()) {
            return EnumSet.of(GraphModel.EdgeType.CALLS);
        }
        final var result = new HashSet<GraphModel.EdgeType>();
        for (final var token : raw.split(",")) {
            if (token.isBlank()) {
                continue;
            }
            final var t = token.trim().toUpperCase(java.util.Locale.ROOT);
            if ("ALL".equals(t)) {
                return EnumSet.allOf(GraphModel.EdgeType.class);
            }
            try {
                result.add(GraphModel.EdgeType.valueOf(t));
            } catch (final IllegalArgumentException ignored) {
                // ignore unknown relation types so a typo does not abort the trace
            }
        }
        return result.isEmpty() ? EnumSet.of(GraphModel.EdgeType.CALLS) : result;
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
