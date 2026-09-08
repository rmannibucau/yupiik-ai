package io.yupiik.ia.agent.tui;

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    private static List<String> plain(final List<Line> lines) {
        return lines.stream().map(Line::rawContent).toList();
    }
}