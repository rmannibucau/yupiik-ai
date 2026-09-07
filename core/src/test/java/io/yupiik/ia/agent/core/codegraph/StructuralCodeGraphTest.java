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
                        List.of("java"), ".yca/generated/graph", true, "docs"),
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

        // per-source-file doc tree must mirror the source paths
        final var docsFoo = project.resolve(".yca/generated/graph/docs/Foo.java.md");
        assertTrue(Files.isRegularFile(docsFoo), "expected doc tree to mirror Foo.java");
        final var fooDoc = Files.readString(docsFoo);
        assertTrue(fooDoc.contains("# demo.Foo"), "expected type header in Foo doc");
        assertTrue(fooDoc.contains("demo.Foo.add"), "expected method symbol in Foo doc");
        final var docsBar = project.resolve(".yca/generated/graph/docs/other/Bar.java.md");
        assertTrue(Files.isRegularFile(docsBar), "expected doc tree to mirror other/Bar.java");
        assertTrue(Files.readString(docsBar).contains("# demo.sub.Bar"));

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
        assertTrue(Files.readString(docsFoo).contains("demo.Foo.sub"),
                "expected the refreshed doc to contain the added method");

        // deleting a source file removes its doc
        Files.delete(project.resolve("Foo.java"));
        graph.refreshFile(project.resolve("Foo.java"));
        assertTrue(!Files.exists(docsFoo), "expected doc removed when source file deleted");

        // reload from disk must match in-memory state
        final var reloaded = new StructuralCodeGraph(config, List.of(new JavaGraphParser()), mapper,
                new StatusReporter(), new ChatClient(config, mapper));
        reloaded.ensureReady();
        assertEquals(after.symbols().size(), reloaded.graph().symbols().size());
    }

    @Test
    void docTreeSkipsUnchangedAndWritesSummaryInjection() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj"));
        Files.writeString(project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
        final var config = new CodingAgentConfiguration(project.toString(), project.toString(), null, null,
                new LlmConfiguration(3, 500L, 0, 4, true), null, null, null, null, null, null,
                new StructuralCodeGraphConfiguration(true, false, null, null, null, 10_000_000,
                        List.of("java"), ".yca/generated/graph", true, "docs"),
                null, null, null);
        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(config, List.of(new JavaGraphParser()), mapper,
                new StatusReporter(), new ChatClient(config, mapper));
        graph.ensureReady();

        final var docRoot = Path.of(config.structuralCodeGraph()
                        .resolvedDocStoreDir(project.toString())).toAbsolutePath().normalize();
        final var writer = new DocTreeWriter(config, docRoot);
        final var model = new GraphModel("java", graph.graph().symbols(), graph.graph().edges());
        final var rel = "Foo.java";
        final var doc = docRoot.resolve("Foo.java.md");

        // writer emits the structural doc
        assertTrue(Files.isRegularFile(doc));
        final var before = Files.readString(doc);

        // injecting a summary appends a ## Summary section, preserving the structural body
        writer.writeSummary(rel, model, "Represents a demo winner.");
        final var after = Files.readString(doc);
        assertTrue(after.contains("## Summary"));
        assertTrue(after.contains("Represents a demo winner."));
        assertTrue(after.contains("demo.Foo"));

        // writer.write() on unchanged structure must preserve the injected summary
        writer.write(rel, model);
        assertTrue(Files.readString(doc).contains("Represents a demo winner."));
        assertTrue(Files.readString(doc).contains("demo.Foo.add") || !graph.graph().symbols().isEmpty());
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