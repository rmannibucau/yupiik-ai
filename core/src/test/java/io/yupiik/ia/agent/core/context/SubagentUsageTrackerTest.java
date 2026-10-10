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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.llm.Usage;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SubagentUsageTrackerTest {
    @Test
    void toksAreKeptOffTheParentUntilMerged() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var subagent = new SubagentUsageTracker(parent, "sub-1");

        assertEquals("sub-1", subagent.subagentId());
        subagent.record(new Usage(50, 30, 80, 10, 0, 12, 0));
        subagent.record(new Usage(20, 20, 40, 5, 0, 3, 0));

        assertEquals(70, subagent.promptTokens());
        assertEquals(50, subagent.completionTokens());
        assertEquals(15, subagent.reasoningTokens());
        // before merge nothing reaches the parent
        assertEquals(0, parent.promptTokens());
        assertEquals(0, parent.auxiliaryPromptTokens());
        assertEquals(0, parent.reasoningTokens());
    }

    @Test
    void mergeRollsSubagentUsageIntoParentAuxiliary() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var subagent = new SubagentUsageTracker(parent, "sub-1");
        subagent.record(new Usage(50, 30, 80, 10, 0, 12, 0));

        subagent.mergeToParent();

        assertEquals(50, parent.auxiliaryPromptTokens());
        assertEquals(30, parent.auxiliaryCompletionTokens());
        assertEquals(10, parent.cacheReadTokens());
        assertEquals(1, parent.auxiliaryRequests());
        assertEquals(1, parent.requests());
        assertEquals(50, parent.promptTokens());
        assertEquals(12, parent.reasoningTokens(), "the merged usage must carry the reasoning tokens");
    }

    @Test
    void mergeIsCumulativeAcrossSubagents() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var a = new SubagentUsageTracker(parent, "a");
        final var b = new SubagentUsageTracker(parent, "b");
        a.record(new Usage(10, 10, 20, 0, 0));
        b.record(new Usage(20, 20, 40, 0, 0));
        a.mergeToParent();
        b.mergeToParent();
        assertEquals(30, parent.auxiliaryPromptTokens());
        assertEquals(30, parent.auxiliaryCompletionTokens());
    }

    @Test
    void concurrentMergesAreSafeAndComplete() throws Exception {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var tracker = new SubagentUsageTracker(parent, "sub-concurrent");
        final var threads = 8;
        final var executor = Executors.newFixedThreadPool(threads);
        final var done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        tracker.record(new Usage(5, 5, 10, 0, 0));
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(threads * 5L, tracker.promptTokens());
            assertEquals(threads * 5L, tracker.completionTokens());
            tracker.mergeToParent();
            assertEquals(threads * 5L, parent.auxiliaryPromptTokens());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void optimizationCountersAccumulateOnTheSubagentAndMergeIntoTheParent() {
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var subagent = new SubagentUsageTracker(parent, "sub-opt");

        // records land on the subagent, not the parent, before the merge
        subagent.recordOptimization(10_000, 1_000);
        subagent.recordOptimization(20_000, 2_000);
        assertEquals(2, subagent.optimizedOutputs());
        assertEquals(30_000, subagent.optimizedCharsBefore());
        assertEquals(3_000, subagent.optimizedCharsAfter());
        assertEquals(27_000, subagent.optimizedCharsSaved());
        assertEquals(0, parent.optimizedOutputs(), "before the merge the parent must not be touched");

        subagent.mergeToParent();

        assertEquals(2, parent.optimizedOutputs());
        assertEquals(30_000, parent.optimizedCharsBefore());
        assertEquals(3_000, parent.optimizedCharsAfter());
        assertEquals(27_000, parent.optimizedCharsSaved());
    }
}
