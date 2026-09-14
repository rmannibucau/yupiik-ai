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
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single structural tool exposed to the model. It fuses the local structural code graph (implementors,
 * extensions, callers, callees, usages with file locations) and the optional Maven artifact knowledge
 * (which dependency provides a class) behind one {@link EmbeddedTool}, so a relation question is answered
 * without alternating between many graph/knowledge tools. Its {@code description} and {@code schema} are
 * computed from the active configuration so the model only ever sees the data sources that are actually on.
 */
@ApplicationScoped
public class CodeResolveTool implements EmbeddedTool {

    private static final Set<String> PRUNED_REMOTE_TOOLS =
            Set.of("code_check_graph", "code_find_code", "code_trace_calls", "maven_repository_knowledge");

    private final CodingAgentConfiguration configuration;
    private final StructuralCodeGraph codeGraph;
    private final MavenRepositoryKnowledgeService knowledge;

    public CodeResolveTool(
            final CodingAgentConfiguration configuration,
            final StructuralCodeGraph codeGraph,
            final MavenRepositoryKnowledgeService knowledge) {
        this.configuration = configuration;
        this.codeGraph = codeGraph;
        this.knowledge = knowledge;
    }

    protected CodeResolveTool() {
        this.configuration = null;
        this.codeGraph = null;
        this.knowledge = null;
    }

    /**
     * The names of the granular tools that this merged tool subsumes when it is active; the wire-list builder
     * prunes them so the model sees a single structural lane.
     */
    public static Set<String> prunedRichToolNames() {
        return PRUNED_REMOTE_TOOLS;
    }

    /** Whether any structural data source is usable for the current configuration/project. */
    public boolean usable() {
        final boolean graph = codeGraph != null && codeGraph.enabled();
        final boolean know = knowledge != null && knowledge.knowledgeEnabled();
        return graph || know;
    }

    /** Whether the graph source is live (affects description and whether a graph branch runs). */
    private boolean graphLive() {
        return codeGraph != null && codeGraph.enabled();
    }

    /** Whether the knowledge source is live. */
    private boolean knowledgeLive() {
        return knowledge != null && knowledge.knowledgeEnabled();
    }

    @Override
    public String name() {
        return "code_resolve";
    }

    @Override
    public String description() {
        final boolean graph = graphLive();
        final boolean know = knowledgeLive();
        final var bases = new ArrayList<String>();
        if (graph) {
            bases.add("local code graph (implementors, extensions, callers, callees, usages with file:line)");
        }
        if (know) {
            bases.add("Maven artifact knowledge (which indexed dependency provides a class)");
        }
        if (bases.isEmpty()) {
            return "Resolve a symbol's relations. No structural data source is active in this session.";
        }
        return "Resolve a symbol against " + String.join(" and ", bases) + ". "
                + "Use op=list to enumerate available indexed artifacts, and op=resolve to get the relations "
                + "of a symbol (children/implementors, callers, callees, usages) with file locations so you do "
                + "not need to re-trace. On demand, read a specific artifact's knowledge with maven_knowledge_content.";
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
        final var props = new HashMap<String, Object>();
        props.put(
                "op",
                Schemas.str("Operation: `resolve` (default) returns relations of a symbol, `list` enumerates the "
                        + "indexed Maven artifacts whose knowledge is available."));
        props.put("symbol", Schemas.str("Fully-qualified symbol name to resolve (used by op=resolve)."));
        props.put(
                "relation",
                Schemas.str("Relation to follow (op=resolve only): implements, extends, callers, callees, uses. "
                        + "Default: implements+extends."));
        props.put("artifact", Schemas.str("Optional artifact coordinate (g:a:v) hint to narrow the knowledge lookup."));
        props.put("depth", Schemas.intProp("Max BFS depth, default 3."));
        props.put("max", Schemas.intProp("Max number of results, default 80."));
        return Schemas.object(props, "op");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var op = arg(args, "op");
        if (op != null && !op.isBlank() && "list".equalsIgnoreCase(op)) {
            return list();
        }
        return resolve(args);
    }

    private ToolResult list() {
        if (!usable()) {
            return ToolResult.ok("No structural data source is active (no code graph and no Maven knowledge).");
        }
        final var gavs = knowledge == null ? List.of() : knowledge.indexedArtifacts();
        if (gavs.isEmpty()) {
            return ToolResult.ok("No Maven artifact knowledge is indexed yet. Run a first prompt to index project "
                    + "dependencies, or verify the project has a build file with resolvable dependencies.");
        }
        final var sb = new StringBuilder();
        sb.append(gavs.size()).append(" indexed artifact(s) with knowledge available:\n");
        for (final var g : gavs) {
            sb.append("- ").append(g).append('\n');
        }
        return ToolResult.ok(sb.toString());
    }

    private ToolResult resolve(final Map<String, Object> args) {
        final var symbol = arg(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return ToolResult.error("symbol is required for op=resolve");
        }
        final var relation = arg(args, "relation");
        final var artifact = arg(args, "artifact");
        final var edgeTypes = relationsToEdgeTypes(relation);
        final var depth = (int) Math.clamp(optInt(args, "depth", 3), 1, 8);
        final var max = (int) Math.clamp(optInt(args, "max", 80), 1, 500);

        final var sb = new StringBuilder();
        int graphHits = 0;
        if (graphLive()) {
            graphHits = appendGraph(sb, symbol, relation, edgeTypes, depth, max);
        }
        if (knowledgeLive()) {
            appendKnowledge(sb, symbol, artifact, graphHits);
        }
        if (graphHits == 0 && knowledgeLive() && (artifact == null || artifact.isBlank())) {
            sb.append('\n')
                    .append("The symbol was not found locally. Use op=list to see which indexed dependencies have "
                            + "knowledge, and maven_knowledge_content to read one.");
        }
        return ToolResult.ok(sb.isEmpty() ? "(no result)" : sb.toString());
    }

    /**
     * Append the graph relation trace for {@code symbol}. Returns the number of distinct symbols found.
     */
    private int appendGraph(
            final StringBuilder sb,
            final String symbol,
            final String relation,
            final Set<GraphModel.EdgeType> edgeTypes,
            final int depth,
            final int max) {
        final var g = codeGraph.graph();
        if (g.symbols().isEmpty() && g.edges().isEmpty()) {
            sb.append("code graph: empty (nothing indexed for this project).\n");
            return 0;
        }
        final var target = resolveExact(symbol, g);
        if (target == null) {
            sb.append("code graph: symbol not found locally: ").append(symbol).append('\n');
            return 0;
        }
        final var targetSymbol = symbolOf(g, target);
        final var onlyImplements = "implements".equalsIgnoreCase(relation != null ? relation.trim() : "");
        if (onlyImplements && targetSymbol != null && targetSymbol.kind() != GraphModel.Kind.INTERFACE) {
            sb.append("code graph: ")
                    .append(target)
                    .append(" is a ")
                    .append(targetSymbol.kind().name().toLowerCase(Locale.ROOT))
                    .append(", not an interface, so nothing implements it directly; trace the interfaces it "
                            + "references (e.g. its field/method return types) for implementors.\n");
            return 0;
        }
        final var direction = directionFor(relation, edgeTypes);
        final var neighborIndex = buildNeighborIndex(g, edgeTypes, direction);
        final var byFqn = new HashMap<String, GraphModel.Symbol>();
        for (final var s : g.symbols()) {
            byFqn.put(s.fqn(), s);
        }
        final var found = new ArrayList<String>();
        final var visited = new HashSet<String>();
        final var queue = new ArrayDeque<String[]>();
        var count = 0;
        visited.add(target);
        queue.add(new String[] {target, "0"});
        while (!queue.isEmpty() && count < max) {
            final var cur = queue.removeFirst();
            final var name = cur[0];
            final var level = Integer.parseInt(cur[1]);
            if (level > 0) {
                found.add(name);
                count++;
                if (count >= max) {
                    break;
                }
            }
            if (level >= depth) {
                continue;
            }
            for (final var n : neighborIndex.getOrDefault(name, Set.of())) {
                if (visited.add(n)) {
                    queue.add(new String[] {n, String.valueOf(level + 1)});
                }
            }
        }
        sb.append("code graph: resolved ")
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
            } else {
                sb.append("- ")
                        .append(s.kind().name().toLowerCase(Locale.ROOT))
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
        }
        if (count >= max) {
            sb.append("(truncated to ").append(max).append(" edges; raise max or narrow relation for more)\n");
        }
        return found.size();
    }

    /**
     * Append a bounded note about which indexed dependency (if any) provides {@code symbol}, matching by
     * package/fqn containment against the indexed artifacts' knowledge index entry points.
     */
    private void appendKnowledge(
            final StringBuilder sb, final String symbol, final String artifactHint, final int graphHits) {
        if (!knowledgeLive()) {
            return;
        }
        final var gavs = knowledge.indexedArtifacts();
        if (gavs.isEmpty()) {
            return;
        }
        String match = null;
        final var want = symbol.trim();
        final var cap = Math.min(gavs.size(), 60);
        for (var i = 0; i < cap && match == null; i++) {
            final var gav = gavs.get(i);
            if (artifactHint != null && !artifactHint.isBlank() && !gav.contains(artifactHint)) {
                continue;
            }
            final var index = knowledge.knowledge(gav, "index");
            if (index != null && index.contains(want)) {
                match = gav;
            }
        }
        if (match != null) {
            sb.append("artifact knowledge: ")
                    .append(symbol)
                    .append(" is provided/listed by ")
                    .append(match)
                    .append(". Read it with maven_knowledge_content (gav=")
                    .append(match)
                    .append(").\n");
        } else if (graphHits == 0 && (artifactHint == null || artifactHint.isBlank())) {
            sb.append("artifact knowledge: no indexed dependency matches ")
                    .append(symbol)
                    .append(" in the first ")
                    .append(cap)
                    .append(" artifacts; use op=list to enumerate.\n");
        }
    }

    private static Set<GraphModel.EdgeType> relationsToEdgeTypes(final String relation) {
        if (relation == null || relation.isBlank()) {
            return EnumSet.of(GraphModel.EdgeType.IMPLEMENTS, GraphModel.EdgeType.EXTENDS);
        }
        final var r = relation.trim().toLowerCase(Locale.ROOT);
        switch (r) {
            case "implements":
                return EnumSet.of(GraphModel.EdgeType.IMPLEMENTS);
            case "extends":
                return EnumSet.of(GraphModel.EdgeType.EXTENDS);
            case "callers":
                return EnumSet.of(GraphModel.EdgeType.CALLS);
            case "callees":
                return EnumSet.of(GraphModel.EdgeType.CALLS);
            case "uses":
                return EnumSet.of(GraphModel.EdgeType.USES);
            case "imports":
                return EnumSet.of(GraphModel.EdgeType.IMPORTS);
            case "all":
                return EnumSet.allOf(GraphModel.EdgeType.class);
            default:
                return EnumSet.of(GraphModel.EdgeType.IMPLEMENTS, GraphModel.EdgeType.EXTENDS);
        }
    }

    private static String edgeTypesLabel(final Set<GraphModel.EdgeType> edgeTypes) {
        return edgeTypes.stream()
                .map(e -> e.name().toLowerCase(Locale.ROOT))
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("-");
    }

    private static GraphModel.Symbol symbolOf(final GraphStore g, final String fqn) {
        for (final var s : g.symbols()) {
            if (fqn.equals(s.fqn())) {
                return s;
            }
        }
        return null;
    }

    /**
     * For a hierarchy (children) relation we must walk only the direction that maps a supertype to its
     * subtypes ({@code e.to() == current -> e.from()}, the "callers" branch of {@code buildNeighborIndex}).
     * Walking "both" also follows the reverse direction and pulls in a subtype's own unrelated supertype
     * interfaces (e.g. {@code java.io.Serializable}), inflating the result. A "callers"/"callees" relation
     * keeps its natural single direction; the ambiguous/use/import relations fall back to "both".
     */
    private static String directionFor(final String relation, final Set<GraphModel.EdgeType> edgeTypes) {
        if (relation != null && !relation.isBlank()) {
            final var r = relation.trim().toLowerCase(Locale.ROOT);
            switch (r) {
                case "callees":
                    return "callees";
                case "callers":
                    return "callers";
                case "uses":
                case "imports":
                case "all":
                    return "both";
                default:
                    return "callers";
            }
        }
        if (edgeTypes.size() == 1 && edgeTypes.contains(GraphModel.EdgeType.CALLS)) {
            return "both";
        }
        return "callers";
    }

    private static String resolveExact(final String symbol, final GraphStore g) {
        for (final var s : g.symbols()) {
            if (symbol.equals(s.fqn())) {
                return s.fqn();
            }
        }
        final var candidates = new ArrayList<String>();
        for (final var s : g.symbols()) {
            final var fqn = s.fqn();
            if (fqn != null && fqn.endsWith('.' + symbol)) {
                candidates.add(fqn);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static Map<String, Set<String>> buildNeighborIndex(
            final GraphStore g, final Set<GraphModel.EdgeType> edgeTypes, final String direction) {
        final var wantCallers = "callers".equals(direction) || "both".equals(direction);
        final var wantCallees = "callees".equals(direction) || "both".equals(direction);
        final var index = new HashMap<String, Set<String>>();
        for (final var e : g.edges()) {
            if (!edgeTypes.contains(e.type())) {
                continue;
            }
            if (wantCallers && e.to() != null && e.from() != null) {
                index.computeIfAbsent(e.to(), n -> new HashSet<>()).add(e.from());
            }
            if (wantCallees && e.from() != null && e.to() != null) {
                index.computeIfAbsent(e.from(), n -> new HashSet<>()).add(e.to());
            }
        }
        return index;
    }

    private static String arg(final Map<String, Object> args, final String key) {
        final var v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static int optInt(final Map<String, Object> args, final String key, final int dflt) {
        final var v = args.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Long.parseLong(v.toString());
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }
}
