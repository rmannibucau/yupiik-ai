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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TableRendererTest {
    private final TableRenderer renderer = new TableRenderer();

    private static String plain(final dev.tamboui.text.Line line) {
        final var sb = new StringBuilder();
        for (final var span : line.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }

    @Test
    void rendersBorderedGridFittingContent() {
        final var lines = renderer.render(
                List.of("a", "bb"),
                List.of(List.of("1", "22")),
                80,
                List.of(),
                List.of(),
                dev.tamboui.style.Style.EMPTY);
        final var texts = lines.stream().map(TableRendererTest::plain).toList();
        assertEquals("┌───┬────┐", texts.get(0));
        assertTrue(texts.get(1).startsWith("│ a │ bb "), texts.toString());
        assertEquals("├───┼────┤", texts.get(2));
        assertTrue(texts.get(3).startsWith("│ 1 │ 22 "), texts.toString());
        assertEquals("└───┴────┘", texts.get(texts.size() - 1));
    }

    @Test
    void neverExceedsRequestedWidthAndWrapsLongCells() {
        final var lines = renderer.render(
                List.of("name", "value"),
                List.of(List.of("configuration", "alpha beta gamma delta")),
                20,
                List.of(),
                List.of(),
                dev.tamboui.style.Style.EMPTY);
        for (final var line : lines) {
            assertTrue(plain(line).length() <= 20, plain(line));
        }
        // the long cell content is still visible, split over wrapped grid rows
        final var texts =
                String.join("\n", lines.stream().map(TableRendererTest::plain).toList());
        assertTrue(texts.contains("alpha"), texts);
        assertTrue(texts.contains("beta"), texts);
        assertTrue(texts.contains("gamma"), texts);
        assertTrue(texts.contains("delta"), texts);
    }

    @Test
    void headerOnlyTableStillBorders() {
        final var lines = renderer.render(
                List.of("h1", "h2"), List.of(), 40, List.of(), List.of(), dev.tamboui.style.Style.EMPTY);
        assertEquals(3, lines.size(), "top border, header, bottom border (no body separator)");
        assertTrue(lines.get(1).spans().stream().anyMatch(s -> s.content().contains("h1")));
    }

    @Test
    void emojiAndWideCharactersKeepGridAligned() {
        final var lines = renderer.render(
                List.of("Run", "Résultat", "Détails"),
                List.of(
                        List.of("T8", "✅ SYNC OK", "load 1230 s"),
                        List.of("T10", "⏳ en génération", "35 min"),
                        List.of("T11", "ok", "borne 30 Go")),
                120,
                List.of(),
                List.of(),
                dev.tamboui.style.Style.EMPTY);
        final var texts = lines.stream().map(TableRendererTest::plain).toList();
        final var widths = texts.stream().mapToInt(TableRenderer::displayWidth).toArray();
        for (int i = 1; i < widths.length; i++) {
            assertEquals(widths[0], widths[i], "line " + i + " has a different visible width: " + texts.get(i));
        }
        // the ✅ row emits fewer characters than the plain row: the 2-cell emoji
        // stole exactly one trailing space worth of padding, keeping the grid on-grid
        assertTrue(texts.get(3).length() < texts.get(5).length(), texts.toString());
    }

    @Test
    void wrapSplitsByDisplayCellsWithoutCuttingWideCharacters() {
        final var parts = TableRenderer.wrapCell("AB⏳EF", 4);
        assertEquals(List.of("AB⏳", "EF"), parts);
        assertTrue(parts.stream().allMatch(p -> TableRenderer.displayWidth(p) <= 4), parts.toString());
    }

    @Test
    void variationSelectorBumpsTextEmojiToTwoCells() {
        // bare ❤ is text-presentation (1 cell in jline), ❤️ with VS16 renders on 2 cells
        assertEquals(4, TableRenderer.displayWidth("A❤BC"));
        assertEquals(5, TableRenderer.displayWidth("A❤️BC"));
    }
}
