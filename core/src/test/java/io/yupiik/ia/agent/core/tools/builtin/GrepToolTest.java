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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepToolTest {
    private final GrepTool tool = new GrepTool();

    @TempDir
    Path root;

    @Test
    void skipsIgnoredDirsAndHonorsGitignore() throws IOException {
        write(root.resolve(".gitignore"), "generated/\n");
        write(root.resolve("src/App.java"), "needle here\n");
        write(root.resolve("generated/Gen.java"), "needle here\n");
        write(root.resolve(".yca/generated/graph/wiring.json"), "needle here\n");
        write(root.resolve("target/Class.class"), "needle here\n");

        final var out = tool.execute(Map.of("pattern", "needle", "path", root.toString()))
                .output();

        assertTrue(out.contains("App.java"));
        assertFalse(out.contains("Gen.java"), "gitignored paths must be skipped:\n" + out);
        assertFalse(out.contains(".yca"), "yca store must be skipped:\n" + out);
        assertFalse(out.contains("target"), "target must be skipped:\n" + out);
    }

    @Test
    void capsResultsWithMarker() throws IOException {
        for (var i = 0; i < 10; i++) {
            write(root.resolve("F" + i + ".txt"), "hit\n");
        }
        final var out = tool.execute(Map.of("pattern", "hit", "path", root.toString(), "maxResults", "3"))
                .output();
        assertTrue(out.contains("3 match(es)"), out);
        assertTrue(out.contains("results capped at 3"), out);
    }

    @Test
    void keepsWalkingPastUnreadableDirectory() throws IOException {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "test relies on POSIX permission bits");
        // an unreadable directory/file (e.g. permission-denied subtrees) must not abort the whole walk
        final var denied = root.resolve("denied");
        Files.createDirectories(denied);
        write(denied.resolve("Hidden.txt"), "needle hidden\n");
        Files.setPosixFilePermissions(denied, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        final var locked = root.resolve("locked.txt");
        write(locked, "needle locked\n");
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        write(root.resolve("ok/App.java"), "needle here\n");

        final var out = tool.execute(Map.of("pattern", "needle", "path", root.toString()))
                .output();

        assertTrue(out.contains("App.java"), "the walk must continue past an unreadable dir:\n" + out);
        assertFalse(out.contains("Cannot search"), "unreadable subtrees must not fail the search:\n" + out);
    }

    @Test
    void relativeBasePathResolvesAgainstSessionProjectDir(@TempDir final Path projectDir) throws IOException {
        write(projectDir.resolve("src/App.java"), "needle here\n");
        write(projectDir.resolve("src/Other.java"), "nothing\n");
        // relative "src" must be rooted on the session projectDir, not the JVM working directory
        final var out = tool.execute(
                        Map.of("pattern", "needle", "path", "src"),
                        io.yupiik.ia.agent.core.tools.ToolContext.empty(projectDir))
                .output();
        assertTrue(out.contains("App.java"), out);
        assertFalse(out.contains("Other.java"), out);
    }

    @Test
    void unbalancedParenPatternIsRepairedAndReported() throws IOException {
        write(root.resolve("Foo.java"), "boolean handleKey( final int a\nboolean key( final int b\n");
        write(root.resolve("Bar.java"), "void dispatch();\n");

        final var out = tool.execute(Map.of("pattern", "handleKey(|key(", "path", root.toString()))
                .output();

        assertTrue(out.contains("handleKey("), "the repaired pattern must actually match: " + out);
        assertTrue(out.contains("adjusted"), "the adjusted pattern must be surfaced to the model: " + out);
    }

    @Test
    void invalidPatternReturnsHelpfulErrorWithLiteralGuidance() throws IOException {
        write(root.resolve("a.txt"), "x\n");

        final var out = tool.execute(Map.of("pattern", "(bad\\p{q}", "path", root.toString()));

        assertFalse(out.success(), "an unrepairable pattern must error: " + out.output());
        assertTrue(out.output().contains("literal: true"), "the error must steer toward literal matching: " + out);
    }

    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
