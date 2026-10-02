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

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders transcript regions to {@link Line} lists with an eye on streaming:
 * the transcript is append-only while a turn streams, so rendering the whole
 * thing again on every frame would be wasteful. This renderer holds back the
 * trailing (still growing) source at a safe boundary - the last line end that is
 * not inside a fenced code block - and reuses the rendered lines of the stable
 * prefix while only re-rendering the short suffix each frame.
 *
 * <p>On top of that suffix trick, the stable prefix itself is not re-parsed while
 * it grows: the previous frame's prefix is remembered, and when the new prefix is
 * just that prefix plus appended lines (streaming is append-only), only the delta
 * between the two is sent through the markdown engine. The rendered lines of the
 * already-stable part are kept as-is, so a long streaming turn costs O(delta) per
 * frame instead of re-parsing the whole accumulated text every frame (which
 * previously put the main render thread at ~40% CPU inside commonmark).
 *
 * <p>This matches the streaming constraint: we never wait for the stream to end
 * and never buffer more than one logical region; buffering happens at line and
 * code-block granularity only. The {@link MarkdownLines} renderer produces plain,
 * pre-wrapped lines so the cached prefix stays valid while new text is appended.
 */
final class TranscriptRenderer {
    private static final int MAX_CACHED_PREFIXES = 64;

    private final MarkdownLines markdownLines = new MarkdownLines();
    // key -> rendered lines of a stable (completed) source prefix; thread-safe and
    // bounded so a long session (a new key per region switch) never grows the
    // footprint. The append-only delta path deliberately skips this cache: growing
    // prefixes are moving targets that would churn (and blow up) the entries.
    private final Map<Key, List<Line>> prefixCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Key, List<Line>> eldest) {
            return size() > MAX_CACHED_PREFIXES;
        }
    });

    // single slot remembering the last stable prefix rendered: an exact hit makes a
    // busy frame with unchanged content O(1), and the append-only path re-parses only
    // the delta added since the previous frame instead of the whole accumulated turn.
    // Only ever touched by the render thread, but kept volatile so concurrent readers
    // never observe a torn half-updated state.
    private volatile String lastPrefix;
    private volatile List<Line> lastPrefixLines;
    private volatile int lastWidth = -1;
    private volatile Style lastBase;
    private volatile MarkdownStyles lastStyles;

    List<Line> renderRegion(final String source, final int width, final Style base, final MarkdownStyles styles) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        final var safe = lastSafeNewline(source);
        final var prefix = source.substring(0, safe);
        final var suffix = source.substring(safe);
        final var prefixLines = prefixLines(prefix, width, base, styles);
        if (suffix.isEmpty()) {
            // defensive copy: cached lines must never be mutated by the caller
            return new ArrayList<>(prefixLines);
        }
        final var combined = new ArrayList<Line>(prefixLines.size() + 16);
        combined.addAll(prefixLines);
        appendBlocks(
                combined,
                renderToList(suffix, width, base, styles),
                boundaryBlanks(prefix, suffix) > 0,
                !suffix.isBlank());
        return combined;
    }

    /**
     * Appends {@code rendered} to {@code out}, optionally inserting the single blank row that
     * separates two block groups.
     * <p>
     * A blank source line only separates blocks <em>inside</em> one markdown document: both
     * chunks here are parsed independently, so commonmark drops the trailing blanks of the first
     * and the leading blanks of the second and the separator row would be lost - the paragraph
     * above glues to the paragraph below, and a stream that already rendered such a boundary
     * keeps it glued for the rest of the session (the cached prefix lines are reused verbatim).
     * The row is inserted only once the new chunk actually produced lines, so a stream paused on
     * a paragraph-ending blank line still renders exactly like a cold render of the same text.
     *
     * @param blankRowBetween true to keep one blank row between the two groups: either a blank
     *                        source line at the boundary, or a new block group (a fenced block)
     */
    static void appendBlocks(final List<Line> out, final List<Line> rendered, final boolean blankRowBetween) {
        appendBlocks(out, rendered, blankRowBetween, false);
    }

    /**
     * Same as {@link #appendBlocks(List, List, boolean)} but aware that the second document may
     * have already started a block that did not emit a row yet (an open fenced block): the cold
     * renderer still counts it as a sibling block and keeps the separator row, so the live stream
     * must not wait for the first content row in that case or the frame right after the opening
     * fence line would read denser than a full render of the same text.
     */
    static void appendBlocks(
            final List<Line> out, final List<Line> rendered, final boolean blankRowBetween, final boolean openBlock) {
        final var blank = (blankRowBetween && !rendered.isEmpty()) || (openBlock && rendered.isEmpty());
        if (blank && !out.isEmpty() && !isBlankRow(out.get(out.size() - 1))) {
            out.add(Line.empty());
        }
        if (!rendered.isEmpty()) {
            out.addAll(rendered);
        }
    }

    /** True when {@code line} carries no visible text (a spacer row). */
    static boolean isBlankRow(final Line line) {
        for (final var span : line.spans()) {
            if (span.content() != null && !span.content().isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Number of blank source lines at the boundary between two chunks of the same region, i.e.
     * the blank lines {@code left} ends with plus the ones {@code right} starts with (the split
     * point is a line boundary, so the two sets never overlap). Positive when the two chunks must
     * be kept apart by a blank row.
     */
    static int boundaryBlanks(final String left, final String right) {
        return trailingBlankLines(left) + leadingBlankLines(right);
    }

    /**
     * Blank lines at the end of {@code text}: a trailing line terminator only terminates the last
     * content line, so {@code "a\n"} has none while {@code "a\n\n"} has one.
     */
    private static int trailingBlankLines(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        final var lines = text.split("\n", -1);
        // a trailing "" is the artifact of the last terminator, not a blank line
        final var end = text.endsWith("\n") ? lines.length - 1 : lines.length;
        var count = 0;
        for (var i = end - 1; i >= 0 && lines[i].isBlank(); i--) {
            count++;
        }
        return count;
    }

    /** Blank lines at the start of {@code text} ({@code "\n\na"} has two). */
    private static int leadingBlankLines(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        final var lines = text.split("\n", -1);
        var count = 0;
        for (var i = 0; i < lines.length && lines[i].isBlank(); i++) {
            count++;
        }
        return count;
    }

    private List<Line> prefixLines(
            final String prefix, final int width, final Style base, final MarkdownStyles styles) {
        if (prefix.isEmpty()) {
            return List.of();
        }
        final var sameParams = width == lastWidth && lastBase != null && lastBase.equals(base) && styles == lastStyles;
        if (lastPrefixLines != null && sameParams) {
            if (prefix.equals(lastPrefix)) {
                // identical stable prefix as the previous frame: reuse verbatim
                return lastPrefixLines;
            }
            if (lastPrefix != null && prefix.startsWith(lastPrefix)) {
                // append-only streaming: only the delta (the lines added since the
                // previous frame) is markdown-parsed; the rest was already rendered.
                final var delta = prefix.substring(lastPrefix.length());
                final var result = new ArrayList<Line>(lastPrefixLines.size() + 8);
                result.addAll(lastPrefixLines);
                appendBlocks(result, renderToList(delta, width, base, styles), boundaryBlanks(lastPrefix, delta) > 0);
                remember(prefix, result, width, base, styles);
                return result;
            }
        }
        // region switch or width/base/styles change: full render through the bounded cache
        final var key = new Key(prefix, width, base, styles);
        var lines = prefixCache.get(key);
        if (lines == null) {
            lines = renderToList(prefix, width, base, styles);
            prefixCache.put(key, lines);
        }
        remember(prefix, lines, width, base, styles);
        return lines;
    }

    private void remember(
            final String prefix,
            final List<Line> lines,
            final int width,
            final Style base,
            final MarkdownStyles styles) {
        lastPrefix = prefix;
        lastPrefixLines = lines;
        lastWidth = width;
        lastBase = base;
        lastStyles = styles;
    }

    private List<Line> renderToList(
            final String source, final int width, final Style base, final MarkdownStyles styles) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return markdownLines.render(source, width, base, styles);
    }

    /**
     * Index just past the last {@code '\n'} that is a safe place to cut: not inside a
     * fenced code block. Cutting between code-fence-aware lines keeps each suffix
     * independently re-parseable, so streaming appends only re-render the tail.
     *
     * @return if {@code source} contains any safe break the index right after its
     *         newline, otherwise {@code 0}
     */
    int lastSafeNewline(final String source) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        var inFence = false;
        var safe = 0;
        var start = 0;
        while (start <= source.length()) {
            final var nl = source.indexOf('\n', start);
            final var line = nl < 0 ? source.substring(start) : source.substring(start, nl);
            final var trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
            }
            if (!inFence && nl >= 0) {
                safe = nl + 1;
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return safe;
    }

    private record Key(String source, int width, Style base, MarkdownStyles styles) {}
}
