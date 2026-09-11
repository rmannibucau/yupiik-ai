package io.yupiik.ia.agent.core.optimize;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.OptimizeConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericOutputProcessorTest {

    @Test
    void compactsBlankRunsAndTrailingWhitespace() {
        final var out = processor(400).process("ls -la", Stream.of(
                "total 0",
                "",
                "   ",
                "drwxr-xr-x  user  staff   ",
                "drwxr-xr-x  user  staff  ",
                "",
                "",
                "done  ",
                "  ")).toList();

        assertEquals(List.of("total 0", "", "drwxr-xr-x  user  staff", "drwxr-xr-x  user  staff", "", "done"), out);
    }

    @Test
    void dropsLeadingAndTrailingBlankLines() {
        final var out = processor(400).process("echo hello", Stream.of("", "", "hello", "", "")).toList();
        assertEquals(List.of("hello"), out);
    }

    @Test
    void stripsAnsi() {
        final var out = processor(400).process("ls", Stream.of("\u001B[31mred\u001B[0m text")).toList();
        assertEquals(List.of("red text"), out);
    }

    @Test
    void truncatesLargeOutputs() {
        final var lines = Stream.iterate(0, i -> i + 1).limit(50).map(i -> "line-" + i);
        final var out = processor(10).process("cat big.txt", lines).toList();

        assertEquals(11, out.size());
        assertEquals("line-0", out.get(0));
        assertTrue(out.get(6).startsWith("[... "));
        assertTrue(out.get(6).endsWith(" lines truncated ...]"));
        assertEquals("line-49", out.get(10));
    }

    private GenericOutputProcessor processor(final int maxLines) {
        final var optimize = new OptimizeConfiguration(true, maxLines, 20, List.of());
        final var configuration = new CodingAgentConfiguration("x", ".", null,
                null, null, null, null, null, null, optimize, null, null, null, null, null, null, null,
                io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig());
        return new GenericOutputProcessor(configuration);
    }
}
