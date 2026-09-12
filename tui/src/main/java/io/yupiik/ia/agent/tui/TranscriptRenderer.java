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
 * <p>This matches the streaming constraint: we never wait for the stream to end
 * and never buffer more than one logical region; buffering happens at line and
 * code-block granularity only. The {@link MarkdownLines} renderer produces plain,
 * pre-wrapped lines so the cached prefix stays valid while new text is appended.
 */
final class TranscriptRenderer {
    private static final int MAX_CACHED_PREFIXES = 64;

    private final MarkdownLines markdownLines = new MarkdownLines();
    // key -> rendered lines of a stable (completed) source prefix
    private final Map<Key, List<Line>> prefixCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Key, List<Line>> eldest) {
            return size() > MAX_CACHED_PREFIXES;
        }
    };

    private String lastSource;
    private List<Line> lastPrefixLines;
    private Style lastBase;
    private MarkdownStyles lastStyles;

    List<Line> renderRegion(final String source, final int width, final Style base, final MarkdownStyles styles) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        final var safe = lastSafeNewline(source);
        final var prefix = source.substring(0, safe);
        final var suffix = source.substring(safe);
        List<Line> prefixLines;
        if (prefix.equals(lastSource) && lastPrefixLines != null && base.equals(lastBase) && styles == lastStyles) {
            prefixLines = lastPrefixLines;
        } else {
            prefixLines = prefixCache.computeIfAbsent(
                    new Key(prefix, width, base, styles), k -> renderToList(prefix, width, base, styles));
        }
        lastSource = prefix;
        lastPrefixLines = prefixLines;
        lastBase = base;
        lastStyles = styles;
        if (suffix.isEmpty()) {
            return new ArrayList<>(prefixLines);
        }
        final var combined = new ArrayList<Line>(prefixLines.size() + 16);
        combined.addAll(prefixLines);
        combined.addAll(renderToList(suffix, width, base, styles));
        return combined;
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
