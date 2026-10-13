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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.shell.ThinkTagSplitter.Parts;
import org.junit.jupiter.api.Test;

class ThinkTagSplitterTest {
    private final ThinkTagSplitter splitter = new ThinkTagSplitter();

    @Test
    void streamsInterleavedChannelsAcrossSplitDelimiters() {
        final var visible = new StringBuilder();
        final var thinking = new StringBuilder();
        final var fenced = new StringBuilder();
        // one parser across the whole stream: state must survive chunk boundaries
        // (the ``` fences are themselves split across chunks)
        final var parser = splitter.create();
        for (final var chunk : new String[] {
            "Hello\n<thi", "nk>private reasoning</th", "ink>\nHere is `", "``json\n{\"x\": 1}\n``", "`"
        }) {
            for (final var segment : parser.feed(chunk)) {
                append(segment, visible, thinking, fenced);
            }
        }
        for (final var segment : parser.flush()) {
            append(segment, visible, thinking, fenced);
        }
        assertEquals("Hello\n\nHere is ", visible.toString());
        assertEquals("private reasoning", thinking.toString());
        // the FENCED channel round-trips the ``` delimiters so reassembling the stream
        // keeps faithful fenced-code markdown (the delimiters were historically dropped,
        // flattening code blocks to bare prose with collapsed indentation)
        assertEquals("```json\n{\"x\": 1}\n```", fenced.toString());
    }

    @Test
    void delimitersSplitAcrossChunksNeverLeak() {
        final var parser = splitter.create();
        final var visible = new StringBuilder();
        final var thinking = new StringBuilder();
        for (final var chunk : new String[] {"<thi", "nk>secret", "</th", "ink>done"}) {
            for (final var segment : parser.feed(chunk)) {
                append(segment, visible, thinking, new StringBuilder());
            }
        }
        for (final var segment : parser.flush()) {
            append(segment, visible, thinking, new StringBuilder());
        }
        assertEquals("secret", thinking.toString());
        assertEquals("done", visible.toString());
        assertTrue(!visible.toString().contains("<"), visible.toString());
    }

    @Test
    void unclosedThinkTailIsReasoning() {
        final var parts = splitter.split("before <think >\npartial reasoning");
        assertEquals("before ", parts.visible());
        assertEquals("partial reasoning", parts.thinking().strip());
    }

    @Test
    void fenceInsideThinkingStaysThinking() {
        final var parts = splitter.split("<think>\n```java\nx=1\n```\n</think>\nvis");
        assertTrue(parts.thinking().contains("```java"), parts.thinking());
        // the raw newline after the close tag belongs to the visible stream
        assertEquals("\nvis", parts.visible());
    }

    @Test
    void thinkInsideFenceStaysVisible() {
        final var parts = splitter.split("```json\n<think>not</think>\n```\nok");
        assertEquals("", parts.thinking());
        assertTrue(parts.visible().contains("<think>not</think>"), parts.visible());
        assertTrue(parts.visible().endsWith("ok"), parts.visible());
    }

    @Test
    void backtickFenceIsNotClosedByTildes() {
        final var parts = splitter.split("```a\ncode\n~~~\nmore\n```\ntail");
        assertEquals("", parts.thinking());
        assertTrue(parts.visible().contains("~~~"), parts.visible());
        assertTrue(parts.visible().endsWith("tail"), parts.visible());
    }

    @Test
    void resetDropsPartialDelimiter() {
        final var parser = splitter.create();
        parser.feed("<thi");
        parser.reset();
        final var visible = new StringBuilder();
        final var thinking = new StringBuilder();
        for (final var segment : parser.feed("plain</think>")) {
            append(segment, visible, thinking, new StringBuilder());
        }
        for (final var segment : parser.flush()) {
            append(segment, visible, thinking, new StringBuilder());
        }
        // an orphan close tag after reset() is plain text: the splitter never
        // fabricates a THINKING state to swallow it
        assertEquals("plain</think>", visible.toString());
        assertEquals("", thinking.toString());
    }

    @Test
    void splitPassesPlainTextThrough() {
        assertEquals(new Parts("just text", ""), splitter.split("just text"));
    }

    @Test
    void splitPreservesFencedCodeDelimitersInVisibleContent() {
        // an assistant answer with a normal (non-thinking) fenced java block must keep its
        // ``` delimiters so downstream renderers can still box + syntax-highlight it
        final var code = "public int x() {\n    return 1;\n}\n";
        final var parts = splitter.split("here is the method:\n```java\n" + code + "```");
        assertEquals("", parts.thinking());
        assertTrue(parts.visible().contains("```java"), parts.visible());
        assertTrue(parts.visible().contains("```"), parts.visible());
    }

    @Test
    void inlineCodeTailStreamsImmediatelyAndFlushAddsNothing() {
        final var parser = splitter.create();
        final var visible = new StringBuilder();
        // a single inline-code backtick followed by more text cannot open a fence: it must
        // stream immediately instead of sitting in the carry window until flush() (regression:
        // the old code truncated the live tail at the closing backtick, losing characters
        // mid-line in the TUI)
        for (final var segment : parser.feed("je commence par le code de `AgentLoop.java` :")) {
            append(segment, visible, new StringBuilder(), new StringBuilder());
        }
        assertEquals(
                "je commence par le code de `AgentLoop.java` :",
                visible.toString(),
                "the inline-code tail must stream immediately, complete, mid-stream");
        final var afterFlush = new StringBuilder();
        for (final var segment : parser.flush()) {
            append(segment, afterFlush, new StringBuilder(), new StringBuilder());
        }
        assertEquals("", afterFlush.toString(), "nothing is held back after the tail streamed");
    }

    private static void append(
            final ThinkTagSplitter.Segment segment,
            final StringBuilder visible,
            final StringBuilder thinking,
            final StringBuilder fenced) {
        switch (segment.channel()) {
            case VISIBLE -> visible.append(segment.text());
            case THINKING -> thinking.append(segment.text());
            case FENCED -> fenced.append(segment.text());
        }
    }
}
