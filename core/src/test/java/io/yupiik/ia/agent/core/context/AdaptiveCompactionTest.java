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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.ContextWindowExceededException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Behavior of the adaptive compaction chunking ({@code yca.compaction.summarizer*}): the chunk cap
 * derives from the compact model's usable window (floored at the legacy 12k), a size-caused
 * summarizer failure halves the cap and retries, non-size failures never halve, and both failure
 * paths degrade honestly to local truncation instead of looping.
 */
@ExtendWith(TestJsonMapper.class)
class AdaptiveCompactionTest {

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private static CodingAgentConfiguration config(final int window, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "m");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", String.valueOf(window));
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.compaction.tailBudgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        entries.putAll(extra);
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries);
    }

    private ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, mapper);
    }

    private CompactionCoordinator coordinator(final CodingAgentConfiguration cfg, final ChatClient chat) {
        return new CompactionCoordinator(
                cfg,
                chat,
                new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                providerManager(cfg),
                null,
                "s1");
    }

    /** A history whose old part is heavy and the retained tail is tiny: compaction must summarize the old part. */
    private static List<Message> oldHeavyHistory(final int oldSteps, final int charsPerStep) {
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < oldSteps; i++) {
            messages.add(Message.user("step " + i + " " + "x".repeat(charsPerStep)));
        }
        messages.add(Message.user("tail"));
        return messages;
    }

    private static String promptOf(final ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(m -> String.valueOf(m.content()))
                .findFirst()
                .orElse("");
    }

    // --- derivation -------------------------------------------------------

    @Test
    void derivationRespectsTheConfiguredFloor() {
        // 16k window -> usable ~12k tokens -> ~48k derived chars, floored at the configured 12k
        final var cfg = config(16_000, Map.of());
        final var coordinator = coordinator(cfg, null);
        assertEquals(12_000, coordinator.effectiveChunkCap(), "the floor must clamp the derived cap");
        assertEquals(6_000, coordinator.effectiveDigestCap(), "digest auto = half the chunk cap");
        assertEquals(2_000, coordinator.effectiveToolOutputChars());
        assertEquals(500, coordinator.effectiveSpliceChars());
    }

    @Test
    void explicitChunkCharsWinsOverTheDerivation() {
        final var cfg = config(128_000, Map.of("yca.compaction.summarizerChunkChars", "5000"));
        final var coordinator = coordinator(cfg, null);
        assertEquals(5_000, coordinator.effectiveChunkCap(), "an explicit cap must win over the window derivation");
        assertEquals(2_500, coordinator.effectiveDigestCap(), "digest auto = half the explicit cap");
    }

    @Test
    void nullConfigFallsBackToDefaults() {
        final var coordinator = new CompactionCoordinator(null, null, null, null, null, "s1");
        assertEquals(12_000, coordinator.effectiveChunkCap(), "null config must use the legacy floor");
        assertEquals(6_000, coordinator.effectiveDigestCap());
        assertEquals(2_000, coordinator.effectiveToolOutputChars());
        assertEquals(500, coordinator.effectiveSpliceChars());
    }

    // --- chunk flattening --------------------------------------------------

    @Test
    void chunkCompactHonorsTheEffectiveCap() {
        final var cfg = config(128_000, Map.of("yca.compaction.summarizerChunkChars", "100"));
        final var coordinator = coordinator(cfg, null);
        final var messages = new ArrayList<Message>();
        for (var i = 0; i < 10; i++) {
            messages.add(Message.user("step " + i + " " + "x".repeat(50)));
        }
        final var chunks = coordinator.chunkCompact(messages, coordinator.effectiveChunkCap());
        assertTrue(chunks.size() >= 2, "a 10x50 history must be split with a 100-char cap");
        for (final var chunk : chunks) {
            assertTrue(
                    chunk.length() <= 100 + 60,
                    "each chunk must respect the cap (a single oversized line can exceed it)");
        }
        // a single oversized line is its own chunk
        final var one = new ArrayList<Message>();
        one.add(Message.user("x".repeat(500)));
        final var single = coordinator.chunkCompact(one, 100);
        assertEquals(1, single.size());
        assertTrue(single.get(0).length() > 100, "an oversized single message stays one chunk");
    }

    // --- halving escalation -------------------------------------------------

    @Test
    void sizeCausedFailureHalvesTheCapAndRetriesToSuccess() {
        final var calls = new ArrayList<Integer>();
        final var cfg = config(
                128_000,
                Map.of(
                        "yca.compaction.summarizerChunkChars", "4000",
                        "yca.compaction.summarizerMinChunkChars", "1000"));
        final var chat = new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final java.util.function.Consumer<io.yupiik.ia.agent.core.llm.PauseEvent> listener,
                    final BooleanSupplier cancelled)
                    throws IOException {
                calls.add(promptOf(request).length());
                if (calls.size() == 1) {
                    // the first attempt is rejected as too large: the cap must be halved and retried
                    throw new ContextWindowExceededException();
                }
                return new ChatResult(Message.assistant("digest: recovered", null), Usage.EMPTY);
            }
        };
        final var manager = new ContextManager(
                cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
        final var result = manager.compact(oldHeavyHistory(60, 200), true);
        assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
        assertTrue(calls.size() >= 2, "one overflow + a retry with the halved cap: " + calls.size() + " calls");
        final var first = calls.get(0);
        assertTrue(
                calls.get(1) < first,
                "the first retried chunk must be smaller than the overflowing one (first=" + first + ", retry="
                        + calls.get(1) + ")");
    }

    @Test
    void halvingStopsAtTheFloorAndDegradesToTruncation() {
        final var cfg = config(
                128_000,
                Map.of(
                        "yca.compaction.summarizerChunkChars", "4000",
                        "yca.compaction.summarizerMinChunkChars", "1000"));
        final var chat = new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final java.util.function.Consumer<io.yupiik.ia.agent.core.llm.PauseEvent> listener,
                    final BooleanSupplier cancelled)
                    throws IOException {
                // always rejected as too large: halving must hit the floor and stop
                throw new ContextWindowExceededException();
            }
        };
        final var manager = new ContextManager(
                cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
        final var result = manager.compact(oldHeavyHistory(6, 4_000), true);
        assertEquals(CompactionResult.Mode.TRUNCATE, result.mode());
        assertTrue(
                result.error() != null && result.error().contains("context window"),
                "the truncation fallback must report the size cause");
    }

    @Test
    void nonSizeFailureNeverHalvesAndUsesRetriesThenTruncates() {
        final var attempts = new java.util.concurrent.atomic.AtomicInteger();
        final var cfg = config(
                128_000,
                Map.of(
                        "yca.compaction.summarizerChunkChars", "4000",
                        "yca.llm.retriesMaxAttempts", "2"));
        final var chat = new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final java.util.function.Consumer<io.yupiik.ia.agent.core.llm.PauseEvent> listener,
                    final BooleanSupplier cancelled)
                    throws IOException {
                attempts.incrementAndGet();
                throw new IOException("upstream 500");
            }
        };
        final var manager = new ContextManager(
                cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
        final var result = manager.compact(oldHeavyHistory(6, 4_000), true);
        assertEquals(CompactionResult.Mode.TRUNCATE, result.mode());
        assertEquals(2, attempts.get(), "non-size failures use the configured retries, no halving");
        assertTrue(
                result.error() != null && result.error().contains("upstream 500"),
                "the truncation fallback must report the real cause");
    }

    @Test
    void singleCallSucceedsByDefaultOnALargeWindow() {
        final var calls = new java.util.concurrent.atomic.AtomicInteger();
        final var cfg = config(128_000, Map.of());
        final var chat = new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final java.util.function.Consumer<io.yupiik.ia.agent.core.llm.PauseEvent> listener,
                    final BooleanSupplier cancelled) {
                calls.incrementAndGet();
                return new ChatResult(Message.assistant("digest: single", null), Usage.EMPTY);
            }
        };
        final var manager = new ContextManager(
                cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
        final var result = manager.compact(oldHeavyHistory(6, 4_000), true);
        assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
        assertEquals(1, calls.get(), "a large window must compact in a single summarizer call by default");
    }

    @Test
    void noLoopOnPersistentSizeFailures() {
        final var attempts = new java.util.concurrent.atomic.AtomicInteger();
        final var cfg = config(
                128_000,
                Map.of(
                        "yca.compaction.summarizerChunkChars", "4000",
                        "yca.compaction.summarizerMinChunkChars", "1000"));
        final var chat = new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final java.util.function.Consumer<io.yupiik.ia.agent.core.llm.PauseEvent> listener,
                    final BooleanSupplier cancelled)
                    throws IOException {
                attempts.incrementAndGet();
                throw new ContextWindowExceededException();
            }
        };
        final var manager = new ContextManager(
                cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()), coordinator(cfg, chat));
        final var result = manager.compact(oldHeavyHistory(6, 4_000), true);
        assertEquals(CompactionResult.Mode.TRUNCATE, result.mode());
        // 4000 -> 2000 -> 1000 (floor): 3 attempts, then stop. Never 4+.
        assertTrue(
                attempts.get() <= 3 && attempts.get() >= 2,
                "halving must be bounded by log2(initial/floor): was " + attempts.get());
    }
}
