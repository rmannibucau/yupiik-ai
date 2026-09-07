package io.yupiik.ia.agent.core.codegraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaGraphParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesTypesMethodsAndCallEdges() throws IOException {
        final var parser = new JavaGraphParser();
        assertTrue(parser.available(), "expected a JDK with javac on the test environment");
        assertEquals(java.util.Set.of("java"), parser.extensions());
        assertTrue(parser.jdkRequired());

        final var src = tempDir.resolve("A.java");
        Files.writeString(src, """
                package sample;
                import java.util.List;
                public class A {
                    private int x;
                    public void run() { BHelper.helper(); }
                    public List<String> list() { return null; }
                }
                """);
        final var model = parser.parse(src);
        assertEquals("java", model.language());

        final var typeNames = model.symbols().stream()
                .filter(s -> s.kind() == GraphModel.Kind.TYPE)
                .map(GraphModel.Symbol::fqn)
                .toList();
        assertTrue(typeNames.contains("sample.A"), "expected the type symbol, got " + typeNames);

        final var methodNames = model.symbols().stream()
                .filter(s -> s.kind() == GraphModel.Kind.METHOD)
                .map(GraphModel.Symbol::fqn)
                .toList();
        assertTrue(methodNames.contains("sample.A.run"), "expected method symbol, got " + methodNames);

        final var edgeTypes = model.edges().stream()
                .map(GraphModel.Edge::type)
                .toList();
        assertTrue(edgeTypes.contains(GraphModel.EdgeType.CALLS), "expected a CALLS edge, got " + edgeTypes);
    }

    @Test
    void parsesImportedTypesNotOnClasspath() throws IOException {
        final var parser = new JavaGraphParser();

        final var src = tempDir.resolve("UsesService.java");
        Files.writeString(src, """
                package sample;
                import not.existing.Service;
                public class UsesService {
                    public Service newService() { return new Service(); }
                }
                """);
        final var model = parser.parse(src);

        final var importEdges = model.edges().stream()
                .filter(e -> e.type() == GraphModel.EdgeType.IMPORTS)
                .map(GraphModel.Edge::to)
                .toList();
        assertTrue(importEdges.contains("not.existing.Service"),
                "expected the off-classpath type to be seen as imported, got " + importEdges);

        final var usesEdges = model.edges().stream()
                .filter(e -> e.type() == GraphModel.EdgeType.USES)
                .map(GraphModel.Edge::to)
                .toList();
        assertTrue(usesEdges.contains("not.existing.Service"),
                "expected the off-classpath type to be seen as referenced, got " + usesEdges);
    }
}