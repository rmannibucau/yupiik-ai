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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionStateTest {
    @Test
    void bufferCellsAreReadByColumnThenRow() {
        final var buffer = Buffer.withLines("ab", "cd");
        assertEquals("a", buffer.get(0, 0).symbol());
        assertEquals("b", buffer.get(1, 0).symbol());
        assertEquals("c", buffer.get(0, 1).symbol());
        assertEquals("d", buffer.get(1, 1).symbol());
    }

    @Test
    void snapshotIndexesByRowThenColumn() {
        final var grid = SelectionState.snapshot(Buffer.withLines("ab", "cd"));
        assertEquals("a", grid[0][0]);
        assertEquals("b", grid[0][1]);
        assertEquals("c", grid[1][0]);
        assertEquals("d", grid[1][1]);
    }

    @Test
    void dragSelectionCopiesTheSelectedText() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("hello world", "second row"));
        selection.press(6, 0, grid);
        selection.drag(10, 0, grid);
        assertEquals("world", selection.copy(grid, null));
    }

    @Test
    void dragUpwardsReversesAnchorAndFocus() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("first", "second"));
        selection.press(3, 1, grid);
        selection.drag(2, 0, grid);
        assertEquals("rst\nseco", selection.copy(grid, null));
    }

    @Test
    void backwardsDragFollowsStreamOrder() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abcde", "fghij"));
        selection.press(1, 1, grid);
        selection.drag(3, 0, grid);
        assertEquals("de\nfg", selection.copy(grid, null));
    }

    @Test
    void singleRowBackwardDragKeepsTheRange() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("hello world"));
        selection.press(4, 0, grid);
        selection.drag(1, 0, grid);
        assertEquals("ello", selection.copy(grid, null));
    }

    @Test
    void multiRowSelectionStripsTrailingSpacesAndDropsTrailingEmptyRows() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc   ", "def", "     "));
        selection.press(0, 0, grid);
        selection.drag(4, 2, grid);
        assertEquals("abc\ndef", selection.copy(grid, null));
    }

    @Test
    void doubleClickSelectsWord() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("foo bar baz"));
        selection.press(4, 0, grid);
        selection.press(5, 0, grid);
        assertEquals("bar", selection.copy(grid, null));
    }

    @Test
    void doubleClickDragExtendsByWords() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("foo bar baz qux"));
        selection.press(4, 0, grid);
        selection.press(4, 0, grid);
        selection.drag(9, 0, grid);
        assertEquals("bar baz", selection.copy(grid, null));
    }

    @Test
    void tripleClickSelectsWholeRow() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("  indented row  ", "other"));
        selection.press(2, 0, grid);
        selection.press(3, 0, grid);
        selection.press(2, 0, grid);
        assertEquals("  indented row", selection.copy(grid, null));
    }

    @Test
    void tripleClickDragExtendsRows() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("one", "two", "three", "four"));
        selection.press(1, 1, grid);
        selection.press(1, 1, grid);
        selection.press(1, 1, grid);
        selection.drag(1, 2, grid);
        assertEquals("two\nthree", selection.copy(grid, null));
    }

    @Test
    void plainClickSelectsSingleCell() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc"));
        selection.press(1, 0, grid);
        assertEquals("b", selection.copy(grid, null));
    }

    @Test
    void copyWithoutSelectionIsNull() {
        final var selection = new SelectionState();
        assertNull(selection.copy(SelectionState.grid(List.of("abc")), null));
    }

    @Test
    void pressOnEmptyGridClears() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc"));
        selection.press(0, 0, grid);
        selection.press(0, 0, new String[0][]);
        assertFalse(selection.hasSelection());
    }

    @Test
    void clearDropsTheSelection() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc"));
        selection.press(0, 0, grid);
        selection.drag(2, 0, grid);
        selection.clear();
        assertFalse(selection.hasSelection());
        assertNull(selection.copy(grid, null));
    }

    @Test
    void highlightReversesSelectedCellsOnly() {
        final var selection = new SelectionState();
        final var buffer = Buffer.filled(new Rect(0, 0, 5, 3), new Cell("x", Style.EMPTY));
        final var grid = SelectionState.snapshot(buffer);
        selection.press(1, 1, grid);
        selection.drag(3, 1, grid);
        selection.highlight(buffer, null);
        for (var x = 0; x < 5; x++) {
            final var reversed = buffer.get(x, 1).style().effectiveModifiers().contains(Modifier.REVERSED);
            assertEquals(x >= 1 && x <= 3, reversed, "cell " + x);
        }
        assertFalse(buffer.get(1, 0).style().effectiveModifiers().contains(Modifier.REVERSED));
    }

    @Test
    void highlightIsClippedToTheZone() {
        final var selection = new SelectionState();
        final var buffer = Buffer.filled(new Rect(0, 0, 8, 3), new Cell("x", Style.EMPTY));
        final var grid = SelectionState.snapshot(buffer);
        // zone spans columns 2..5 (right-exclusive 6) and rows 0..2; the anchor starts inside it
        final var zone = new Rect(2, 0, 4, 3);
        selection.press(2, 0, grid);
        selection.drag(7, 2, grid);
        selection.highlight(buffer, List.of(zone));
        for (var y = 0; y < 3; y++) {
            for (var x = 0; x < 8; x++) {
                final var reversed =
                        buffer.get(x, y).style().effectiveModifiers().contains(Modifier.REVERSED);
                assertEquals(x >= 2 && x <= 5, reversed, "cell " + x + "," + y);
            }
        }
    }

    @Test
    void clampsCoordinatesOutsideTheGrid() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc", "def"));
        selection.press(100, 100, grid);
        selection.drag(-50, -50, grid);
        assertEquals("abc\ndef", selection.copy(grid, null));
    }

    @Test
    void multiLineDragIsClippedToTheZoneColumns() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("gghellogg", "ggworldgg", "gg     gg"));
        // zone spans the content body only, excluding the left bar (cols 0-1) and the right pane (col 7+)
        final var zone = new Rect(2, 0, 5, 3);
        selection.press(2, 0, grid);
        selection.drag(8, 2, grid);
        assertEquals("hello\nworld", selection.copy(grid, List.of(zone)));
    }

    @Test
    void singleRowSelectionIsClippedToTheZone() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("gghellogg"));
        final var zone = new Rect(2, 0, 5, 1);
        selection.press(2, 0, grid);
        selection.drag(8, 0, grid);
        assertEquals("hello", selection.copy(grid, List.of(zone)));
    }

    @Test
    void rowsOutsideTheZoneVerticalSpanAreSkipped() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc", "def", "ghi", "jkl"));
        // zone spans rows 1..2 (exclusive 3); the anchor starts inside it and the drag runs below
        final var zone = new Rect(0, 1, 3, 2);
        selection.press(0, 1, grid);
        selection.drag(2, 3, grid);
        assertEquals("def\nghi", selection.copy(grid, List.of(zone)));
    }

    @Test
    void anchorOutsideEveryZoneFallsBackToTheWholeFrame() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc", "def"));
        final var zone = new Rect(0, 1, 3, 1);
        selection.press(0, 0, grid);
        selection.drag(2, 1, grid);
        assertEquals("abc\ndef", selection.copy(grid, List.of(zone)));
    }

    @Test
    void anchorSelectsItsOwnZoneFromSeveralCandidates() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("gghellogg", "ggworldgg", "ccinputcc"));
        final var chat = new Rect(2, 0, 5, 2);
        final var input = new Rect(2, 2, 5, 1);
        // the drag starts in the chat zone, so the input zone is ignored
        selection.press(2, 0, grid);
        selection.drag(8, 1, grid);
        assertEquals("hello\nworld", selection.copy(grid, List.of(chat, input)));
        // a drag starting in the input zone is confined to the input
        selection.clear();
        selection.press(2, 2, grid);
        selection.drag(8, 2, grid);
        assertEquals("input", selection.copy(grid, List.of(chat, input)));
    }

    @Test
    void emptyZoneFallsBackToTheWholeFrame() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc", "def"));
        selection.press(0, 0, grid);
        selection.drag(2, 1, grid);
        assertEquals("abc\ndef", selection.copy(grid, List.of(Rect.ZERO)));
    }

    @Test
    void osc52PayloadEncodesUtf8Text() {
        assertEquals("\u001B]52;c;aGk=\u0007", SelectionState.osc52Payload("hi"));
        assertEquals(
                "\u001B]52;c;" + Base64.getEncoder().encodeToString("héllo".getBytes(StandardCharsets.UTF_8))
                        + "\u0007",
                SelectionState.osc52Payload("héllo"));
    }

    @Test
    void gridHelperSplitsCharacters() {
        final var grid = SelectionState.grid(List.of("ab"));
        assertEquals("a", grid[0][0]);
        assertEquals("b", grid[0][1]);
        assertEquals(2, grid[0].length);
    }
}
