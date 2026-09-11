package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyPatchToolTest {

    @TempDir
    Path dir;

    private ApplyPatchTool tool() {
        return new ApplyPatchTool(new CodingAgentConfiguration(
                dir.toString(), null, dir.toString(), null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig()));
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