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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import org.junit.jupiter.api.Test;

class ConversationCoordinatorTest {
    @Test
    void foldsEventsIntoTranscriptAndStats() {
        final var coordinator = new ConversationCoordinator();
        coordinator.onEvent(new AgentEvent.ToolStarted("bash", "{}"));
        coordinator.onEvent(new AgentEvent.ToolFinished("bash", "out", true));
        final var usage = new AgentEvent.UsageUpdated(null, 11, 22.0);
        coordinator.onEvent(usage);
        coordinator.onEvent(new AgentEvent.TurnMetrics(2, 30, 40, 45, 1, 500));
        coordinator.onEvent(new AgentEvent.TurnDone("done"));

        assertTrue(coordinator.transcript().transcript().toString().contains("**Assistant**\n\ndone"));
        assertEquals(1, coordinator.stats().toolsRun());
        assertNotNull(coordinator.stats().lastUsage());
        assertEquals(11, coordinator.stats().lastUsage().estimatedContextTokens());
        assertNotNull(coordinator.stats().lastTurnMetrics());
        assertEquals(2, coordinator.stats().lastTurnMetrics().llmCalls());
    }

    @Test
    void shellLifecycleEventsResetState() {
        final var coordinator = new ConversationCoordinator();
        coordinator.onEvent(new AgentEvent.ToolFinished("bash", "out", true));
        coordinator.onEvent(new AgentEvent.UsageUpdated(null, 11, 22.0));

        coordinator.onRegistered(new ShellRegistered(null));

        assertEquals("", coordinator.transcript().transcript().toString());
        assertEquals(0, coordinator.stats().toolsRun());
        assertNull(coordinator.stats().lastUsage());

        coordinator.onEvent(new AgentEvent.TurnMetrics(1, 0, 0, 0, 0, 0));
        coordinator.onUnregistered(new ShellUnregistered(null));
        assertNull(coordinator.stats().lastTurnMetrics());
    }
}
