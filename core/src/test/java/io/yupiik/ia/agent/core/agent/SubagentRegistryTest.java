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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentRegistryTest {
    @TempDir
    Path storageHome;

    private CodingAgentConfiguration config(final Map<String, String> overrides) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.subagents.enabled", "true");
        entries.put("yca.subagents.maxConcurrent", "2");
        entries.put("yca.llm.maxConcurrent", "4");
        entries.putAll(overrides);
        return TestConfigs.codingAgent(entries);
    }

    private ProviderManager providerManager(final int maxConcurrent) {
        return new ProviderManager() {
            @Override
            public ProviderConfiguration current() {
                return new ProviderConfiguration(
                        "default",
                        "http://test",
                        "key",
                        "model",
                        ApiFlavor.AUTO,
                        -1,
                        -1,
                        "",
                        null,
                        List.of(),
                        maxConcurrent,
                        300,
                        "",
                        Map.of(),
                        Map.of(),
                        "",
                        null);
            }
        };
    }

    @Test
    void startSucceedsWhenEnabledAndUnderLimit() {
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(4));
        final var state = registry.start("parent", "task");
        assertTrue(state.isPresent());
        assertEquals("task", state.get().task());
    }

    @Test
    void startFailsWhenDisabled() {
        final var registry = new SubagentRegistry(config(Map.of("yca.subagents.enabled", "false")), providerManager(4));
        final var state = registry.start("parent", "task");
        assertFalse(state.isPresent());
    }

    @Test
    void startFailsWhenAtMaxConcurrent() {
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "1")), providerManager(3));
        assertTrue(registry.start("parent", "t1").isPresent());
        assertTrue(registry.start("parent", "t2").isEmpty());
    }

    @Test
    void completeReleasesSemaphoreKeepsStateForCollect() {
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "1")), providerManager(3));
        final var state = registry.start("parent", "task").orElseThrow();
        registry.complete(state.id(), SubagentRegistry.SubagentResult.success("done", "conv", List.of()));
        // State should still be available for collect
        assertTrue(registry.get(state.id()).isPresent());
        // But semaphore is released, so new subagent can start
        assertTrue(registry.start("parent", "t2").isPresent());
    }

    @Test
    void cancelReleasesSemaphoreAndRemovesState() {
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "1")), providerManager(3));
        final var state = registry.start("parent", "task").orElseThrow();
        registry.cancel(state.id());
        // Cancel removes state immediately
        assertTrue(registry.get(state.id()).isEmpty());
        assertTrue(registry.start("parent", "t2").isPresent());
    }

    @Test
    void cancelByParentSessionCancelsOnlyThatSessionsChildren() {
        // the latch behind "the parent turn was interrupted": children of that session stop,
        // children of other tabs keep running
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "4")), providerManager(5));
        final var mine = registry.start("s1", "t1").orElseThrow();
        final var mineToo = registry.start("s1", "t2").orElseThrow();
        final var other = registry.start("s2", "t3").orElseThrow();

        assertEquals(2, registry.cancelByParentSession("s1"));
        assertTrue(registry.get(mine.id()).isEmpty(), "a cancelled child is removed");
        assertTrue(registry.get(mineToo.id()).isEmpty(), "a cancelled child is removed");
        assertTrue(registry.get(other.id()).isPresent(), "another session's child is untouched");
        assertEquals(1, registry.cancelByParentSession("s2"));
        assertEquals(0, registry.cancelByParentSession(null), "a blank session id cancels nothing");
        assertEquals(0, registry.cancelByParentSession(""), "a blank session id cancels nothing");
    }

    @Test
    void getReturnsStateWhenActive() {
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(4));
        final var state = registry.start("parent", "task").orElseThrow();
        final var got = registry.get(state.id());
        assertTrue(got.isPresent());
        assertEquals(state.id(), got.get().id());
    }

    @Test
    void listActiveReturnsAllActive() {
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(4));
        registry.start("parent", "t1");
        registry.start("parent", "t2");
        final var active = registry.listActive();
        assertEquals(2, active.size());
    }

    @Test
    void updateSemaphoreRecalculatesOnProviderChange() {
        // the subagent budget is a process-local cap (number of simultaneous AgentLoops), not the
        // provider's in-flight LLM budget - the shared client queues any LLM overflow, so there is
        // no "minus one" reservation for the main session.
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "3")), providerManager(2));
        assertEquals(2, registry.maxConcurrent()); // min(2, 3) = 2
        final var newRegistry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "3")), providerManager(5));
        assertEquals(3, newRegistry.maxConcurrent()); // min(5, 3) = 3
    }

    @Test
    void updateSemaphoreBoundedByConfigAndProvider() {
        // config cap is the binding constraint
        final var cfgBounded =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "1")), providerManager(10));
        assertEquals(1, cfgBounded.maxConcurrent()); // min(10, 1) = 1

        // provider cap is the binding constraint
        final var providerBounded =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "10")), providerManager(2));
        assertEquals(2, providerBounded.maxConcurrent()); // min(2, 10) = 2

        // provider with no explicit cap (maxConcurrent < 0) falls back to the global yca.llm.maxConcurrent
        // (default 4 here); config cap wins over both
        final var fallbackModelMax =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "2")), providerManager(-1));
        assertEquals(2, fallbackModelMax.maxConcurrent()); // min(4, 2) = 2 via the llm fallback

        // a provider budget of exactly the config cap keeps the cap, no longer degraded by -1
        final var equal = new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "2")), providerManager(2));
        assertEquals(2, equal.maxConcurrent()); // min(2, 2) = 2
    }

    @Test
    void startSucceedsWithOneProviderSlot() {
        // with provider maxConcurrent=1 the registry still allows one subagent: the shared client
        // queues the LLM request rather than reserving the only slot for the (possibly idle) main.
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "4")), providerManager(1));
        assertEquals(1, registry.maxConcurrent()); // min(1, 4) = 1
        assertTrue(registry.start("parent", "t1").isPresent());
    }

    @Test
    void collectReturnsResultEvenAfterStateEvictedByCleanup() {
        // the get -> collect race: once done states exceed maxCompletedStates, cleanup() evicts them
        // from the subagents map, but collect must still return the finished result via the cache.
        // provider=2, subagents=2 -> maxConcurrent=min(2,2)=2, maxCompletedStates=max(3,6)=6.
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "2")), providerManager(2));
        // complete more subagents than maxCompletedStates (6) so cleanup() LRU-evicts the oldest done
        // state from the map. Each start acquires a permit and each complete releases it, so 10 cycles run.
        final var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            final var s = registry.start("parent", "task" + i).orElseThrow();
            ids.add(s.id());
            registry.complete(s.id(), SubagentRegistry.SubagentResult.success("done-" + s.id(), "conv", List.of()));
        }
        final var oldest = ids.get(0);
        // the result-cache (bound >= 64) keeps every completed result even after the matching state
        // was LRU-evicted from the bounded state map, so a delayed collect still resolves
        final var collected = registry.collect(oldest);
        assertTrue(collected.isPresent(), "collect must return an evicted-but-uncollected result: " + oldest);
        assertEquals("done-" + oldest, collected.get().summary());
    }

    @Test
    void collectIsOneShot() {
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "2")), providerManager(5));
        final var state = registry.start("parent", "task").orElseThrow();
        registry.complete(state.id(), SubagentRegistry.SubagentResult.success("done", "conv", List.of()));
        assertTrue(registry.collect(state.id()).isPresent(), "first collect returns the result");
        assertTrue(registry.collect(state.id()).isEmpty(), "second collect is empty (one-shot)");
    }
}
