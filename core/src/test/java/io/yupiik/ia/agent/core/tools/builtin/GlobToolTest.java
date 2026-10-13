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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobToolTest {
    private final GlobTool tool = new GlobTool();

    @TempDir
    Path root;

    @Test
    void skipsIgnoredDirsAndHonorsGitignore() throws IOException {
        write(root.resolve(".gitignore"), "vendor/\n");
        write(root.resolve("src/Main.java"), "class Main {}\n");
        write(root.resolve("vendor/Lib.java"), "class Lib {}\n");
        write(root.resolve(".yca/generated/graph/index.bin"), "x");

        final var out = tool.execute(Map.of("pattern", "**/*.java", "path", root.toString()))
                .output();

        assertTrue(out.contains("Main.java"), out);
        assertFalse(out.contains("vendor"), "gitignored dirs must be skipped:\n" + out);
        assertFalse(out.contains(".yca"), "yca store must be skipped:\n" + out);
    }

    @Test
    void capsResultsWithMarker() throws IOException {
        for (var i = 0; i < 600; i++) {
            write(root.resolve("F" + i + ".txt"), "x");
        }
        final var out = tool.execute(Map.of("pattern", "**/*.txt", "path", root.toString()))
                .output();
        assertTrue(out.contains("500 match(es)"), out);
        assertTrue(out.contains("results capped at 500"), out);
    }

    @Test
    void relativeBasePathResolvesAgainstSessionProjectDir(@TempDir final Path projectDir) throws IOException {
        write(projectDir.resolve("src/Main.java"), "class Main {}\n");
        write(projectDir.resolve("other/Ignored.java"), "ignored\n");
        // relative "src" must be rooted on the session projectDir, not the JVM working directory
        final var out = tool.execute(
                        Map.of("pattern", "**/*.java", "path", "src"),
                        io.yupiik.ia.agent.core.tools.ToolContext.empty(projectDir))
                .output();
        assertTrue(out.contains("Main.java"), out);
        assertFalse(out.contains("Ignored.java"), out);
    }

    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
