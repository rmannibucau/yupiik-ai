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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    @Test
    void echoedSeamSplitAcrossChunksIsStrippedFromDrainedLine() {
        // a seam tag can split across two incoming chunks ("@5" + "35@"); the scrub runs on
        // the drained complete line after fragments are joined, so it must still be removed
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("", buffer.append("answer @5"));
        assertEquals("answer\n", buffer.append("35@\ndone"));
        assertEquals("done\n", buffer.flush());
    }

    @Test
    void echoedWholeLineAnalogInSeamIsStripped() {
        // whole-line and inline echoes, plus the block and priority-suffix variants, are scrubbed
        final var buffer = new StreamTextBuffer(line -> line);
        // the whole-line seam strips to an empty line, which still drains as a newline
        assertEquals("\nreal prose\n", buffer.append("@b21@ [high]\nreal prose\n"));
        assertEquals("x y\n", buffer.append("x @blocked@ y\n"));
        assertEquals("kept @user\n", buffer.append("kept @user\n"));
    }
}
