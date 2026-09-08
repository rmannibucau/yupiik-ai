package io.yupiik.ia.agent.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("world", selection.copy(grid));
    }

    @Test
    void dragUpwardsReversesAnchorAndFocus() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("first", "second"));
        selection.press(3, 1, grid);
        selection.drag(2, 0, grid);
        assertEquals("rst\nseco", selection.copy(grid));
    }

    @Test
    void backwardsDragFollowsStreamOrder() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abcde", "fghij"));
        selection.press(1, 1, grid);
        selection.drag(3, 0, grid);
        assertEquals("de\nfg", selection.copy(grid));
    }

    @Test
    void singleRowBackwardDragKeepsTheRange() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("hello world"));
        selection.press(4, 0, grid);
        selection.drag(1, 0, grid);
        assertEquals("ello", selection.copy(grid));
    }

    @Test
    void multiRowSelectionStripsTrailingSpacesAndDropsTrailingEmptyRows() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc   ", "def", "     "));
        selection.press(0, 0, grid);
        selection.drag(4, 2, grid);
        assertEquals("abc\ndef", selection.copy(grid));
    }

    @Test
    void doubleClickSelectsWord() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("foo bar baz"));
        selection.press(4, 0, grid);
        selection.press(5, 0, grid);
        assertEquals("bar", selection.copy(grid));
    }

    @Test
    void doubleClickDragExtendsByWords() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("foo bar baz qux"));
        selection.press(4, 0, grid);
        selection.press(4, 0, grid);
        selection.drag(9, 0, grid);
        assertEquals("bar baz", selection.copy(grid));
    }

    @Test
    void tripleClickSelectsWholeRow() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("  indented row  ", "other"));
        selection.press(2, 0, grid);
        selection.press(3, 0, grid);
        selection.press(2, 0, grid);
        assertEquals("  indented row", selection.copy(grid));
    }

    @Test
    void tripleClickDragExtendsRows() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("one", "two", "three", "four"));
        selection.press(1, 1, grid);
        selection.press(1, 1, grid);
        selection.press(1, 1, grid);
        selection.drag(1, 2, grid);
        assertEquals("two\nthree", selection.copy(grid));
    }

    @Test
    void plainClickSelectsSingleCell() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc"));
        selection.press(1, 0, grid);
        assertEquals("b", selection.copy(grid));
    }

    @Test
    void copyWithoutSelectionIsNull() {
        final var selection = new SelectionState();
        assertNull(selection.copy(SelectionState.grid(List.of("abc"))));
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
        assertNull(selection.copy(grid));
    }

    @Test
    void highlightReversesSelectedCellsOnly() {
        final var selection = new SelectionState();
        final var buffer = Buffer.filled(new Rect(0, 0, 5, 3), new Cell("x", Style.EMPTY));
        final var grid = SelectionState.snapshot(buffer);
        selection.press(1, 1, grid);
        selection.drag(3, 1, grid);
        selection.highlight(buffer);
        for (var x = 0; x < 5; x++) {
            final var reversed = buffer.get(x, 1).style().effectiveModifiers().contains(Modifier.REVERSED);
            assertEquals(x >= 1 && x <= 3, reversed, "cell " + x);
        }
        assertFalse(buffer.get(1, 0).style().effectiveModifiers().contains(Modifier.REVERSED));
    }

    @Test
    void clampsCoordinatesOutsideTheGrid() {
        final var selection = new SelectionState();
        final var grid = SelectionState.grid(List.of("abc", "def"));
        selection.press(100, 100, grid);
        selection.drag(-50, -50, grid);
        assertEquals("abc\ndef", selection.copy(grid));
    }

    @Test
    void osc52PayloadEncodesUtf8Text() {
        assertEquals("\u001B]52;c;aGk=\u0007", SelectionState.osc52Payload("hi"));
        assertEquals("\u001B]52;c;" + Base64.getEncoder().encodeToString("h\u00e9llo".getBytes(StandardCharsets.UTF_8)) + "\u0007",
                SelectionState.osc52Payload("h\u00e9llo"));
    }

    @Test
    void gridHelperSplitsCharacters() {
        final var grid = SelectionState.grid(List.of("ab"));
        assertEquals("a", grid[0][0]);
        assertEquals("b", grid[0][1]);
        assertEquals(2, grid[0].length);
    }
}
