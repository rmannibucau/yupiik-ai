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

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Screen-region text selection driven by left-button mouse events. The
 * selection works on the last rendered frame snapshot (a grid of cell
 * symbols) so it can copy any visible text whatever widget rendered it
 * (markdown, paragraphs, borders, sidebar, status line...).
 * <p>
 * A simple press starts (or restarts) a selection, dragging extends it and
 * releasing copies it (handled by the caller). A double-click selects the
 * word under the cursor and a triple-click the whole row; dragging then
 * extends by words or whole rows. The selection stays highlighted until the
 * next press or {@link #clear()}.
 * <p>
 * A selection is confined to the {@link #zone(List) pane} its press landed in
 * (a modal, the prompt input, the conversation body...), resolved once at press
 * time so a layout change mid-drag cannot make the highlight and the copied
 * text disagree.
 */
final class SelectionState {
    private static final long CLICK_WINDOW_NANOS = 500_000_000L;

    private int anchorX = -1;
    private int anchorY = -1;
    private int focusX = -1;
    private int focusY = -1;
    private int clickCount;
    private long lastClickNanos;
    private int lastClickX = -1;
    private int lastClickY = -1;

    /**
     * The pane the press was resolved in, or null to select the whole frame.
     * Resolved on press and kept until the next press or {@link #clear()}.
     */
    private Rect zone;

    /**
     * @return true when a selection is currently visible
     */
    boolean hasSelection() {
        return anchorY >= 0 && focusY >= 0;
    }

    /**
     * Handles a left-button press: starts a new selection, or a word/row
     * selection when clicking again on the same cell within the double-click
     * window.
     * <p>
     * The press also resolves the selection {@link #zone(List) zone} from the
     * clamped click cell, before any double/triple click expands the anchor to
     * a word or a whole row: a word may reach across a modal border, but the
     * selection must still stay in the pane the user actually clicked.
     *
     * @param grid the last rendered frame snapshot, may be empty
     * @param zones the candidate panes, most specific first (see the caller for the order)
     */
    void press(final int rawX, final int rawY, final String[][] grid, final List<Rect> zones) {
        final var height = grid.length;
        if (height == 0 || grid[0].length == 0) {
            clear();
            return;
        }
        final var width = grid[0].length;
        final var x = Math.clamp(rawX, 0, width - 1);
        final var y = Math.clamp(rawY, 0, height - 1);
        zone = zone(zones, x, y);
        final var now = System.nanoTime();
        if (now - lastClickNanos <= CLICK_WINDOW_NANOS && y == lastClickY && Math.abs(x - lastClickX) <= 1) {
            clickCount = Math.min(3, clickCount + 1);
        } else {
            clickCount = 1;
        }
        lastClickNanos = now;
        lastClickX = x;
        lastClickY = y;
        anchorY = y;
        focusY = y;
        if (clickCount == 2) {
            anchorX = wordStart(grid, y, x);
            focusX = wordEnd(grid, y, x);
        } else if (clickCount == 3) {
            anchorX = 0;
            focusX = width - 1;
        } else {
            anchorX = x;
            focusX = x;
        }
    }

    /**
     * Extends the current selection to the given cell, by words or whole rows
     * when the selection was started with a double/triple click.
     */
    void drag(final int rawX, final int rawY, final String[][] grid) {
        if (!hasSelection() || grid.length == 0 || grid[0].length == 0) {
            return;
        }
        final var width = grid[0].length;
        final var x = Math.clamp(rawX, 0, width - 1);
        final var y = Math.clamp(rawY, 0, grid.length - 1);
        if (clickCount == 2) {
            focusX = x >= anchorX ? wordEnd(grid, y, x) : wordStart(grid, y, x);
            focusY = y;
        } else if (clickCount == 3) {
            focusX = width - 1;
            focusY = y;
        } else {
            focusX = x;
            focusY = y;
        }
    }

    /**
     * Drops the current selection.
     */
    void clear() {
        anchorX = -1;
        anchorY = -1;
        focusX = -1;
        focusY = -1;
        clickCount = 0;
        zone = null;
    }

    /**
     * Reverses the style of the selected cells so the selection is visible.
     * <p>
     * The highlight is clipped to the {@link #zone(List) zone} resolved by the
     * press, so the shaded area matches what {@link #copy(String[][])} would
     * extract; when the press fell in no zone the whole selected span is lit.
     */
    void highlight(final Buffer buffer) {
        if (!hasSelection()) {
            return;
        }
        final var width = buffer.width();
        final var bounds = bounds(width);
        for (var y = bounds.fromY(); y <= bounds.toY(); y++) {
            final var span = span(bounds, width, y);
            if (span == null) {
                continue;
            }
            buffer.setStyle(new Rect(span.startX, y, span.endX - span.startX + 1, 1), Style.EMPTY.reversed());
        }
    }

    /**
     * Extracts the selected text from the last rendered frame snapshot:
     * trailing whitespace is stripped from each row and trailing empty rows
     * are dropped.
     * <p>
     * The selection is clipped to the {@link #zone(List) zone} resolved by the
     * press: rows outside that zone's vertical span are skipped and each row's
     * start/end columns are clamped into the zone's horizontal span. This keeps
     * a drag that overflows its pane from pulling the content rendered
     * underneath it, or the left gutter/bar or the right sidebar/todos, into
     * the copied text. When the press fell in no zone the whole frame is
     * copied.
     *
     * @return the selected text, possibly empty, or null when nothing is selected
     */
    String copy(final String[][] grid) {
        if (!hasSelection() || grid.length == 0 || grid[0].length == 0) {
            return null;
        }
        final var width = grid[0].length;
        final var bounds = bounds(width);
        final var rows = new ArrayList<String>(bounds.toY() - bounds.fromY() + 1);
        for (var y = bounds.fromY(); y <= bounds.toY() && y < grid.length; y++) {
            final var span = span(bounds, width, y);
            if (span == null) {
                continue;
            }
            final var row = new StringBuilder(Math.max(0, span.endX - span.startX + 1));
            for (var x = span.startX; x <= span.endX; x++) {
                row.append(grid[y][x]);
            }
            // A boxed code block (Borders.ALL + padding) fills the whole copy zone, so its
            // right vertical border and its top/bottom rules fall inside the selection cut
            // and would otherwise pollute the clipboard (the left border is already excluded
            // by the zone clip, prose lines end in text, so only these box glyphs leak).
            final var stripped = stripBoxFraming(row.toString());
            if (stripped != null) {
                rows.add(stripped);
            }
        }
        while (!rows.isEmpty() && rows.getLast().isEmpty()) {
            rows.removeLast();
        }
        return String.join("\n", rows);
    }

    /** Box vertical border glyphs (a code block's left/right walls). */
    private static final String BOX_VERTICALS = "\u2502\u2503";

    /** Box horizontal rule glyphs (a code block's top/bottom walls and corners). */
    private static final String BOX_HORIZONTALS = "\u2500\u256D\u256E\u2570\u256F";

    /**
     * Removes the framing a boxed code block contributes to a copied row: the
     * right vertical border (plus the padding that precedes it) and rows that
     * are nothing but a horizontal rule (the block's top or bottom wall).
     * The left border is already excluded by the zone clip, so only these
     * right/rule glyphs reach here.
     *
     * @param raw the raw cell string for the row in the selected column span
     * @return the row without its box frame, or null when the row is a block
     *         border (the top/bottom rule) that must not be copied at all
     */
    private static String stripBoxFraming(final String raw) {
        var row = raw.stripTrailing();
        if (row.isEmpty()) {
            return row;
        }
        // a row that is nothing but box rule glyphs is the block's top/bottom wall:
        // drop it entirely so a full-block selection copies only the code lines
        final var body = row.stripLeading();
        if (!body.isEmpty() && body.chars().allMatch(c -> BOX_HORIZONTALS.indexOf(c) >= 0)) {
            return null;
        }
        // a trailing vertical border flags a boxed row: drop it and the padding on both
        // sides (the left padding leaks past the zone clip), while untouched prose keeps
        // its leading indentation
        if (BOX_VERTICALS.indexOf(row.charAt(row.length() - 1)) >= 0) {
            row = row.substring(0, row.length() - 1).stripTrailing().stripLeading();
        }
        return row;
    }

    /**
     * Selects the zone the given cell fell into, so a drag that starts in a
     * modal is confined to that modal, one starting in the chat body to the
     * chat and one starting in the prompt input to the input. Returns null
     * (whole frame) when the cell is in none of the given zones or there is
     * none.
     * <p>
     * The first matching zone wins, so callers must order the list most
     * specific first - typically the topmost overlay before the panes it covers.
     */
    private static Rect zone(final List<Rect> zones, final int x, final int y) {
        if (zones == null) {
            return null;
        }
        for (final var candidate : zones) {
            if (candidate != null && !candidate.isEmpty() && candidate.contains(x, y)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Computes the cell span of the selection on the given row, clamped into
     * the {@link #zone} resolved by the press (when any). Returns null for rows
     * outside the zone's vertical span or when the horizontal span collapses.
     */
    private Span span(final Bounds bounds, final int width, final int y) {
        final var clip = zone != null && !zone.isEmpty() ? zone : null;
        if (clip != null && (y < clip.y() || y >= clip.bottom())) {
            return null;
        }
        final var startX = Math.max(bounds.startX(y), clip == null ? 0 : clip.x());
        final var endX = Math.min(bounds.endX(y), Math.min(width - 1, clip == null ? width - 1 : clip.right() - 1));
        if (endX < startX) {
            return null;
        }
        return new Span(startX, endX);
    }

    private record Span(int startX, int endX) {}

    private Bounds bounds(final int width) {
        final var topIsAnchor = anchorY <= focusY;
        return new Bounds(
                topIsAnchor ? anchorY : focusY,
                topIsAnchor ? focusY : anchorY,
                topIsAnchor ? anchorX : focusX,
                topIsAnchor ? focusX : anchorX,
                width - 1);
    }

    /**
     * Linear (stream) selection bounds, like terminal emulators: the first
     * row runs from its start column to the end of the line and the last row
     * from its start to the end column; a single-row selection runs from the
     * leftmost to the rightmost column whatever the drag direction.
     */
    private record Bounds(int fromY, int toY, int fromX, int toX, int lastColumn) {
        private Bounds {
            fromX = Math.clamp(fromX, 0, lastColumn);
            toX = Math.clamp(toX, 0, lastColumn);
        }

        private int startX(final int y) {
            if (fromY == toY) {
                return Math.min(fromX, toX);
            }
            return y == fromY ? fromX : 0;
        }

        private int endX(final int y) {
            if (fromY == toY) {
                return Math.max(fromX, toX);
            }
            return y == toY ? toX : lastColumn;
        }
    }

    private static int wordStart(final String[][] grid, final int y, final int x) {
        var i = x;
        while (i > 0 && !grid[y][i - 1].isBlank()) {
            i--;
        }
        return i;
    }

    private static int wordEnd(final String[][] grid, final int y, final int x) {
        final var width = grid[y].length;
        var i = x;
        while (i < width - 1 && !grid[y][i + 1].isBlank()) {
            i++;
        }
        return i;
    }

    /**
     * Builds the OSC 52 escape sequence setting the system clipboard to the
     * given text.
     *
     * @param text the text to copy
     * @return the escape sequence to write to the terminal
     */
    static String osc52Payload(final String text) {
        return "\u001B]52;c;" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\u0007";
    }

    /**
     * Builds the screen snapshot used for selection highlighting and text
     * extraction: one symbol per cell, empty string for wide character
     * continuation cells.
     *
     * @return the grid of symbols, indexed by {@code grid[y][x]}
     */
    static String[][] snapshot(final Buffer buffer) {
        final var width = buffer.width();
        final var height = buffer.height();
        final var grid = new String[height][];
        for (var y = 0; y < height; y++) {
            final var row = new String[width];
            for (var x = 0; x < width; x++) {
                final var cell = buffer.get(x, y);
                row[x] = cell.isContinuation() ? "" : cell.symbol();
            }
            grid[y] = row;
        }
        return grid;
    }

    /**
     * Converts text rows into the grid form used by this state, for tests and
     * callers without a rendered frame. Rows are padded with spaces so the
     * grid is rectangular, like a real screen snapshot.
     *
     * @param rows the text rows
     * @return the grid of symbols, indexed by {@code grid[y][x]}
     */
    static String[][] grid(final List<String> rows) {
        final var width = rows.stream().mapToInt(String::length).max().orElse(0);
        return rows.stream()
                .map(row -> {
                    final var padded = String.format("%-" + width + "s", row);
                    return padded.chars()
                            .mapToObj(c -> String.valueOf((char) c))
                            .toArray(String[]::new);
                })
                .toArray(String[][]::new);
    }
}
