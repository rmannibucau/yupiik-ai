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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeResolveToolTest {

    private static GraphStore store() {
        return new GraphStore(
                List.of(
                        new GraphModel.Symbol("org.hop.RowSet", GraphModel.Kind.INTERFACE, "RowSet.java", 1, 5, null),
                        new GraphModel.Symbol(
                                "org.hop.RowSetImpl", GraphModel.Kind.TYPE, "RowSetImpl.java", 1, 5, null),
                        new GraphModel.Symbol("org.hop.Producer", GraphModel.Kind.TYPE, "Producer.java", 1, 5, null)),
                List.of(
                        new GraphModel.Edge("org.hop.RowSetImpl", "org.hop.RowSet", GraphModel.EdgeType.IMPLEMENTS),
                        new GraphModel.Edge("org.hop.Producer", "org.hop.RowSet", GraphModel.EdgeType.USES),
                        new GraphModel.Edge("org.hop.Producer", "org.hop.RowSetImpl", GraphModel.EdgeType.CALLS)),
                Map.of(),
                System.currentTimeMillis());
    }

    private static StructuralCodeGraph graph(final GraphStore store) {
        return new StructuralCodeGraph() {

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public GraphStore graph() {
                if (store == null) {
                    return GraphStore.empty();
                }
                return store;
            }

            @Override
            public Path storeRoot() {
                return Path.of("/tmp/fake-code-resolve-graph");
            }
        };
    }

    private static StructuralCodeGraph disabledGraph() {
        return new StructuralCodeGraph() {

            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public GraphStore graph() {
                return GraphStore.empty();
            }

            @Override
            public Path storeRoot() {
                return Path.of("/tmp/fake-code-resolve-graph");
            }
        };
    }

    private static MavenRepositoryKnowledgeService knowledge(
            final boolean enabled, final List<String> gavs, final Map<String, String> contentByGav) {
        return new MavenRepositoryKnowledgeService() {

            @Override
            public boolean knowledgeEnabled() {
                return enabled;
            }

            @Override
            public List<String> indexedArtifacts() {
                return gavs;
            }

            @Override
            public String knowledge(final String coordinate, final String shortName) {
                final var content = contentByGav.get(coordinate);
                return content == null ? "no knowledge indexed for " + coordinate : content;
            }
        };
    }

    private static MavenRepositoryKnowledgeService knowledge(final boolean enabled, final List<String> gavs) {
        return knowledge(enabled, gavs, gavContent("org.hop.RowSet"));
    }

    private static Map<String, String> gavContent(final String symbol) {
        return Map.of("g.com:artifact-a:1.0", "## Entry points\n" + symbol + "\n");
    }

    private static CodeResolveTool tool(
            final StructuralCodeGraph graph, final MavenRepositoryKnowledgeService knowledge) {
        return new CodeResolveTool(null, graph, knowledge);
    }

    @Test
    void concreteClassImplementsYieldsTerminalVerdict() {
        final var store = new GraphStore(
                List.of(
                        new GraphModel.Symbol(
                                "org.hop.PipelineRunConfiguration", GraphModel.Kind.TYPE, "Cfg.java", 1, 5, null),
                        new GraphModel.Symbol("org.hop.CfgImpl", GraphModel.Kind.TYPE, "CfgImpl.java", 1, 5, null)),
                List.of(new GraphModel.Edge(
                        "org.hop.CfgImpl", "org.hop.PipelineRunConfiguration", GraphModel.EdgeType.EXTENDS)),
                Map.of(),
                System.currentTimeMillis());
        final var tool = tool(graph(store), knowledge(false, List.of()));
        final var out = tool.execute(
                        Map.of("op", "resolve", "symbol", "org.hop.PipelineRunConfiguration", "relation", "implements"))
                .output();
        assertTrue(out.contains("is a type, not an interface"), out);
        assertFalse(out.contains("CfgImpl"), "concrete class must have no implements results");
    }

    @Test
    void implementsDoesNotTransitivelyLeakSubtypeOwnSupertypes() {
        final var store = new GraphStore(
                List.of(
                        new GraphModel.Symbol("org.hop.RowSet", GraphModel.Kind.INTERFACE, "RowSet.java", 1, 5, null),
                        new GraphModel.Symbol(
                                "org.hop.RowSetImpl", GraphModel.Kind.TYPE, "RowSetImpl.java", 1, 5, null),
                        new GraphModel.Symbol(
                                "java.io.Serializable", GraphModel.Kind.INTERFACE, "Serializable.java", 1, 5, null)),
                List.of(
                        new GraphModel.Edge("org.hop.RowSetImpl", "org.hop.RowSet", GraphModel.EdgeType.IMPLEMENTS),
                        new GraphModel.Edge(
                                "org.hop.RowSetImpl", "java.io.Serializable", GraphModel.EdgeType.IMPLEMENTS)),
                Map.of(),
                System.currentTimeMillis());
        final var tool = tool(graph(store), knowledge(false, List.of()));
        final var out = tool.execute(Map.of("op", "resolve", "symbol", "org.hop.RowSet", "relation", "implements"))
                .output();
        assertTrue(out.contains("type org.hop.RowSetImpl (RowSetImpl.java:1-5)"), out);
        assertFalse(
                out.contains("java.io.Serializable"),
                "the subtype's own unrelated supertypes must not leak into the implements result: " + out);
    }

    @Test
    void graphBackedResolveReturnsImplementors() {
        final var tool = tool(graph(store()), knowledge(false, List.of()));
        final var out = tool.execute(Map.of("op", "resolve", "symbol", "org.hop.RowSet", "relation", "implements"))
                .output();
        assertTrue(out.contains("code graph: resolved org.hop.RowSet (implements)"));
        assertTrue(out.contains("type org.hop.RowSetImpl (RowSetImpl.java:1-5)"));
        assertFalse(out.contains("artifact knowledge"));
    }

    @Test
    void graphAndKnowledgeMergeBothBranchesPresent() {
        final var knowledge = knowledge(true, List.of("g.com:artifact-a:1.0"));
        final var tool = tool(graph(store()), knowledge);
        final var out = tool.execute(Map.of("op", "resolve", "symbol", "org.hop.RowSet", "relation", "implements"))
                .output();
        assertTrue(out.contains("code graph: resolved org.hop.RowSet (implements)"));
        assertTrue(out.contains("type org.hop.RowSetImpl (RowSetImpl.java:1-5)"));
        assertTrue(out.contains("artifact knowledge: org.hop.RowSet is provided/listed by g.com:artifact-a:1.0"));
    }

    @Test
    void knowledgeProviderNoteIsAppendedWhenGraphHasNoHit() {
        final var knowledge = knowledge(true, List.of("g.com:artifact-a:1.0"));
        final var tool = tool(graph(store()), knowledge);
        final var out = tool.execute(Map.of("op", "resolve", "symbol", "org.hop.RowSet", "relation", "uses"))
                .output();
        assertTrue(out.contains("artifact knowledge: org.hop.RowSet is provided/listed by g.com:artifact-a:1.0"));
    }

    @Test
    void listEnumeratesIndexedArtifacts() {
        final var tool =
                tool(disabledGraph(), knowledge(true, List.of("g.com:artifact-a:1.0", "g.com:artifact-b:2.0")));
        final var out = tool.execute(Map.of("op", "list")).output();
        assertTrue(out.contains("2 indexed artifact(s)"));
        assertTrue(out.contains("- g.com:artifact-a:1.0"));
        assertTrue(out.contains("- g.com:artifact-b:2.0"));
    }

    @Test
    void listReportsNoKnowledgeIndexedYet() {
        final var tool = tool(disabledGraph(), knowledge(true, List.of()));
        final var out = tool.execute(Map.of("op", "list")).output();
        assertTrue(out.contains("No Maven artifact knowledge is indexed yet"));
    }

    @Test
    void listInactiveSourcesAreRejected() {
        final var tool = tool(disabledGraph(), knowledge(false, List.of()));
        final var out = tool.execute(Map.of("op", "list")).output();
        assertTrue(out.contains("No structural data source is active"));
    }

    @Test
    void unknownLocalSymbolStillMatchesKnowledgeProvider() {
        final var knowledge = knowledge(true, List.of("g.com:artifact-a:1.0"), gavContent("org.hop.External"));
        final var tool = tool(graph(store()), knowledge);
        final var out = tool.execute(Map.of("op", "resolve", "symbol", "org.hop.External"))
                .output();
        assertTrue(out.contains("code graph: symbol not found locally: org.hop.External"));
        assertTrue(out.contains("artifact knowledge: org.hop.External is provided/listed by g.com:artifact-a:1.0"));
    }

    @Test
    void unknownSymbolWithNoKnowledgeSuggestsList() {
        final var knowledge = knowledge(true, List.of("g.com:artifact-a:1.0"), gavContent("org.hop.RowSet"));
        final var tool = tool(graph(store()), knowledge);
        final var out = tool.execute(Map.of("op", "resolve", "symbol", "org.hop.Unknown"))
                .output();
        assertTrue(out.contains("code graph: symbol not found locally: org.hop.Unknown"));
        assertTrue(out.contains("Use op=list"));
    }

    @Test
    void emptyGraphReportsNothingIndexed() {
        final var tool = tool(graph(GraphStore.empty()), knowledge(false, List.of()));
        final var out = tool.execute(Map.of("op", "resolve", "symbol", "org.hop.RowSet"))
                .output();
        assertTrue(out.contains("code graph: empty"));
    }

    @Test
    void symbolIsRequiredOnResolve() {
        final var tool = tool(disabledGraph(), knowledge(false, List.of()));
        final var result = tool.execute(Map.of("op", "resolve", "symbol", "", "relation", "implements"));
        assertFalse(result.success());
        assertTrue(result.output().contains("symbol is required"));
    }

    @Test
    void descriptionReflectsLiveSources() {
        assertEquals(
                "Resolve a symbol's relations. No structural data source is active in this session.",
                tool(disabledGraph(), knowledge(false, List.of())).description());
        final var both = tool(graph(store()), knowledge(true, List.of())).description();
        assertTrue(both.contains("local code graph"));
        assertTrue(both.contains("Maven artifact knowledge"));
    }

    @Test
    void richToolNamesArePruned() {
        assertEquals(
                Set.of("code_check_graph", "code_find_code", "code_trace_calls", "maven_repository_knowledge"),
                CodeResolveTool.prunedRichToolNames());
    }
}
