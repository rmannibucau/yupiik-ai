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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyPatchToolTest {

    @TempDir
    Path dir;

    private ApplyPatchTool tool() {
        return new ApplyPatchTool(io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.of(
                "yca.storageHome", dir.toString(),
                "yca.projectDir", dir.toString())));
    }

    @Test
    void appliesContextChanges() throws Exception {
        final var file = dir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "line one\nline two\nline three\n");
        final var patch = """
                --- a/src/Main.java
                +++ b/src/Main.java
                @@
                 line one
                -line two
                +line two changed
                 line three
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertTrue(result.success(), () -> result.output());
        assertEquals("line one\nline two changed\nline three\n", Files.readString(file));
    }

    @Test
    void createsNewFileFromDevNull() throws Exception {
        final var patch = """
                --- /dev/null
                +++ b/created.txt
                @@
                +hello
                +world
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertTrue(result.success(), () -> result.output());
        assertTrue(result.output().contains("Created"));
        assertEquals("hello\nworld\n", Files.readString(dir.resolve("created.txt")));
    }

    @Test
    void deletesFileToDevNull() throws Exception {
        final var file = dir.resolve("to-delete.txt");
        Files.writeString(file, "bye\n");
        final var patch = """
                --- a/to-delete.txt
                +++ /dev/null
                @@
                -bye
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertTrue(result.success(), () -> result.output());
        assertTrue(result.output().contains("Deleted"));
        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsContextMismatch() throws Exception {
        final var file = dir.resolve("foo.txt");
        Files.writeString(file, "different\n");
        final var patch = """
                --- a/foo.txt
                +++ b/foo.txt
                @@
                 missing
                -no
                +yes
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertFalse(result.success());
        assertEquals("different\n", Files.readString(file));
    }

    @Test
    void rejectsEscapingPatchPath() throws Exception {
        final var patch = """
                --- a/../../etc/passwd
                +++ b/../../etc/passwd
                @@
                -x
                +y
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertFalse(result.success());
    }

    @Test
    void rejectsDriftedHunkInsteadOfSplicingAtTheFirstMatchingOffset() throws Exception {
        // the @@ header anchors the hunk at line 1 while its context only exists
        // at line 10: the old implementation scanned from offset 0, matched at
        // line 10 and dropped everything before it (head truncation)
        final var sb = new StringBuilder();
        for (var i = 1; i <= 12; i++) {
            sb.append("line ").append(i).append('\n');
        }
        final var file = dir.resolve("drifted.txt");
        Files.writeString(file, sb.toString());
        final var patch = """
                --- a/drifted.txt
                +++ b/drifted.txt
                @@ -1,2 +1,2 @@
                 line 10
                -line 11
                +changed
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertFalse(result.success(), () -> result.output());
        assertEquals(sb.toString(), Files.readString(file));
    }

    @Test
    void appliesAnchoredHunkAtDeclaredPositionWhenBodyRepeatsEarlier() throws Exception {
        // the hunk body also occurs at line 3, but the header pins it to line 13
        final var sb = new StringBuilder();
        for (var i = 1; i <= 15; i++) {
            sb.append("line ").append(i).append('\n');
        }
        final var file = dir.resolve("anchor.txt");
        Files.writeString(file, sb.toString());
        final var patch = """
                --- a/anchor.txt
                +++ b/anchor.txt
                @@ -13,2 +13,2 @@
                 line 13
                -line 14
                +changed
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertTrue(result.success(), () -> result.output());
        final var updated = Files.readString(file);
        assertTrue(updated.contains("line 12\nline 13\nchanged\nline 15\n"), updated);
        assertTrue(updated.contains("\nline 3\nline 4\n"), updated);
    }

    @Test
    void rejectsAmbiguousBareHunk() throws Exception {
        // a bare @@ hunk matching two locations cannot be applied safely
        final var file = dir.resolve("ambig.txt");
        Files.writeString(file, "a\nb\nc\na\nb\nc\n");
        final var patch = """
                --- a/ambig.txt
                +++ b/ambig.txt
                @@
                 a
                -b
                +B
                 c
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertFalse(result.success(), () -> result.output());
        assertEquals("a\nb\nc\na\nb\nc\n", Files.readString(file));
    }

    @Test
    void rejectsHunkBodyThatDisagreesWithHeaderCounts() throws Exception {
        // the @@ header declares 3 added lines while the body only adds one
        final var file = dir.resolve("counts.txt");
        Files.writeString(file, "a\nb\nc\n");
        final var patch = """
                --- a/counts.txt
                +++ b/counts.txt
                @@ -1,1 +1,3 @@
                -a
                +a
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertFalse(result.success(), () -> result.output());
        assertEquals("a\nb\nc\n", Files.readString(file));
    }

    @Test
    void appliesMultipleHunksWithDrift() throws Exception {
        final var file = dir.resolve("multi.txt");
        Files.writeString(file, "a\nb\nc\nd\ne\n");
        final var patch = """
                --- a/multi.txt
                +++ b/multi.txt
                @@ -2,1 +2,1 @@
                -b
                +B
                @@ -4,1 +4,1 @@
                -d
                +D
                """;
        final var result = tool().execute(Map.of("patch", patch));
        assertTrue(result.success(), () -> result.output());
        assertEquals("a\nB\nc\nD\ne\n", Files.readString(file));
    }
}
