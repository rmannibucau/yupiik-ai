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

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
