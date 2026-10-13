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

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unified diff rendering for tool cards, the output overlay, the approval modal
 * and the structured tool previews: file headers emphasized, hunk headers
 * annotated with +N/-M counters, add/remove lines colored, and paired -/+ lines
 * get word-level emphasis of the actually changed segment.
 *
 * <p>Stateless (the palette is a render argument) so a single instance can be
 * shared, including across theme switches where the palette changes per frame.
 * Pure and terminal-free so it is unit testable.
 */
public class DiffRenderer {

    /** True when the text plausibly is a unified diff (not a markdown list). */
    public boolean looksLikeDiff(final String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        var hunk = false;
        var fileHeaders = false;
        var changed = 0;
        for (final var line : text.split("\n")) {
            final var s = line.stripLeading();
            if (s.startsWith("@@") && s.contains("@@")) {
                hunk = true;
            } else if (s.startsWith("--- ") || s.startsWith("+++ ")) {
                fileHeaders = true;
            } else if (s.startsWith("+") || s.startsWith("-")) {
                changed++;
            }
        }
        return (hunk && changed >= 1) || (fileHeaders && changed >= 2);
    }

    /**
     * Renders a unified diff into pre-wrapped styled lines.
     *
     * @param diff    the raw diff text
     * @param width   the available width per line
     * @param palette the resolved palette
     */
    public List<Line> render(final String diff, final int width, final Palette palette) {
        final var out = new ArrayList<Line>();
        if (diff == null || diff.isBlank()) {
            return out;
        }
        final var split = diff.replace("\r\n", "\n").split("\n", -1);
        // a trailing newline produces an empty tail segment: drop it
        final var lines =
                split.length > 1 && split[split.length - 1].isEmpty() ? Arrays.copyOf(split, split.length - 1) : split;
        var i = 0;
        while (i < lines.length) {
            final var raw = lines[i];
            if (raw.startsWith("diff --git")
                    || raw.startsWith("index ")
                    || raw.startsWith("--- ")
                    || raw.startsWith("+++ ")) {
                out.add(Line.from(Span.styled(clip(raw, width), palette.bold(palette.primary()))));
                i++;
            } else if (raw.startsWith("@@")) {
                out.add(Line.from(Span.styled(clip(hunkLabel(lines, i), width), palette.bold(palette.info()))));
                i++;
            } else if (raw.startsWith("+") && !raw.startsWith("+++")) {
                out.add(Line.from(spansFor('+', raw.substring(1), -1, -1, palette.success(), palette, width)));
                i++;
            } else if (raw.startsWith("-") && !raw.startsWith("---")) {
                final var next = i + 1 < lines.length ? lines[i + 1] : "";
                if (next.startsWith("+") && !next.startsWith("+++")) {
                    final var segment = changedSegment(raw.substring(1), next.substring(1));
                    out.add(Line.from(
                            spansFor('-', raw.substring(1), segment[0], segment[1], palette.error(), palette, width)));
                    out.add(Line.from(spansFor(
                            '+', next.substring(1), segment[0], segment[1], palette.success(), palette, width)));
                    i += 2;
                } else {
                    out.add(Line.from(spansFor('-', raw.substring(1), -1, -1, palette.error(), palette, width)));
                    i++;
                }
            } else {
                out.add(Line.from(Span.styled(clip(raw, width), palette.style(palette.text()))));
                i++;
            }
        }
        return out;
    }

    /** Builds the {@code @@ ... @@ (+2 -1)} label for the hunk starting at {@code index}. */
    private String hunkLabel(final String[] lines, final int index) {
        var add = 0;
        var del = 0;
        for (var j = index + 1; j < lines.length; j++) {
            final var line = lines[j];
            if (line.startsWith("@@") || line.startsWith("diff --git")) {
                break;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                add++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                del++;
            }
        }
        final var counters = " (+" + add + " -" + del + ")";
        if (lines[index].length() + counters.length() <= 120) {
            return lines[index] + counters;
        }
        return lines[index];
    }

    /**
     * Computes the changed segment of a removed line compared to its paired
     * added line: common prefix/suffix trimming.
     *
     * @return {@code {start, end}} delimiting the changed range of {@code a}
     */
    private int[] changedSegment(final String a, final String b) {
        var prefix = 0;
        final var max = Math.min(a.length(), b.length());
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        var endA = a.length();
        var endB = b.length();
        while (endA > prefix && endB > prefix && a.charAt(endA - 1) == b.charAt(endB - 1)) {
            endA--;
            endB--;
        }
        return new int[] {prefix, endA};
    }

    private List<Span> spansFor(
            final char tag,
            final String body,
            final int changedStart,
            final int changedEnd,
            final String hex,
            final Palette palette,
            final int width) {
        final var color = palette.style(hex);
        final var emphasized = color.bold().underlined();
        final var prefix = " " + tag + " ";
        final var available = Math.max(1, width - prefix.length());
        final var spans = new ArrayList<Span>(4);
        spans.add(Span.styled(prefix, palette.bold(hex)));
        if (changedStart < 0 || changedStart >= changedEnd || changedEnd > body.length()) {
            spans.add(Span.styled(clip(body, available), color));
            return spans;
        }
        final var before = body.substring(0, changedStart);
        final var middle = body.substring(changedStart, Math.min(changedEnd, body.length()));
        final var after = body.substring(Math.min(changedEnd, body.length()));
        var budget = available;
        appendClipped(spans, before, color, budget);
        budget -= before.length();
        if (budget <= 0) {
            return spans;
        }
        appendClipped(spans, middle, emphasized, budget);
        budget -= middle.length();
        if (budget <= 0) {
            return spans;
        }
        appendClipped(spans, after, color, budget);
        return spans;
    }

    private void appendClipped(
            final List<Span> spans, final String text, final dev.tamboui.style.Style style, final int budget) {
        if (text.isEmpty() || budget <= 0) {
            return;
        }
        spans.add(Span.styled(text.length() > budget ? text.substring(0, budget) : text, style));
    }

    private String clip(final String text, final int width) {
        return text.length() > width ? text.substring(0, Math.max(1, width - 1)) + "…" : text;
    }
}
