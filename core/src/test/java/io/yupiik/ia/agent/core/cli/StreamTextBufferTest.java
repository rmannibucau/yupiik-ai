package io.yupiik.ia.agent.core.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamTextBufferTest {
    @Test
    void emitsOnlyCompletedLinesAndFlushesTheRemainder() {
        final var lines = new ArrayList<String>();
        final var buffer = new StreamTextBuffer(line -> {
            lines.add(line);
            return line;
        });
        assertEquals("", buffer.append("hello wor"));
        assertEquals(List.of(), lines);
        assertEquals("hello world\n", buffer.append("ld\nnext "));
        assertEquals(List.of("hello world"), lines);
        assertEquals("next \n", buffer.flush());
        assertEquals(List.of("hello world", "next "), lines);
        assertEquals("", buffer.flush());
    }

    @Test
    void multiLineChunkDrainsAllCompleteLines() {
        final var buffer = new StreamTextBuffer(line -> "[" + line + "]");
        assertEquals("[a]\n[b]\n", buffer.append("a\nb\nc"));
        assertEquals("[c]\n", buffer.flush());
    }

    @Test
    void resetDropsPendingLine() {
        final var buffer = new StreamTextBuffer(line -> line);
        buffer.append("partial");
        buffer.reset();
        assertEquals("", buffer.flush());
        assertEquals("done\n", buffer.append("done\n"));
    }

    @Test
    void nullAndEmptyChunksAreIgnored() {
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("", buffer.append(null));
        assertEquals("", buffer.append(""));
        assertEquals("x\n", buffer.append("x\n"));
    }

    @Test
    void emptyLinesAreEmittedAsNewlines() {
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("\n\n", buffer.append("\n\n"));
        assertEquals("", buffer.flush());
    }
}
