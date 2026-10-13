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
package io.yupiik.ia.agent.core.tools;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.codegraph.CodeResolveTool;
import io.yupiik.ia.agent.core.codegraph.GraphStore;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolRegistryTest {

    @TempDir
    static Path fakeRoot;

    private static EmbeddedTool stub(final String name) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "stub " + name;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public ToolResult execute(final Map<String, Object> args) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static StructuralCodeGraph graph(final boolean enabled) {
        return new StructuralCodeGraph() {

            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public GraphStore graph() {
                return GraphStore.empty();
            }

            @Override
            public Path storeRoot() {
                return fakeRoot.resolve("fake-tool-registry-graph");
            }
        };
    }

    private static MavenRepositoryKnowledgeService knowledge(final boolean enabled) {
        return new MavenRepositoryKnowledgeService() {

            @Override
            public boolean knowledgeEnabled() {
                return enabled;
            }

            @Override
            public List<String> indexedArtifacts() {
                return List.of();
            }

            @Override
            public String knowledge(final String coordinate, final String shortName) {
                return "no knowledge indexed for " + coordinate;
            }
        };
    }

    private static ToolRegistry registry(final List<EmbeddedTool> tools, final CodeResolveTool resolve) {
        final var mcp = new McpManager() {
            @Override
            public List<EmbeddedTool> tools() {
                final var all = new ArrayList<EmbeddedTool>(tools);
                if (resolve != null) {
                    all.add(resolve);
                }
                return all;
            }
        };
        return new ToolRegistry(mcp);
    }

    @Test
    void prunesRichToolsWhenResolveUsable() {
        final var resolve = new CodeResolveTool(null, graph(true), knowledge(false));
        final var registry = registry(
                List.of(
                        stub("code_check_graph"),
                        stub("code_find_code"),
                        stub("code_trace_calls"),
                        stub("maven_repository_knowledge"),
                        stub("maven_knowledge_content"),
                        stub("grep")),
                resolve);
        final var wire = registry.toWireTools();
        final var names = wire.stream().map(t -> t.function().name()).collect(toSet());
        assertEquals(Set.of("code_resolve", "maven_knowledge_content", "grep"), names);
    }

    @Test
    void hidesResolveAndRichToolsWhenUnusable() {
        final var resolve = new CodeResolveTool(null, graph(false), knowledge(false));
        final var registry = registry(
                List.of(
                        stub("code_check_graph"),
                        stub("code_trace_calls"),
                        stub("maven_repository_knowledge"),
                        stub("maven_knowledge_content"),
                        stub("grep")),
                resolve);
        final var wire = registry.toWireTools();
        final var names = wire.stream().map(t -> t.function().name()).collect(toSet());
        assertEquals(Set.of("maven_knowledge_content", "grep"), names);
    }

    @Test
    void noResolveBeanLeavesToolsUntouched() {
        final var registry =
                registry(List.of(stub("code_trace_calls"), stub("maven_knowledge_content"), stub("grep")), null);
        final var wire = registry.toWireTools();
        final var names = wire.stream().map(t -> t.function().name()).collect(toSet());
        assertEquals(Set.of("code_trace_calls", "maven_knowledge_content", "grep"), names);
    }

    @Test
    void knowledgeOnlyResolveIsUsable() {
        final var resolve = new CodeResolveTool(null, graph(false), knowledge(true));
        assertTrue(resolve.usable(), "knowledge-only code_resolve should be usable");
        final var registry = registry(
                List.of(stub("code_trace_calls"), stub("maven_repository_knowledge"), stub("maven_knowledge_content")),
                resolve);
        final var names =
                registry.toWireTools().stream().map(t -> t.function().name()).collect(toSet());
        assertEquals(Set.of("code_resolve", "maven_knowledge_content"), names);
    }
}
