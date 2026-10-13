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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFileToolTest {

    @Test
    void readsWholeFileWithNumberedLines(@TempDir final Path root) throws IOException {
        final var file = root.resolve("a.txt");
        Files.writeString(file, "one\ntwo\nthree\n");
        final var tool = new ReadFileTool();

        final var result = tool.execute(Map.of("path", file.toString()));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("     1\tone"), result.output());
        assertTrue(result.output().contains("     3\tthree"), result.output());
        assertFalse(result.output().contains("more lines below"), result.output());
    }

    @Test
    void honorsOffsetAndLimitWithContinueMarker(@TempDir final Path root) throws IOException {
        final var file = root.resolve("a.txt");
        Files.writeString(file, "l1\nl2\nl3\nl4\nl5\n");
        final var tool = new ReadFileTool();

        final var slice = tool.execute(Map.of("path", file.toString(), "offset", "2", "limit", "2"));
        assertTrue(slice.success(), slice.output());
        assertTrue(slice.output().contains("     2\tl2"), slice.output());
        assertTrue(slice.output().contains("     3\tl3"), slice.output());
        assertFalse(slice.output().contains("l1"), slice.output());
        assertTrue(slice.output().contains("... more lines below, use offset=4 to continue"), slice.output());
    }

    @Test
    void tailReadHasNoContinueMarker(@TempDir final Path root) throws IOException {
        final var file = root.resolve("a.txt");
        Files.writeString(file, "l1\nl2\n");
        final var tool = new ReadFileTool();

        final var tail = tool.execute(Map.of("path", file.toString(), "offset", "2"));
        assertTrue(tail.success());
        assertTrue(tail.output().contains("     2\tl2"), tail.output());
        assertFalse(tail.output().contains("more lines below"), tail.output());
    }

    @Test
    void missingFileSuggestsClosestCandidate(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("ProviderConfiguration.java"), "x");
        Files.writeString(root.resolve("ProviderListConfiguration.java"), "x");
        final var tool = new ReadFileTool();

        final var result = tool.execute(
                Map.of("path", root.resolve("ProviderConfiguraion.java").toString()));
        assertFalse(result.success());
        assertTrue(result.output().contains("Did you mean:"), result.output());
        assertTrue(result.output().contains("ProviderConfiguration.java"), result.output());
    }

    @Test
    void missingFileWithoutCandidateStaysPlain(@TempDir final Path root) {
        final var tool = new ReadFileTool();
        final var result = tool.execute(
                Map.of("path", root.resolve("zzz-does-not-exist.java").toString()));
        assertFalse(result.success());
        assertTrue(result.output().startsWith("File not found:"), result.output());
        assertFalse(result.output().contains("Did you mean"), result.output());
    }

    @Test
    void levenshteinBasics() {
        assertEquals(0, ReadFileTool.levenshtein("abc", "abc"));
        assertEquals(1, ReadFileTool.levenshtein("abc", "abd"));
        assertEquals(1, ReadFileTool.levenshtein("ProviderConfiguraion", "ProviderConfiguration"));
    }

    @Test
    void relativePathResolvesAgainstSessionProjectDir(@TempDir final Path projectDir, @TempDir final Path cwd)
            throws IOException {
        // a relative path must land under the session root, NOT the JVM working directory
        Files.writeString(projectDir.resolve("rel.txt"), "session content\n");
        final var tool = new ReadFileTool();
        final var result =
                tool.execute(Map.of("path", "rel.txt"), io.yupiik.ia.agent.core.tools.ToolContext.empty(projectDir));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("session content"), result.output());
    }
}
