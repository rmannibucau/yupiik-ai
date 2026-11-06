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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.usage.CodexUsageSnapshot;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SessionUsageReporterTest {

    @Test
    void completionPushesCachedQuotaWithoutRefreshingOrResettingContext() {
        final var events = new ArrayList<AgentEvent.UsageUpdated>();
        final var store = new CompletionStore();
        final var reporter = new SessionUsageReporter(
                null, store, new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent.UsageUpdated usage) {
                            events.add(usage);
                        }
                    }
                }, () -> null, "s1", () -> ChatSettings.EMPTY, () -> null, null, null);
        reporter.pushUsageContext(1234, "model-x");
        reporter.refreshUsage("model-x");
        final var reads = store.refreshingReads;
        final var before = events.getLast();
        store.completion.run();
        final var after = events.getLast();
        assertEquals(before.estimatedContextTokens(), after.estimatedContextTokens());
        assertEquals(before.contextPercent(), after.contextPercent());
        assertEquals(before.usage(), after.usage());
        assertEquals("model-x", after.model());
        assertEquals("s1", after.sessionId());
        assertTrue(after.quota().supported());
        assertEquals(reads, store.refreshingReads, "completion must not trigger a quota refresh");
        final var count = events.size();
        reporter.close();
        assertEquals("s1", store.closedSession);
        store.completion.run();
        assertEquals(count, events.size(), "late completion after close must not publish");
    }

    private static class CompletionStore extends QuotaSnapshotStore {
        private Runnable completion;
        private int refreshingReads;
        private String closedSession;

        @Override
        public void listen(final String id, final Runnable listener) {
            assertEquals("s1", id);
            completion = listener;
        }

        @Override
        public void attach(final String id, final ChatSettings settings) {
            assertTrue(completion != null, "subscribe before starting the initial fetch");
        }

        @Override
        public QuotaView snapshot(final String id, final boolean force) {
            refreshingReads++;
            return cachedSnapshot(id);
        }

        @Override
        public QuotaView cachedSnapshot(final String id) {
            return new QuotaView(true, true, CodexUsageSnapshot.empty());
        }

        @Override
        public void close(final String id) {
            closedSession = id;
        }
    }

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
        assertSame(pushed.quota(), AgentEvent.UsageUpdated.Quota.NONE, "no quota store: NONE quota");
    }
}
