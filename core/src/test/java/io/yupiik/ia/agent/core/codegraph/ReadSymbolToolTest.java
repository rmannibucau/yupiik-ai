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
        // context=1 -> lines 2..6 rendered, read_file numbering format
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
