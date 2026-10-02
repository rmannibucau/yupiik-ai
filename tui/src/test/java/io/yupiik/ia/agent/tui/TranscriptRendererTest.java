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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptRendererTest {
    private final TranscriptRenderer renderer = new TranscriptRenderer();

    @Test
    void emptySourceRendersEmpty() {
        assertEquals(List.of(), renderer.renderRegion("", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
    }

    @Test
    void lastSafeNewlineBeforeFence() {
        // safe after "a\n" (outside fence), not after "```\n", not at "code\n"
        assertEquals(2, renderer.lastSafeNewline("a\n```\ncode\n"));
        // safe again after the closing fence (after the line ending at index 14)
        assertEquals(15, renderer.lastSafeNewline("a\n```\ncode\n```\n"));
    }

    @Test
    void lastSafeNewlineOnlyTrailingPartialLine() {
        assertEquals(0, renderer.lastSafeNewline("no newline yet"));
        assertEquals(5, renderer.lastSafeNewline("abcd\nstill growing"));
        assertEquals(0, renderer.lastSafeNewline("```\ninside fence, no close\n"));
    }

    @Test
    void renderMatchesMarkdownLines() {
        final var source = "hello\nworld";
        final var expected = new MarkdownLines().render(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(plain(expected), plain(renderer.renderRegion(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void growingStreamKeepsPrefixAndAddsSuffix() {
        final var a = plain(renderer.renderRegion("one\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two"), a);
        // extend the stream: prefix "one\n" is stable, unknown tail appended
        final var b = plain(renderer.renderRegion("one\ntwo\nthree", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two", "three"), b);
    }

    @Test
    void growingPartialLineReRendersTail() {
        final var a = plain(renderer.renderRegion("hello", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        final var b = plain(renderer.renderRegion("hello world", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("hello"), a);
        assertEquals(List.of("hello world"), b);
    }

    @Test
    void widthChangeFallsBackToFullRender() {
        final var a = plain(renderer.renderRegion("alpha beta gamma", 10, Style.EMPTY, MarkdownStyles.DEFAULTS));
        final var wide = plain(renderer.renderRegion("alpha beta gamma", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("alpha beta gamma"), wide);
        assertNotEquals(a, wide);
    }

    @Test
    void fencedCodeStaysIntactAcrossFrames() {
        final var md = "before\n```\ncode\n```\nafter";
        final var a = plain(renderer.renderRegion(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        // growing: keep fence intact, append
        final var b = plain(renderer.renderRegion(md + " line", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(a.size(), b.size());
        assertEquals("before", b.get(0));
        assertEquals("after line", b.get(b.size() - 1));
    }

    @Test
    void incrementalStreamingMatchesFullRenderAtEveryStep() {
        final var full =
                new MarkdownLines().render("alpha\nbeta\ngamma\ndelta", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var soFar = new StringBuilder();
        final var seen = new ArrayList<String>();
        for (final var line : List.of("alpha", "beta", "gamma", "delta")) {
            if (!soFar.isEmpty()) {
                soFar.append('\n');
            }
            soFar.append(line);
            seen.add(line);
            assertEquals(
                    seen,
                    plain(renderer.renderRegion(soFar.toString(), 80, Style.EMPTY, MarkdownStyles.DEFAULTS)),
                    () -> "frame after appending '" + line + "'");
        }
        // the incremental (prefix-cached) renderer must agree with a full render at the end
        assertEquals(plain(full), seen);
    }

    @Test
    void multiLineDeltaAppendsThroughTheDeltaPath() {
        final var a = plain(renderer.renderRegion("one\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two"), a);
        // several lines flushed between two frames: all land through the delta path
        final var b = plain(renderer.renderRegion("one\ntwo\nthree\nfour", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two", "three", "four"), b);
    }

    @Test
    void streamedBlankLinesMatchAFullRenderAtEveryStep() {
        // a blank line only separates two blocks *inside* one markdown document: the streaming
        // renderer cuts the region and parses both halves independently, so the separator row must
        // be put back or paragraphs are glued together while the stream grows
        final var md = "para one\n\npara two\n\npara three";
        assertEquals(List.of("para one", "", "para two", "", "para three"), fullRender(md));
        assertEveryFrameMatchesAFullRender(md);
        assertEquals(List.of("para one", "", "para two", "", "para three"), plainOf(md));
        // the flushed text is rendered again from scratch (thinking flushed, answer re-rendered):
        // the cached prefix lines must not carry a lost separator for the rest of the session
        assertEquals(List.of("para one", "", "para two", "", "para three"), plainOf(md));
    }

    @Test
    void blankLineAroundAFenceMatchesAFullRenderWhileStreaming() {
        final var md = "para\n\n```\ncode\n```\n\nafter";
        assertEquals(List.of("para", "", "code", "", "after"), fullRender(md));
        assertEveryFrameMatchesAFullRender(md);
    }

    /** Renders every growing prefix of {@code md}, as a token-by-token stream would. */
    private void assertEveryFrameMatchesAFullRender(final String md) {
        for (var cut = 1; cut <= md.length(); cut++) {
            final var frame = cut;
            final var partial = md.substring(0, cut);
            assertEquals(fullRender(partial), plainOf(partial), () -> "frame after " + frame + " characters");
        }
    }

    private List<String> plainOf(final String source) {
        return plain(renderer.renderRegion(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
    }

    private static List<String> fullRender(final String source) {
        return plain(new MarkdownLines().render(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
    }

    private static List<String> plain(final List<Line> lines) {
        return lines.stream().map(Line::rawContent).toList();
    }
}
