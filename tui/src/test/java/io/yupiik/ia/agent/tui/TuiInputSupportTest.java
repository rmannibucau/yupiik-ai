package io.yupiik.ia.agent.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiInputSupportTest {
    @Test
    void slashCommandsAreComplete() {
        assertEquals(17, TuiInputSupport.SLASH_COMMANDS.size());
        assertEquals("/help", TuiInputSupport.SLASH_COMMANDS.get(0));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/quit"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/exit"));
        assertThrows(UnsupportedOperationException.class, () -> TuiInputSupport.SLASH_COMMANDS.add("/nope"));
    }

    @Test
    void nextWordStopsAtWordEnds() {
        assertEquals(3, TuiInputSupport.nextWord("foo bar baz", 0));
        assertEquals(7, TuiInputSupport.nextWord("foo bar baz", 4));
        assertEquals(11, TuiInputSupport.nextWord("foo bar baz", 8));
        assertEquals(3, TuiInputSupport.nextWord("foo", 0));
    }

    @Test
    void previousWordStopsAtWordStarts() {
        assertEquals(0, TuiInputSupport.previousWord("foo bar baz", 3));
        assertEquals(4, TuiInputSupport.previousWord("foo bar baz", 7));
        assertEquals(8, TuiInputSupport.previousWord("foo bar baz", 11));
        assertEquals(0, TuiInputSupport.previousWord("foo bar baz", 0));
    }

    @Test
    void firstLineExtractsAndCaps() {
        assertEquals("first", TuiInputSupport.firstLine("first\nsecond"));
        assertEquals("", TuiInputSupport.firstLine(null));
        assertEquals(123, TuiInputSupport.firstLine("x".repeat(500)).length());
        assertTrue(TuiInputSupport.firstLine("x".repeat(500)).endsWith("..."));
    }
}
