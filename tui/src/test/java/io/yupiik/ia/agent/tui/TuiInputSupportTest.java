package io.yupiik.ia.agent.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void slashMatchesFiltersByPrefix() {
        assertEquals(List.of("/help"), TuiInputSupport.slashMatches("he"));
        assertEquals(List.of("/help"), TuiInputSupport.slashMatches("h"));
        assertEquals(List.of("/new"), TuiInputSupport.slashMatches("n"));
        assertEquals(17, TuiInputSupport.slashMatches("").size());
        assertEquals(List.of(), TuiInputSupport.slashMatches("zzz"));
    }

    @Test
    void agentOverrideSplitsKnownAgent() {
        final var override = TuiInputSupport.agentOverride("@coder do the thing", name -> name.equals("coder"));
        assertEquals("coder", override.agentName());
        assertEquals("do the thing", override.prompt());
    }

    @Test
    void agentOverrideKeepsTextForUnknownAgent() {
        final var override = TuiInputSupport.agentOverride("@nope do the thing", name -> false);
        assertEquals(null, override.agentName());
        assertEquals("@nope do the thing", override.prompt());
    }

    @Test
    void agentOverrideWithoutPromptKeepsFullText() {
        final var override = TuiInputSupport.agentOverride("@coder", name -> name.equals("coder"));
        assertEquals(null, override.agentName());
        assertEquals("@coder", override.prompt());
    }

    @Test
    void agentOverrideWithoutAgentPrefixKeepsFullText() {
        final var override = TuiInputSupport.agentOverride("plain prompt", name -> true);
        assertEquals(null, override.agentName());
        assertEquals("plain prompt", override.prompt());
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
