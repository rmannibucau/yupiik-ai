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
}
