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
import java.util.List;

/**
 * Renders the context-aware hints bar above the prompt: the keys that matter
 * in the current state (idle, busy, overlay open), degrading to a shorter
 * subset on narrow terminals. Segments come from {@link KeymapCatalog} so the
 * hints can never drift from {@code /shortcuts}.
 */
public final class HintsBar {
    private HintsBar() {}

    /** Builds the segments for the given state (exposed for tests). */
    public static List<KeymapCatalog.Binding> segments(final boolean busy, final boolean overlayOpen, final int width) {
        if (overlayOpen) {
            return KeymapCatalog.overlayHints();
        }
        if (width < 96) {
            return KeymapCatalog.idleHints()
                    .subList(0, Math.min(4, KeymapCatalog.idleHints().size()));
        }
        return busy ? KeymapCatalog.busyHints() : KeymapCatalog.idleHints();
    }

    /** Renders the hints bar line for the given state. */
    public static Line render(final boolean busy, final boolean overlayOpen, final Palette palette, final int width) {
        var segments = segments(busy, overlayOpen, width);
        var line = new StringBuilder();
        var fits = new java.util.ArrayList<KeymapCatalog.Binding>();
        for (final var segment : segments) {
            final var piece = line.isEmpty()
                    ? segment.key() + " " + segment.label()
                    : "  " + segment.key() + " " + segment.label();
            if (line.length() + piece.length() > width && !fits.isEmpty()) {
                break;
            }
            line.append(piece);
            fits.add(segment);
        }
        if (fits.isEmpty() && !segments.isEmpty()) {
            fits.add(segments.getFirst());
        }
        final var spans = new java.util.ArrayList<Span>();
        var first = true;
        for (final var segment : fits) {
            if (!first) {
                spans.add(Span.styled("  ", palette.muted()));
            }
            spans.add(Span.styled(segment.key(), palette.bold(palette.accent())));
            spans.add(Span.styled(" " + segment.label(), palette.muted()));
            first = false;
        }
        return spans.isEmpty() ? Line.empty() : Line.from(spans);
    }
}
