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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.Usage;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class MetricsFormatTest {
    @Test
    void measuredRateOnly() {
        assertEquals("42.5 tok/s", MetricsFormat.tokPerSec(new AgentEvent.Throughput(85, 2_000, 0)));
    }

    @Test
    void measuredRateNextToTheServerView() {
        assertEquals(
                "42.5 tok/s (real 40.0 tok/s)", MetricsFormat.tokPerSec(new AgentEvent.Throughput(85, 2_000, 2_125)));
    }

    @Test
    void serverViewAloneWhenNothingWasMeasured() {
        assertEquals("real 40.0 tok/s", MetricsFormat.tokPerSec(new AgentEvent.Throughput(85, 0, 2_125)));
    }

    @Test
    void nothingMeasurableYieldsNoSegment() {
        assertEquals("", MetricsFormat.tokPerSec(AgentEvent.Throughput.EMPTY));
        assertEquals("", MetricsFormat.tokPerSec(new AgentEvent.Throughput(0, 1_000, 0)), "no output token");
        assertEquals("", MetricsFormat.tokPerSec(new AgentEvent.Throughput(100, 0, 0)), "no time known");
        assertEquals("", MetricsFormat.tokPerSec(null));
    }

    @Test
    void reasoningBreakdownIsHiddenInCompactMode() {
        final var transcript = transcriptWithReasoningTurn();
        final var usage = usageWithReasoning();
        final var report = MetricsFormat.report(
                new MetricsConfiguration(true, true, MetricsDisplayMode.COMPACT), transcript, usage);
        final var lines = report.split("\n");
        assertEquals(2, lines.length, "the report contract stays exactly two lines: " + report);
        assertEquals("metrics: showPerTurn=true showSessionTotals=true displayMode=COMPACT", lines[0]);
        assertFalse(lines[1].contains("reasoning"), "compact must not surface the reasoning drill-down: " + lines[1]);
        assertTrue(lines[1].contains("2 LLM (1.2s think)"), lines[1]);
        assertTrue(lines[1].contains("3 tools (600ms/700ms)"), lines[1]);
        assertTrue(lines[1].contains("session: 1 LLM calls"), lines[1]);
    }

    @Test
    void reasoningBreakdownIsSurfacedInFullMode() {
        final var transcript = transcriptWithReasoningTurn();
        final var usage = usageWithReasoning();
        final var report =
                MetricsFormat.report(new MetricsConfiguration(true, true, MetricsDisplayMode.FULL), transcript, usage);
        final var lines = report.split("\n");
        assertEquals(2, lines.length, "the report contract stays exactly two lines: " + report);
        assertEquals(
                "2 LLM (1.2s think) · out 953 tok · reasoning 251 tok · 3 tools (600ms/700ms)"
                        + " · 602.0 tok/s (real 566.6 tok/s) · reasoning 125.5 tok/s · 12.3s"
                        + " · session: 1 LLM calls · reasoning 500 tok",
                lines[1]);
    }

    @Test
    void fullModeKeepsTheCompactShapeWithoutReasoning() {
        final var transcript = new ConversationTranscript();
        transcript.onEvent(new AgentEvent.TurnMetrics(
                1, 0, 300, 400, 2, 5_000, new AgentEvent.Throughput(120, 0, 1_000, 0), "s1", null, null));
        final var report =
                MetricsFormat.report(new MetricsConfiguration(true, true, MetricsDisplayMode.FULL), transcript, null);
        assertEquals(
                "metrics: showPerTurn=true showSessionTotals=true displayMode=FULL\n"
                        + "1 LLM · 2 tools (300ms/400ms) · 120.0 tok/s · 5s",
                report);
    }

    @Test
    void cachedTokensHelperFormatsTheShareAndPercent() {
        assertEquals("", MetricsFormat.cachedTokens(0, 4_000), "no cache-read -> empty");
        assertEquals("", MetricsFormat.cachedTokens(1_500, 0), "no prompt -> empty");
        assertEquals("cached 1.5k (38%)", MetricsFormat.cachedTokens(1_500, 4_000));
        assertEquals("cached 1.2k (30%)", MetricsFormat.cachedTokens(1_200, 4_000));
        assertEquals("cached 10k (100%)", MetricsFormat.cachedTokens(10_000, 10_000));
        assertEquals("cached 1.5M (75%)", MetricsFormat.cachedTokens(1_500_000, 2_000_000));
    }

    @Test
    void fullModeSurfacesCachedTokensInTheTurnLine() {
        final var transcript = transcriptWithReasoningTurn();
        transcript.onEvent(new AgentEvent.UsageUpdated(
                new Usage(4_000, 953, 4_953, 1_200, 0),
                42_000,
                55.0,
                4_000,
                1.05,
                "m",
                "s1",
                null,
                null,
                AgentEvent.UsageUpdated.Quota.NONE));
        final var report =
                MetricsFormat.report(new MetricsConfiguration(true, true, MetricsDisplayMode.FULL), transcript, null);
        final var lines = report.split("\n");
        assertEquals(2, lines.length, "the report contract stays exactly two lines: " + report);
        assertTrue(lines[1].contains("cached 1.2k (30%)"), lines[1]);
    }

    @Test
    void compactModeDoesNotAddCachedWhenNoCacheData() {
        final var transcript = transcriptWithReasoningTurn();
        final var report = MetricsFormat.report(
                new MetricsConfiguration(true, true, MetricsDisplayMode.COMPACT), transcript, null);
        assertFalse(report.split("\n")[1].contains("cached"), "no cache usage -> no cached segment: " + report);
    }

    @Test
    void cacheShareUsesFullInputForCacheExclusiveProviders() {
        final var transcript = transcriptWithReasoningTurn();
        transcript.onEvent(new AgentEvent.UsageUpdated(new Usage(5, 2, 7, 100, 20, 0, 0, 125), 68, 14, "s1"));
        final var metrics = new MetricsConfiguration(true, true, MetricsDisplayMode.FULL);
        assertTrue(MetricsFormat.report(metrics, transcript, null).contains("cached 100 (80%)"));
        transcript.onEvent(new AgentEvent.UsageUpdated(new Usage(0, 2, 2, 100, 0, 0, 0, 100), 68, 14, "s1"));
        assertTrue(MetricsFormat.report(metrics, transcript, null).contains("cached 100 (100%)"));
    }

    @Test
    void tokensHelperFormatsCompactHumanCounts() {
        assertEquals("0", MetricsFormat.tokens(0));
        assertEquals("999", MetricsFormat.tokens(999));
        assertEquals("1.2k", MetricsFormat.tokens(1_200));
        assertEquals("14k", MetricsFormat.tokens(13_700));
        assertEquals("1.2M", MetricsFormat.tokens(1_200_000));
        assertEquals("10M", MetricsFormat.tokens(10_400_000));
    }

    @Test
    void shrinkFormatsTheBeforeAfterReclaimSegment() {
        assertEquals("513k -> 87k (83%)", MetricsFormat.shrink(513_000, 87_000));
        assertEquals("100 -> 90 (10%)", MetricsFormat.shrink(100, 90));
        assertEquals("1.2M -> 512k (57%)", MetricsFormat.shrink(1_200_000, 512_000));
        assertEquals("200 -> 100 (50%)", MetricsFormat.shrink(200, 100));
    }

    @Test
    void shrinkIsEmptyWhenThereIsNothingMeaningfulToShow() {
        assertEquals("", MetricsFormat.shrink(0, 0), "no before");
        assertEquals("", MetricsFormat.shrink(-1, 100), "negative before");
        assertEquals("", MetricsFormat.shrink(100, -1), "negative after");
        assertEquals("", MetricsFormat.shrink(100, 100), "no reclaim");
        assertEquals("", MetricsFormat.shrink(100, 200), "grew");
    }

    private static ConversationTranscript transcriptWithReasoningTurn() {
        final var transcript = new ConversationTranscript();
        transcript.onEvent(new AgentEvent.TurnMetrics(
                2, 1_234, 600, 700, 3, 12_300, new AgentEvent.Throughput(1_204, 251, 2_000, 2_125), "s1", null, null));
        return transcript;
    }

    private static UsageTracker usageWithReasoning() {
        final var usage = new UsageTracker(null, null, null, Clock.systemUTC());
        usage.record(new Usage(5_000, 3_000, 8_000, 0, 0, 500, 0));
        return usage;
    }
}
