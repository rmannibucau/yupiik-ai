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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadSymbolToolTest {

    @Test
    void slicesSymbolSpanWithContextLines(@TempDir final Path root) throws IOException {
        final var src = root.resolve("src/Sample.java");
        Files.createDirectories(src.getParent());
        Files.writeString(
                src,
                "package p;\npublic class Sample {\n    int add(int a, int b) {\n        return a + b;\n    }\n}\n");
        final var tool = new ReadSymbolTool(fake(
                root,
                new GraphStore(
                        List.of(new GraphModel.Symbol(
                                "p.Sample#add",
                                GraphModel.Kind.METHOD,
                                "src/Sample.java",
                                3,
                                5,
                                "int add(int a, int b)")),
                        List.of(),
                        Map.of(),
                        1L)));

        final var result = tool.execute(Map.of("symbol", "p.Sample#add", "context", "1"));
        assertTrue(result.success(), result.output());
        final var text = result.output();
        assertTrue(text.contains("symbol METHOD p.Sample#add (src/Sample.java:3-5)"), text);
        assertTrue(text.contains("signature int add(int a, int b)"), text);
        // context=1 -> lines 2..6 rendered, read numbering format
        assertTrue(text.contains("     2\tpublic class Sample {"), text);
        assertTrue(text.contains("     5\t    }"), text);
    }

    @Test
    void resolvesByUniqueSimpleNameAndFullFqn(@TempDir final Path root) throws IOException {
        final var src = root.resolve("src/Foo.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package q;\npublic class Foo {\n}\n");
        final var tool = new ReadSymbolTool(fake(
                root,
                new GraphStore(
                        List.of(new GraphModel.Symbol("q.Foo", GraphModel.Kind.TYPE, "src/Foo.java", 2, 2, null)),
                        List.of(),
                        Map.of(),
                        1L)));

        final var bySimple = tool.execute(Map.of("symbol", "Foo"));
        assertTrue(bySimple.success(), bySimple.output());
        assertTrue(bySimple.output().contains("symbol TYPE q.Foo (src/Foo.java:2-2)"), bySimple.output());

        final var byFqn = tool.execute(Map.of("symbol", "q.Foo"));
        assertTrue(byFqn.success());
        assertTrue(byFqn.output().contains("public class Foo"), byFqn.output());
    }

    @Test
    void resolvesHashFormMethodWhenIndexStoresDotForm(@TempDir final Path root) throws IOException {
        final var src = root.resolve("src/Sample.java");
        Files.createDirectories(src.getParent());
        Files.writeString(
                src,
                "package p;\npublic class Sample {\n    int add(int a, int b) {\n        return a + b;\n    }\n}\n");
        final var tool = new ReadSymbolTool(fake(
                root,
                new GraphStore(
                        List.of(new GraphModel.Symbol(
                                "p.Sample.add",
                                GraphModel.Kind.METHOD,
                                "src/Sample.java",
                                3,
                                5,
                                "int add(int a, int b)")),
                        List.of(),
                        Map.of(),
                        1L)));

        final var result = tool.execute(Map.of("symbol", "p.Sample#add", "context", "0"));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("symbol METHOD p.Sample.add (src/Sample.java:3-5)"), result.output());
        assertTrue(result.output().contains("signature int add(int a, int b)"), result.output());
    }

    @Test
    void hashFormDisambiguatesSameNamedMethods(@TempDir final Path root) throws IOException {
        final var a = root.resolve("src/A.java");
        final var b = root.resolve("src/B.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, "package q;\npublic class A {\n    void run() {\n    }\n}\n");
        Files.writeString(b, "package q;\npublic class B {\n    void run() {\n    }\n}\n");
        final var tool = new ReadSymbolTool(fake(
                root,
                new GraphStore(
                        List.of(
                                new GraphModel.Symbol(
                                        "q.A.run", GraphModel.Kind.METHOD, "src/A.java", 3, 4, "void run()"),
                                new GraphModel.Symbol(
                                        "q.B.run", GraphModel.Kind.METHOD, "src/B.java", 3, 4, "void run()")),
                        List.of(),
                        Map.of(),
                        1L)));

        final var aRes = tool.execute(Map.of("symbol", "q.A#run", "context", "0"));
        assertTrue(aRes.success(), aRes.output());
        assertTrue(aRes.output().contains("symbol METHOD q.A.run (src/A.java:3-4)"), aRes.output());

        final var bRes = tool.execute(Map.of("symbol", "q.B#run", "context", "0"));
        assertTrue(bRes.success(), bRes.output());
        assertTrue(bRes.output().contains("symbol METHOD q.B.run (src/B.java:3-4)"), bRes.output());
    }

    @Test
    void shortClassPrefixDisambiguatesMethodAgainstGlobalCollision(@TempDir final Path root) throws IOException {
        final var src = root.resolve("src/ModelsResponse.java");
        Files.createDirectories(src.getParent());
        Files.writeString(
                src,
                "package com.acme.llm;\n"
                        + "public class ModelsResponse {\n"
                        + "    public record Model(Integer maxTokens) {\n"
                        + "        Integer a() { return maxTokens; }\n"
                        + "        Integer b() { return maxTokens; }\n"
                        + "        public Integer maxTokens() { return maxTokens; }\n"
                        + "    }\n"
                        + "}\n");
        final var other = root.resolve("src/Foo.java");
        Files.writeString(
                other, "package com.other;\npublic class Foo {\n    public Integer maxTokens() { return 0; }\n}\n");
        final var tool = new ReadSymbolTool(fake(
                root,
                new GraphStore(
                        List.of(
                                new GraphModel.Symbol(
                                        "com.acme.llm.ModelsResponse.Model.a",
                                        GraphModel.Kind.METHOD,
                                        "src/ModelsResponse.java",
                                        4,
                                        4,
                                        "Integer a()"),
                                new GraphModel.Symbol(
                                        "com.acme.llm.ModelsResponse.Model.b",
                                        GraphModel.Kind.METHOD,
                                        "src/ModelsResponse.java",
                                        5,
                                        5,
                                        "Integer b()"),
                                new GraphModel.Symbol(
                                        "com.acme.llm.ModelsResponse.Model.maxTokens",
                                        GraphModel.Kind.METHOD,
                                        "src/ModelsResponse.java",
                                        6,
                                        6,
                                        "public Integer maxTokens()"),
                                new GraphModel.Symbol(
                                        "com.other.Foo.maxTokens",
                                        GraphModel.Kind.METHOD,
                                        "src/Foo.java",
                                        3,
                                        3,
                                        "public Integer maxTokens()")),
                        List.of(),
                        Map.of(),
                        1L)));

        // the stored FQN is fully qualified while the request uses a short class prefix; the method simple
        // name is also globally ambiguous, so only the class-aware fallback can resolve it
        final var result = tool.execute(Map.of("symbol", "ModelsResponse.Model#maxTokens"));
        assertTrue(result.success(), result.output());
        assertTrue(
                result.output()
                        .contains(
                                "symbol METHOD com.acme.llm.ModelsResponse.Model.maxTokens (src/ModelsResponse.java:6-6)"),
                result.output());
    }

    @Test
    void ambiguousMethodUnderSameClassPrefixIsNotFound(@TempDir final Path root) throws IOException {
        final var a = root.resolve("src/A.java");
        final var b = root.resolve("src/B.java");
        Files.createDirectories(a.getParent());
        Files.writeString(
                a,
                "package com.acme.a;\npublic class ModelsResponse {\n    public record Model(Integer maxTokens) {\n        public Integer maxTokens() { return maxTokens; }\n    }\n}\n");
        Files.writeString(
                b,
                "package com.acme.b;\npublic class ModelsResponse {\n    public record Model(Integer maxTokens) {\n        public Integer maxTokens() { return maxTokens; }\n    }\n}\n");
        final var tool = new ReadSymbolTool(fake(
                root,
                new GraphStore(
                        List.of(
                                new GraphModel.Symbol(
                                        "com.acme.a.ModelsResponse.Model.maxTokens",
                                        GraphModel.Kind.METHOD,
                                        "src/A.java",
                                        4,
                                        4,
                                        "public Integer maxTokens()"),
                                new GraphModel.Symbol(
                                        "com.acme.b.ModelsResponse.Model.maxTokens",
                                        GraphModel.Kind.METHOD,
                                        "src/B.java",
                                        4,
                                        4,
                                        "public Integer maxTokens()")),
                        List.of(),
                        Map.of(),
                        1L)));

        // two methods match under the given class prefix: report not found instead of returning the first
        final var result = tool.execute(Map.of("symbol", "ModelsResponse.Model#maxTokens"));
        assertFalse(result.success());
        assertTrue(result.output().contains("Symbol not found: ModelsResponse.Model#maxTokens"), result.output());
        assertTrue(result.output().contains("code_find_code"), result.output());
    }

    @Test
    void errorsOnUnknownSymbolWithCodeFindHint() {
        final var tool = new ReadSymbolTool(fake(Path.of("."), GraphStore.empty()));
        final var result = tool.execute(Map.of("symbol", "NoSuch"));
        assertFalse(result.success());
        assertTrue(result.output().contains("Symbol not found: NoSuch"), result.output());
        assertTrue(result.output().contains("code_find_code"), result.output());
    }

    @Test
    void requiresSymbol() {
        final var tool = new ReadSymbolTool(fake(Path.of("."), GraphStore.empty()));
        assertFalse(tool.execute(Map.of()).success());
    }

    private static StructuralCodeGraph fake(final Path root, final GraphStore store) {
        return new StructuralCodeGraph() {
            @Override
            public GraphStore graph() {
                return store;
            }

            @Override
            public Path storeRoot() {
                return root.resolve("fake-code-graph");
            }

            @Override
            public Path projectDir() {
                return root;
            }
        };
    }
}
