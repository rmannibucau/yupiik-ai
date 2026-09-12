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
import java.util.HashSet;
import java.util.List;
import java.util.Map;

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
        return "Trace callers and callees of a symbol in the structural code graph (breadth-first, optional depth limit).";
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
                        "depth", Schemas.intProp("Maximum traversal depth, default 3."),
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
        final var depth = (int) Math.max(1, Math.min(8, optInt(args, "depth", 3)));
        final var max = (int) Math.max(1, Math.min(500, optInt(args, "max", 80)));
        final var g = codeGraph.graph();
        if (g.symbols().isEmpty() && g.edges().isEmpty()) {
            return ToolResult.ok("Structural code graph is empty.");
        }
        final var target = resolveExact(symbol, g);
        if (target == null) {
            return ToolResult.error("Symbol not found: " + symbol + ". Grep the graph docs with the grep tool (path="
                    + codeGraph.storeRoot().resolve("docs") + ") to locate a symbol.");
        }

        final var sb = new StringBuilder();
        var count = 0;
        final var visited = new HashSet<String>();
        final Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[] {target, "0"});
        visited.add(target);
        while (!queue.isEmpty() && count < max) {
            final var cur = queue.poll();
            final var name = cur[0];
            final var level = Integer.parseInt(cur[1]);
            if (level > 0) {
                sb.append("  ".repeat(level - 1)).append('-').append(name).append('\n');
                count++;
                if (count >= max) {
                    break;
                }
            }
            if (level >= depth) {
                continue;
            }
            final var neighbors = neighbors(name, direction, g);
            for (final var n : neighbors) {
                if (visited.add(n)) {
                    queue.add(new String[] {n, Integer.toString(level + 1)});
                }
            }
        }
        sb.append("\ntraced ").append(symbol).append(" down to depth ").append(depth);
        if (count >= max) {
            sb.append(" (capped at ").append(max).append(" edges)");
        }
        sb.append('\n');
        return ToolResult.ok(sb.toString());
    }

    private String resolveExact(final String symbol, final GraphStore g) {
        for (final var s : g.symbols()) {
            if (symbol.equals(s.fqn())) {
                return s.fqn();
            }
        }
        for (final var s : g.symbols()) {
            final var fqn = s.fqn();
            if (fqn != null && fqn.endsWith("." + symbol)) {
                return fqn;
            }
        }
        return null;
    }

    private List<String> neighbors(final String name, final String direction, final GraphStore g) {
        final var dir = direction == null ? "both" : direction.toLowerCase(java.util.Locale.ROOT);
        final var out = new ArrayList<String>();
        if ("callers".equals(dir) || "both".equals(dir)) {
            for (final var e : g.edges()) {
                if (e.type() == GraphModel.EdgeType.CALLS && name.equals(e.to()) && e.from() != null) {
                    out.add(e.from());
                }
            }
        }
        if ("callees".equals(dir) || "both".equals(dir)) {
            for (final var e : g.edges()) {
                if (e.type() == GraphModel.EdgeType.CALLS && name.equals(e.from()) && e.to() != null) {
                    out.add(e.to());
                }
            }
        }
        final var seen = new HashSet<String>();
        out.removeIf(n -> !seen.add(n));
        return out;
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
