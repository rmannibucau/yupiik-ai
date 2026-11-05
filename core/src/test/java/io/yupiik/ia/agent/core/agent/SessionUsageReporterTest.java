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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SessionUsageReporterTest {

    @Test
    void pushUsageContextCarriesTheProvidedEstimateAndRefreshesTheGaugeCache() {
        final var events = new ArrayList<AgentEvent>();
        final var reporter = new SessionUsageReporter(
                new UsageTracker(null, null, null, Clock.systemUTC()) {
                    @Override
                    public double contextPercent(final int estimatedTokens) {
                        return 42.5;
                    }
                },
                null,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                        }
                    }
                },
                () -> null,
                "s1",
                () -> ChatSettings.EMPTY,
                () -> null,
                null,
                null);

        reporter.pushUsageContext(12_345, "model-x");

        assertEquals(1, events.size(), "pushUsageContext must emit exactly one event: " + events);
        final var pushed = (AgentEvent.UsageUpdated) events.get(0);
        assertEquals(12_345, pushed.estimatedContextTokens(), "the provided estimate must be carried verbatim");
        assertEquals(42.5, pushed.contextPercent(), "the percent must be recomputed from the provided estimate");
        assertEquals("model-x", pushed.model());
        assertEquals("s1", pushed.sessionId());
        assertEquals(-1, pushed.lastRealPromptTokens(), "no anchor: no real prompt base yet");
        assertEquals(-1, pushed.calibrationRatio(), "no anchor: no calibration ratio yet");
        assertTrue(pushed.quota() == AgentEvent.UsageUpdated.Quota.NONE, "no quota store: NONE quota");
    }
}
