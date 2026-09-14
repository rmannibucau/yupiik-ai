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
        return "Find symbols (types, interfaces, enums, methods, fields) by name in the structural code graph, "
                + "returning their kind, fully-qualified name and file line span. Use it to locate a symbol before "
                + "tracing its relations with code_trace_calls.";
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
                        "query",
                                Schemas.str("Name or name fragment to search, eg. `RowSet` or `ToolExecutionService`."),
                        "kind",
                                Schemas.str("Optional symbol kind filter: TYPE, METHOD, FIELD, FUNCTION, CONSTRUCTOR, "
                                        + "INTERFACE, ENUM, ANNOTATION, MODULE."),
                        "max", Schemas.intProp("Maximum number of results, default 40.")),
                "query");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var query = arg(args, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("query is required");
        }
        final var kind = arg(args, "kind");
        final var qKind = kind == null || kind.isBlank() ? null : kind.toUpperCase(Locale.ROOT);
        final var max = (int) Math.max(1, Math.min(200, optInt(args, "max", 40)));
        final var q = query.toLowerCase(Locale.ROOT);
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
            final var fqn = s.fqn();
            final var fqnLower = fqn == null ? "" : fqn.toLowerCase(Locale.ROOT);
            final var simpleLower = simple(fqn) == null ? "" : simple(fqn).toLowerCase(Locale.ROOT);
            if (!fqnLower.contains(q) && !simpleLower.contains(q)) {
                continue;
            }
            count++;
            sb.append(s.kind())
                    .append("  ")
                    .append(s.fqn())
                    .append("  (")
                    .append(file(s))
                    .append(':')
                    .append(s.startLine())
                    .append('-')
                    .append(s.endLine())
                    .append(")\n");
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
            return null;
        }
        final var dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private String file(final GraphModel.Symbol s) {
        return s.file() == null || s.file().isBlank() ? "-" : s.file();
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
