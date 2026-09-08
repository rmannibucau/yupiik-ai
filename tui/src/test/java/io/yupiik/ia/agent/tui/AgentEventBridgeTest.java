package io.yupiik.ia.agent.tui;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventBridgeTest {
    @Test
    void withoutSinkOnEventIsNoOp() {
        new AgentEventBridge().onEvent(new AgentEvent.Status("ignored"));
    }

    @Test
    void sinkReceivesDispatchedEvents() {
        final var bridge = new AgentEventBridge();
        final var received = new ArrayList<AgentEvent>();
        bridge.sink(received::add);
        bridge.onEvent(new AgentEvent.TextDelta("a"));
        bridge.onEvent(new AgentEvent.TurnDone("b"));
        assertEquals(2, received.size());
        assertEquals("a", ((AgentEvent.TextDelta) received.get(0)).delta());
        assertTrue(received.get(1) instanceof AgentEvent.TurnDone);
    }

    @Test
    void clearingSinkStopsDispatch() {
        final var bridge = new AgentEventBridge();
        final var received = new ArrayList<AgentEvent>();
        bridge.sink(received::add);
        bridge.sink(null);
        bridge.onEvent(new AgentEvent.Status("dropped"));
        assertEquals(List.of(), received);
    }
}
