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
     * @return true when a selection is currently visible
     */
    boolean hasSelection() {
        return anchorY >= 0 && focusY >= 0;
    }

    /**
     * Handles a left-button press: starts a new selection, or a word/row
     * selection when clicking again on the same cell within the double-click
     * window.
     *
     * @param grid the last rendered frame snapshot, may be empty
     */
    void press(final int rawX, final int rawY, final String[][] grid) {
        final var height = grid.length;
        if (height == 0 || grid[0].length == 0) {
            clear();
            return;
        }
        final var width = grid[0].length;
        final var x = clamp(rawX, 0, width - 1);
        final var y = clamp(rawY, 0, height - 1);
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
        final var x = clamp(rawX, 0, width - 1);
        final var y = clamp(rawY, 0, grid.length - 1);
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
    }

    /**
     * Reverses the style of the selected cells so the selection is visible.
     */
    void highlight(final Buffer buffer) {
        if (!hasSelection()) {
            return;
        }
        final var width = buffer.width();
        final var bounds = bounds(width);
        for (var y = bounds.fromY(); y <= bounds.toY(); y++) {
            final var startX = bounds.startX(y);
            final var endX = Math.min(bounds.endX(y), width - 1);
            if (endX < startX) {
                continue;
            }
            buffer.setStyle(new Rect(startX, y, endX - startX + 1, 1), Style.EMPTY.reversed());
        }
    }

    /**
     * Extracts the selected text from the last rendered frame snapshot:
     * trailing whitespace is stripped from each row and trailing empty rows
     * are dropped.
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
            final var startX = bounds.startX(y);
            final var endX = Math.min(bounds.endX(y), width - 1);
            final var row = new StringBuilder(Math.max(0, endX - startX + 1));
            for (var x = startX; x <= endX; x++) {
                row.append(grid[y][x]);
            }
            rows.add(row.toString().stripTrailing());
        }
        while (!rows.isEmpty() && rows.getLast().isEmpty()) {
            rows.removeLast();
        }
        return String.join("\n", rows);
    }

    private Bounds bounds(final int width) {
        final var topIsAnchor = anchorY <= focusY;
        return new Bounds(
                topIsAnchor ? anchorY : focusY, topIsAnchor ? focusY : anchorY,
                topIsAnchor ? anchorX : focusX, topIsAnchor ? focusX : anchorX,
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
            fromX = Math.max(0, Math.min(fromX, lastColumn));
            toX = Math.max(0, Math.min(toX, lastColumn));
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

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(value, max));
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
                    return padded.chars().mapToObj(c -> String.valueOf((char) c)).toArray(String[]::new);
                })
                .toArray(String[][]::new);
    }
}
