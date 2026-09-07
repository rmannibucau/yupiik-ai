package io.yupiik.ia.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseParserTest {
    private final SseParser parser = new SseParser();

    @Test
    void singleDataLine() {
        assertTrue(parser.feed("data: {\"a\":1}").isEmpty());
        final var events = parser.feed(null);
        assertEquals(1, events.size());
        assertEquals("{\"a\":1}", events.get(0).data());
        assertNull(events.get(0).event());
    }

    @Test
    void eventAndDataDispatchOnBlankLine() {
        assertTrue(parser.feed("event: response.completed").isEmpty());
        assertTrue(parser.feed("data: {\"x\":2}").isEmpty());
        final var events = parser.feed("");
        assertEquals(1, events.size());
        assertEquals("response.completed", events.get(0).event());
        assertEquals("{\"x\":2}", events.get(0).data());
    }

    @Test
    void multilineDataJoined() {
        assertTrue(parser.feed("data: line1").isEmpty());
        assertTrue(parser.feed("data: line2").isEmpty());
        final var events = parser.feed(null);
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void commentsIgnored() {
        assertTrue(parser.feed(": keep-alive").isEmpty());
        assertTrue(parser.feed(null).isEmpty());
    }

    @Test
    void blankPayloadNotDispatched() {
        assertTrue(parser.feed("event: ping").isEmpty());
        assertTrue(parser.feed("").isEmpty());
        final var events = parser.feed("data: real");
        assertTrue(events.isEmpty());
        final var flushed = parser.feed(null);
        assertEquals(List.of(new SseParser.Event(null, "real")), flushed);
    }

    @Test
    void dataWithoutSpace() {
        assertTrue(parser.feed("data:nospace").isEmpty());
        assertEquals("nospace", parser.feed(null).get(0).data());
    }
}
