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

import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.time.Clock;
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

    /** A fake provider package with the given maxConcurrent, resolvable by id ("default"). */
    private ProviderManager providerManager(final int maxConcurrent) {
        return providerManager("default", maxConcurrent);
    }

    private ProviderManager providerManager(final String id, final int maxConcurrent) {
        return new ProviderManager() {
            @Override
            public ProviderConfiguration current() {
                return provider("default");
            }

            @Override
            public List<ProviderConfiguration> providers() {
                return List.of(provider("default"));
            }

            @Override
            public ProviderConfiguration provider(final String id) {
                return new ProviderConfiguration(
                        id == null ? "default" : id,
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
                        null,
                        null,
                        null,
                        null,
                        null);
            }
        };
    }

    @Test
    void resolvesExactNamesWithinSessionIncludingCompletedChildren() {
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(10));
        final var mine = registry.start("parent", "task", "review code").orElseThrow();
        registry.start("other", "task", "review code").orElseThrow();
        registry.complete(mine.id(), SubagentRegistry.SubagentResult.success("done", "conversation", List.of()));

        assertEquals(List.of(mine), registry.resolve("parent", "review code"));
        assertTrue(registry.resolve("parent", "review").isEmpty());
        assertTrue(registry.resolve("parent", "Review code").isEmpty());
        assertTrue(registry.resolve("missing", "review code").isEmpty());
        assertTrue(registry.resolve("parent", null).isEmpty());
        assertTrue(registry.resolve("parent", "  ").isEmpty());
        assertTrue(registry.resolve("parent", "").isEmpty());
    }

    @Test
    void duplicateNamesAreAmbiguousButGlobalExactIdWinsOverLocalNames() {
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(10));
        final var first = registry.start("parent", "task", "review").orElseThrow();
        final var second = registry.start("parent", "task", "review").orElseThrow();
        final var elsewhere = registry.start("other", "task", "review").orElseThrow();
        registry.start("parent", "task", elsewhere.id()).orElseThrow();

        final var matches = registry.resolve("parent", "review");
        assertEquals(2, matches.size());
        assertTrue(matches.containsAll(List.of(first, second)));
        final var diagnostic = registry.selectionError("review", matches);
        assertTrue(diagnostic.contains("Ambiguous"));
        assertTrue(diagnostic.contains(first.id()));
        assertTrue(diagnostic.contains(second.id()));
        assertFalse(diagnostic.contains(elsewhere.id()));
        assertEquals(List.of(elsewhere), registry.resolve("parent", elsewhere.id()));
        assertEquals("Unknown subagent: missing", registry.selectionError("missing", List.of()));
    }

    @Test
    void completionCandidatesAreScopedSortedAndDeduplicated() {
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(10));
        final var first = registry.start("parent", "task", "review code").orElseThrow();
        final var second = registry.start("parent", "task", "review code").orElseThrow();
        final var blank = registry.start("parent", "task", "  ").orElseThrow();
        final var unnamed = registry.start("parent", "task").orElseThrow();
        final var idNamed = registry.start("parent", "task", first.id()).orElseThrow();
        registry.start("other", "task", "elsewhere").orElseThrow();
        registry.complete(first.id(), SubagentRegistry.SubagentResult.success("done", "conversation", List.of()));

        assertEquals(
                List.of(first.id(), second.id(), blank.id(), unnamed.id(), idNamed.id(), "review code").stream()
                        .sorted()
                        .toList(),
                registry.completionCandidates("parent"));
        assertTrue(registry.completionCandidates("missing").isEmpty());
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
    void providerEntryWithMaxConcurrentOneForbidsSubagents() {
        // N == 1: the main session reserves the only slot -> subagents are forbidden on that provider,
        // even with zero in flight and a full session budget.
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(1));
        final var result = registry.start(
                new AgentRunSpec("parent", "t1", null, null, null, null, null, null), "default", new SubagentBudget(4));
        assertFalse(result.admitted(), "N=1 must forbid subagents on that provider");
        assertEquals(SubagentRegistry.RefusalReason.PROVIDER_FORBIDDEN, result.reason());
    }

    @Test
    void providerNAllowsUpToNMinusOneSubagentsThenRefuses() {
        // N = 4 -> at most 3 concurrent subagents on that provider (the main session holds 1 slot).
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(4));
        final var specs = new AgentRunSpec("parent", "t", null, null, null, null, null, null);
        assertTrue(registry.start(specs, "default", null).admitted());
        assertTrue(registry.start(specs, "default", null).admitted());
        assertTrue(registry.start(specs, "default", null).admitted());
        final var fourth = registry.start(specs, "default", null);
        assertFalse(fourth.admitted(), "N-1 = 3 in flight already: the 4th must be refused");
        assertEquals(SubagentRegistry.RefusalReason.PROVIDER_FULL, fourth.reason());
    }

    @Test
    void providerCapIsGlobalAcrossSessions() {
        // N = 2 -> at most 1 subagent on that provider, regardless of which session spawns it.
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(2));
        final var s1 = new AgentRunSpec("s1", "t1", null, null, null, null, null, null);
        final var s2 = new AgentRunSpec("s2", "t2", null, null, null, null, null, null);
        assertTrue(registry.start(s1, "default", null).admitted());
        final var fromOtherSession = registry.start(s2, "default", null);
        assertFalse(fromOtherSession.admitted(), "the provider cap is global per instance, not per session");
        assertEquals(SubagentRegistry.RefusalReason.PROVIDER_FULL, fromOtherSession.reason());
    }

    @Test
    void sessionCapIsEnforcedPerSessionNotGlobal() {
        // the per-session cap (yca.subagents.maxConcurrent=2) is owned by each session's budget:
        // session s1 and s2 each get their own 2 in-flight children.
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(8));
        final var s1 = new AgentRunSpec("s1", "t1", null, null, null, null, null, null);
        final var s2 = new AgentRunSpec("s2", "t2", null, null, null, null, null, null);
        final var budgetS1 = new SubagentBudget(2);
        final var budgetS2 = new SubagentBudget(2);
        assertTrue(registry.start(s1, "default", budgetS1).admitted());
        assertTrue(registry.start(s1, "default", budgetS1).admitted());
        assertTrue(registry.start(s2, "default", budgetS2).admitted());
        assertTrue(registry.start(s2, "default", budgetS2).admitted());
        // both sessions are now at their own cap; one more from s1 is refused
        final var extra = registry.start(s1, "default", budgetS1);
        assertFalse(extra.admitted());
        assertEquals(SubagentRegistry.RefusalReason.SESSION_FULL, extra.reason());
    }

    @Test
    void sessionCapRefusalDoesNotLeakProviderSlot() {
        // the session cap refuses after the provider reserve: the provider counter must be rolled
        // back so a later spawn (when the session budget frees up) still has its N-1 slots.
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(8)); // N-1 = 7, session 1
        final var budget = new SubagentBudget(1);
        assertTrue(registry.start(new AgentRunSpec("s1", "t1", null, null, null, null, null, null), "default", budget)
                .admitted());
        // provider still has plenty of slots, but the session budget is exhausted
        final var refused =
                registry.start(new AgentRunSpec("s1", "t2", null, null, null, null, null, null), "default", budget);
        assertFalse(refused.admitted());
        assertEquals(SubagentRegistry.RefusalReason.SESSION_FULL, refused.reason());
        // release the first child: both the session permit and the provider slot free up
        registry.complete(
                registry.listActive().get(0).id(), SubagentRegistry.SubagentResult.success("done", "conv", List.of()));
        assertTrue(registry.start(new AgentRunSpec("s1", "t3", null, null, null, null, null, null), "default", budget)
                .admitted());
    }

    @Test
    void completeReleasesBothLimitsAndKeepsStateForCollect() {
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "1")), providerManager(3));
        final var budget = new SubagentBudget(1);
        final var state = registry.start(
                        new AgentRunSpec("parent", "task", null, null, null, null, null, null), "default", budget)
                .state();
        registry.complete(state.id(), SubagentRegistry.SubagentResult.success("done", "conv", List.of()));
        // state still available for collect
        assertTrue(registry.get(state.id()).isPresent());
        // both limits released: a new subagent can start
        assertTrue(
                registry.start(new AgentRunSpec("parent", "t2", null, null, null, null, null, null), "default", budget)
                        .admitted());
    }

    @Test
    void cancelReleasesBothLimitsAndRemovesState() {
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "1")), providerManager(3));
        final var budget = new SubagentBudget(1);
        final var state = registry.start(
                        new AgentRunSpec("parent", "task", null, null, null, null, null, null), "default", budget)
                .state();
        registry.cancel(state.id());
        // cancel removes state immediately
        assertTrue(registry.get(state.id()).isEmpty());
        // both limits released
        assertTrue(
                registry.start(new AgentRunSpec("parent", "t2", null, null, null, null, null, null), "default", budget)
                        .admitted());
    }

    @Test
    void cancelByParentSessionReleasesBothLimits() {
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "4")), providerManager(5));
        final var budget = new SubagentBudget(4);
        final var mine = registry.start(
                        new AgentRunSpec("s1", "t1", null, null, null, null, null, null), "default", budget)
                .state();
        final var mineToo = registry.start(
                        new AgentRunSpec("s1", "t2", null, null, null, null, null, null), "default", budget)
                .state();
        final var other = registry.start(
                        new AgentRunSpec("s2", "t3", null, null, null, null, null, null), "default", budget)
                .state();

        assertEquals(2, registry.cancelByParentSession("s1"));
        assertTrue(registry.get(mine.id()).isEmpty(), "a cancelled child is removed");
        assertTrue(registry.get(mineToo.id()).isEmpty(), "a cancelled child is removed");
        assertTrue(registry.get(other.id()).isPresent(), "another session's child is untouched");
        assertEquals(1, registry.cancelByParentSession("s2"));
        assertEquals(0, registry.cancelByParentSession(null), "a blank session id cancels nothing");
        assertEquals(0, registry.cancelByParentSession(""), "a blank session id cancels nothing");
        // provider slots of the cancelled children are free again
        assertTrue(registry.start(new AgentRunSpec("s3", "t4", null, null, null, null, null, null), "default", budget)
                .admitted());
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
    void identityOfReturnsTheRecordedEffectiveIdentity() {
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(4));
        final var state = registry.start("parent", "task").orElseThrow();
        // unknown before the runner resolved the routing
        assertTrue(registry.identityOf(state.id()).isEmpty());
        registry.setTranscriptIdentity(state.id(), "provider-x", "gpt-5.5");
        final var identity = registry.identityOf(state.id());
        assertTrue(identity.isPresent(), "the effective identity must be readable after recording");
        assertEquals("provider-x", identity.get().provider());
        assertEquals("gpt-5.5", identity.get().model());
        // null/blank ids are never a hit
        assertTrue(registry.identityOf(null).isEmpty());
        assertTrue(registry.identityOf("").isEmpty());
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
    void maxConcurrentIsThePerSessionCap() {
        // the per-provider N-1 cap no longer shrinks the registry-wide "maxConcurrent": that one is
        // the per-session yca.subagents.maxConcurrent only.
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "3")), providerManager(2));
        assertEquals(3, registry.maxConcurrent());
    }

    @Test
    void unlimitedProviderMeansNoProviderGate() {
        // provider maxConcurrent <= 0 is unlimited; with the global llm cap also unlimited (-1),
        // only the per-session cap applies, even with many children on that provider.
        final var registry = new SubagentRegistry(config(Map.of("yca.llm.maxConcurrent", "-1")), providerManager(-1));
        for (int i = 0; i < 10; i++) {
            assertTrue(registry.start(
                            new AgentRunSpec("parent", "t" + i, null, null, null, null, null, null), "default", null)
                    .admitted());
        }
    }

    @Test
    void collectReturnsResultEvenAfterStateEvictedByCleanup() {
        // the get -> collect race: once done states exceed maxCompletedStates, cleanup() evicts them
        // from the subagents map, but collect must still return the finished result via the cache.
        // provider=2, subagents=2 -> maxConcurrent=session=2, maxCompletedStates=max(3,6)=6.
        final var registry =
                new SubagentRegistry(config(Map.of("yca.subagents.maxConcurrent", "2")), providerManager(2));
        // complete more subagents than maxCompletedStates (6) so cleanup() LRU-evicts the oldest done
        // state from the map. Each start+complete cycle acquires and releases the permits, so 10 cycles run.
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

    @Test
    void transcriptUsesStoredIdentityThenFallsBackToProcessCurrent() {
        // a $provider/model spawn runs on a provider other than the process-wide current one:
        // the runner stores the effective tuple, the lazily created transcript must use it
        final var labels = new SpeakerLabels(
                List.of(new FormatDatePromptHelper(Clock.systemDefaultZone())), config(Map.of()), null);
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(4), labels);
        final var state = registry.start("parent", "task").orElseThrow();
        registry.setTranscriptIdentity(state.id(), "child-provider", "child-model");
        final var transcript = registry.transcriptOf(state.id());
        assertTrue(transcript.assistantLabel().endsWith("child-provider::child-model"), transcript.assistantLabel());
        // after dropTranscript the identity is gone: a fresh transcript falls back to the process current
        registry.dropTranscript(state.id());
        final var fresh = registry.transcriptOf(state.id());
        assertTrue(fresh.assistantLabel().endsWith("default::model"), fresh.assistantLabel());
    }

    @Test
    void setTranscriptIdentityIgnoresBlankIds() {
        final var labels = new SpeakerLabels(
                List.of(new FormatDatePromptHelper(Clock.systemDefaultZone())), config(Map.of()), null);
        final var registry = new SubagentRegistry(config(Map.of()), providerManager(4), labels);
        registry.setTranscriptIdentity(null, "p", "m");
        registry.setTranscriptIdentity("", "p", "m");
        registry.setTranscriptIdentity("   ", "p", "m");
        // no exception, no entry stored: a real transcript still falls back to the process current
        assertTrue(registry.transcriptOf("any").assistantLabel().endsWith("default::model"));
    }
}
