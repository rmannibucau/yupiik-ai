package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.StructuralCodeGraphConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralCodeGraphTest {

    @TempDir
    Path temp;

    @Test
    void buildsRefreshAndPersists() throws Exception {
        final var project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                }
                """);
        Files.createDirectory(project.resolve("other"));
        Files.writeString(project.resolve("other/Bar.java"), """
                package demo.sub;
                public class Bar {
                    public void go() { new demo.Foo().add(1, 2); }
                }
                """);

        final var config = new CodingAgentConfiguration(project.toString(), project.toString(), null, null,
                new LlmConfiguration(3, 500L, 0, 4, true), null, null, null, null, null, null,
                new StructuralCodeGraphConfiguration(true, false, null, null, null, 10_000_000,
                        List.of("java"), ".yca/generated/graph"),
                null, null, null);

        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(config, List.of(new JavaGraphParser()), mapper,
                new StatusReporter(), new ChatClient(config, mapper));

        graph.ensureReady();
        final var built = graph.graph();
        assertEquals(4, built.symbols().size(), "expected Foo + Foo.add + Bar + Bar.go symbols");
        final var types = built.symbols().stream().filter(s -> s.kind() == GraphModel.Kind.TYPE).toList();
        assertEquals(2, types.size());
        assertTrue(built.edges().stream().anyMatch(e -> e.type() == GraphModel.EdgeType.CALLS));

        // store must have been persisted
        final var storeFile = project.resolve(".yca/generated/graph/graph.json");
        assertTrue(Files.isRegularFile(storeFile));

        // refresh: mutating a file removes its old symbols and adds new ones
        Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                    public int sub(int a, int b) { return a - b; }
                }
                """);
        graph.refreshFile(project.resolve("Foo.java"));
        final var after = graph.graph();
        assertTrue(after.symbols().stream().anyMatch(s -> s.fqn().equals("demo.Foo.sub")),
                "expected the added method symbol after refresh");

        // reload from disk must match in-memory state
        final var reloaded = new StructuralCodeGraph(config, List.of(new JavaGraphParser()), mapper,
                new StatusReporter(), new ChatClient(config, mapper));
        reloaded.ensureReady();
        assertEquals(after.symbols().size(), reloaded.graph().symbols().size());
    }

    private static JsonMapper newMapper() {
        return new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(
                new GraphStore$FusionJsonCodec(),
                new GraphModel$Symbol$FusionJsonCodec(),
                new GraphModel$Edge$FusionJsonCodec(),
                new io.yupiik.fusion.json.internal.codec.EnumJsonCodec<>(
                        GraphModel.Kind.class, List.of(GraphModel.Kind.values()), Enum::name),
                new io.yupiik.fusion.json.internal.codec.EnumJsonCodec<>(
                        GraphModel.EdgeType.class, List.of(GraphModel.EdgeType.values()), Enum::name)),
                key -> Optional.empty());
    }
}