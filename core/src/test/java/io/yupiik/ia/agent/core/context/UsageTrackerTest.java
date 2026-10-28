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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class UsageTrackerTest {
    @TempDir
    Path storageHome;

    private HttpServer server;
    private ChatClient client;
    private ProviderManager providerManager;
    private CodingAgentConfiguration configuration;
    private JsonMapper mapper;

    private volatile String modelsBody = "{\"data\":[]}";
    private volatile int modelsStatus = 200;

    @BeforeEach
    void start(final JsonMapper mapper) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, modelsStatus, modelsBody));
        server.start();
        this.mapper = mapper;
        configuration = configuration(-1);
        providerManager = new ProviderManager(configuration, mapper);
        client = new ChatClient(configuration, mapper, providerManager, Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void configuredWindowWins() {
        final var cfg = configuration(32000);
        final var tracker = new UsageTracker(new ProviderManager(cfg, mapper), null, null, Clock.systemUTC());
        assertEquals(32000, tracker.contextWindowTokenCount().value());
    }

    @Test
    void perModelContextWindowOverridesProviderAndDetection() {
        final var cfg = configuration(32000); // provider window
        // re-resolve with a per-model override the provider's own model name
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put(
                "yca.provider.providers.0.baseUrl",
                "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "32000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.provider.providers.0.models.length", "1");
        entries.put("yca.provider.providers.0.models.0.model", "test-model");
        entries.put("yca.provider.providers.0.models.0.contextWindow", "1000000");
        final var cfgOverride = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries);
        final var pm = new ProviderManager(cfgOverride, mapper);
        final var tracker = new UsageTracker(pm, client, null, Clock.systemUTC());
        assertEquals(
                1_000_000,
                tracker.contextWindowTokenCount().value(),
                "the per-model override must win over the provider value");
        assertEquals("explicit", tracker.windowOrigin(), "a per-model override counts as explicit, never auto-halved");
    }

    @Test
    void perModelOverrideIsNeverHalvedAndPersisted() throws Exception {
        // provider has no window -> detection via /v1/models (empty here) -> baseline; but the
        // per-model override pins it, and rememberShrinkOnOverflow must not persist the halved value
        final var cfg = configuration(-1);
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put(
                "yca.provider.providers.0.baseUrl",
                "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.provider.providers.0.models.length", "1");
        entries.put("yca.provider.providers.0.models.0.model", "test-model");
        entries.put("yca.provider.providers.0.models.0.contextWindow", "1000000");
        final var cfgOverride = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries);
        final var pm = new ProviderManager(cfgOverride, mapper);
        final var tracker = new UsageTracker(pm, client, null, Clock.systemUTC());
        assertEquals(1_000_000, tracker.contextWindowTokenCount().value());
        // an overflow-triggered shrink must not persist (the override is explicit, not auto)
        tracker.rememberShrinkOnOverflow();
        // a fresh tracker resolves the same 1M again, never a halved/cached 500k
        final var fresh = new UsageTracker(pm, client, null, Clock.systemUTC());
        assertEquals(
                1_000_000,
                fresh.contextWindowTokenCount().value(),
                "the per-model override must survive overflow auto-halving");
    }

    @Test
    void auxiliarySpendIsCountedGloballyAndSeparately() {
        final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
        tracker.record(new Usage(100, 20, 120));
        tracker.recordAuxiliary(new Usage(11, 7, 18)); // no cache-read
        assertEquals(111, tracker.promptTokens());
        assertEquals(27, tracker.completionTokens());
        assertEquals(2, tracker.requests());
        assertEquals(11, tracker.auxiliaryPromptTokens());
        assertEquals(7, tracker.auxiliaryCompletionTokens());
        assertEquals(0, tracker.auxiliaryCacheReadTokens());
        assertEquals(11, tracker.auxiliaryFreshTokens(), "fresh = prompt - cache-read, all prompt is fresh");
        assertEquals(1, tracker.auxiliaryRequests());
        // reset is not available on base UsageTracker anymore (only on SubagentUsageTracker)
        // use a new instance instead
    }

    @Test
    void auxiliaryFreshTokensSubtractTheAuxCacheRead() {
        final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
        // (prompt, completion, total, cacheRead, cacheWrite, reasoning, ...)
        tracker.recordAuxiliary(new Usage(100, 8, 108, 60, 0, 0, 0));
        assertEquals(100, tracker.auxiliaryPromptTokens());
        assertEquals(60, tracker.auxiliaryCacheReadTokens());
        assertEquals(40, tracker.auxiliaryFreshTokens(), "background fresh excludes the cached prefix");
        assertEquals(100, tracker.promptTokens(), "aux prompt also counts toward the global total");
        assertEquals(60, tracker.cacheReadTokens(), "aux cache-read also counts toward the global total");
    }

    @Test
    void llamaCppRuntimeContextIsRead() {
        modelsBody =
                "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"llamacpp\",\"meta\":{\"n_ctx\":196608,\"n_ctx_train\":262144}}]}";
        final var tracker = tracker();
        assertEquals(196608, tracker.contextWindowTokenCount().value());
        server.stop(0);
        server = null;
        assertEquals(196608, tracker.contextWindowTokenCount().value());
        assertEquals(
                Optional.of(196608),
                new ContextWindowCache(configuration, mapper, Clock.systemUTC())
                        .get("default", "test-model")
                        .map(ModelsResponse.TokenCount::value));
    }

    @Test
    void staleCacheTriggersRefetchAndRefresh() {
        final var cache = new ContextWindowCache(configuration, mapper, Clock.systemUTC());
        cache.put(
                "default",
                "test-model",
                new ModelsResponse.TokenCount(4096, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                null,
                System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_window\":8192}]}";
        assertEquals(8192, tracker().contextWindowTokenCount().value());
        assertEquals(Optional.of(8192), cache.get("default", "test-model").map(ModelsResponse.TokenCount::value));
    }

    @Test
    void freshCacheSkipsModelsCall() {
        new ContextWindowCache(configuration, mapper, Clock.systemUTC())
                .put(
                        "default",
                        "test-model",
                        new ModelsResponse.TokenCount(64000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "test"),
                        null,
                        System.currentTimeMillis());
        modelsStatus = 500;
        assertEquals(64000, tracker().contextWindowTokenCount().value());
    }

    @Test
    void missingVendorValueFallsBackToAutoBaseline() {
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"x\"}]}";
        assertEquals(
                ModelsResponse.AUTO_DETECTED_BASELINE,
                tracker().contextWindowTokenCount().value());
    }

    @Test
    void modelsFailureFallsBackToAutoBaseline() {
        modelsStatus = 500;
        assertEquals(
                ModelsResponse.AUTO_DETECTED_BASELINE,
                tracker().contextWindowTokenCount().value());
    }

    @Test
    void modelsFailureIsCoalescedOnSubsequentResolutions() {
        modelsStatus = 500;
        final var tracker = tracker();
        // first resolution fails -> warning fires, falls back to the conservative baseline
        assertEquals(
                ModelsResponse.AUTO_DETECTED_BASELINE,
                tracker.contextWindowTokenCount().value());
        // the failure is remembered, so a rapid follow-up must not re-hit /v1/models: even though the
        // endpoint recovered, the second resolution stays on the baseline instead of re-detecting
        modelsStatus = 200;
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_window\":8192}]}";
        assertEquals(
                ModelsResponse.AUTO_DETECTED_BASELINE,
                tracker.contextWindowTokenCount().value(),
                "a recent auto-detection failure must be coalesced, not re-attempted every resolution");
    }

    @Test
    void effectiveContextFallsBackToRawEstimateWithoutAnchor() {
        final var tracker = calibratedTracker();
        final var messages = List.of(Message.user("hello world"));
        assertEquals(tracker.estimateContext(messages, 0), tracker.effectiveContext(messages, 0));
    }

    @Test
    void anchorCalibratesTheAppendedEstimateWithRealUsage() {
        final var tracker = calibratedTracker();
        tracker.anchor(500, 100);
        final var messages = List.of(Message.user("a".repeat(400)));
        final var raw = tracker.estimateContext(messages, 0);
        // ratio 500/100 = 5 clamped to 2.0
        assertEquals((int) Math.round(500 + 2.0 * (raw - 100)), tracker.effectiveContext(messages, 0));
    }

    @Test
    void anchorRatioIsClampedFromBelowToo() {
        final var tracker = calibratedTracker();
        tracker.anchor(25, 100);
        final var messages = List.of(Message.user("a".repeat(400)));
        final var raw = tracker.estimateContext(messages, 0);
        // ratio 25/100 = 0.25 clamped to 0.5
        assertEquals((int) Math.round(25 + 0.5 * (raw - 100)), tracker.effectiveContext(messages, 0));
    }

    @Test
    void shrunkContextFallsBackToTheRawEstimate() {
        final var tracker = calibratedTracker();
        tracker.anchor(500, 100);
        final var messages = List.of(Message.user("hi"));
        assertEquals(tracker.estimateContext(messages, 0), tracker.effectiveContext(messages, 0));
    }

    @Test
    void nonPositiveAnchorIsIgnoredAndResetClearsIt() {
        final var tracker = calibratedTracker();
        final var messages = List.of(Message.user("a".repeat(400)));
        tracker.anchor(0, 100);
        assertEquals(tracker.estimateContext(messages, 0), tracker.effectiveContext(messages, 0));
        tracker.anchor(500, 100);
        tracker.clearAnchor();
        assertEquals(tracker.estimateContext(messages, 0), tracker.effectiveContext(messages, 0));
    }

    @Test
    void usableWindowReservesCompletionHeadroom() {
        assertEquals(80_000, calibratedTracker().usableWindow());
        final var small =
                new UsageTracker(new ProviderManager(configuration(4_096), mapper), null, null, Clock.systemUTC());
        assertEquals(3_072, small.usableWindow());
    }

    @Test
    void topProviderOutputCapIsReservedFromTheDetectedWindow() {
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"top_provider\":{\"is_moderated\":false,"
                + "\"context_length\":262144,\"max_completion_tokens\":32768}}]}";
        final var tracker = tracker();
        assertEquals(262_144, tracker.contextWindowTokenCount().value());
        assertEquals(32_768, tracker.declaredOutputTokensTokenCount().value());
        assertEquals(229_376, tracker.usableWindow());
    }

    @Test
    void usableWindowForModelUsesTheReferencedModelWindow() {
        // a session on the 'main' provider: explicit main window 128k, no output cap configured
        final var tracker = new UsageTracker(multiWindowProviderManager(), null, null, Clock.systemUTC());
        // the session's own usable window is the main provider's 128k (minus the 20k fallback reserve)
        assertEquals(108_000, tracker.usableWindow());
        // a background compact reference routed to the 'cheap' provider with a smaller window is
        // sized against that smaller window: 32k - min(20k, 32k/4)=8k -> 24k usable
        assertEquals(24_000, tracker.usableWindowForModel("$cheap/compact-model"));
    }

    @Test
    void usableWindowForModelBlankFallsBackToSessionWindow() {
        final var tracker = new UsageTracker(multiWindowProviderManager(), null, null, Clock.systemUTC());
        assertEquals(
                tracker.usableWindow(),
                tracker.usableWindowForModel(null),
                "a blank/null compact reference must not change the window (summarizer on the conversation model)");
        assertEquals(
                tracker.usableWindow(),
                tracker.usableWindowForModel("  "),
                "a blank compact reference must fall back to the conversation model window");
    }

    @Test
    void usableWindowForModelWithoutAnyEstablishedWindowFallsBackToConservative() {
        // a provider configured with NO context window and NO /v1/models detection (client is null):
        // no window can be established, so the caller must get the conservative MIN, never a phantom
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "nodata");
        entries.put("yca.provider.providers.0.baseUrl", "https://nodata.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "ghost");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        final var tracker = new UsageTracker(
                new ProviderManager(io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries), mapper),
                null,
                null,
                Clock.systemUTC());
        assertEquals(1_024, tracker.usableWindowForModel("$nodata/ghost"));
    }

    private ProviderManager multiWindowProviderManager() {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "2");
        entries.put("yca.provider.providers.0.id", "main");
        entries.put("yca.provider.providers.0.baseUrl", "https://main.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "main-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.provider.providers.1.id", "cheap");
        entries.put("yca.provider.providers.1.baseUrl", "https://cheap.example/v1/chat/completions");
        entries.put("yca.provider.providers.1.apiKey", "");
        entries.put("yca.provider.providers.1.model", "compact-model");
        entries.put("yca.provider.providers.1.api", "auto");
        entries.put("yca.provider.providers.1.contextWindow", "32000");
        entries.put("yca.provider.providers.1.timeoutSeconds", "120");
        return new ProviderManager(io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries), mapper);
    }

    @Test
    void equalLiteLLMCapsAreNotATrustedPartition() {
        // the user's /v1/models payload: max_input_tokens == max_output_tokens per model
        modelsBody = """
                {"data":[
                 {"id":"qwen3.8-27b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"gemma4-26b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072},
                 {"id":"qwen3-8b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":40960,"max_output_tokens":40960}
                ],"object":"list"}""";
        // the equal caps are per-side limits, not a partition: the input limit is taken directly
        assertLiteLLMUsable("qwen3.8-27b", 131_072, 131_072);
        assertLiteLLMUsable("gemma4-26b", 131_072, 131_072);
        assertLiteLLMUsable("qwen3-8b", 40_960, 40_960);
    }

    private void assertLiteLLMUsable(final String model, final int window, final int usable) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put(
                "yca.provider.providers.0.baseUrl",
                "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", model);
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        final var cfg = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries);
        final var pm = new ProviderManager(cfg, mapper);
        final var c = new ChatClient(cfg, mapper, pm, Clock.systemUTC());
        try {
            final var tracker =
                    new UsageTracker(pm, c, new ContextWindowCache(cfg, mapper, Clock.systemUTC()), Clock.systemUTC());
            assertEquals(window, tracker.contextWindowTokenCount().value());
            assertEquals(window, tracker.declaredOutputTokensTokenCount().value());
            assertEquals(usable, tracker.usableWindow());
        } finally {
            c.close();
        }
    }

    @Test
    void configuredOutputTokensIsReservedFromAConfiguredWindow() {
        final var cfg = configuration(200_000, 8_000);
        final var tracker = new UsageTracker(new ProviderManager(cfg, mapper), null, null, Clock.systemUTC());
        assertEquals(8_000, tracker.declaredOutputTokensTokenCount().value());
        assertEquals(192_000, tracker.usableWindow());
    }

    @Test
    void configuredOutputTokensWinsOverTheDetectedCap() {
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_length\":128000,\"max_completion_tokens\":32768}]}";
        final var cfg = configuration(-1, 4_000);
        final var tracker = new UsageTracker(
                new ProviderManager(cfg, mapper),
                client,
                new ContextWindowCache(cfg, mapper, Clock.systemUTC()),
                Clock.systemUTC());
        assertEquals(128_000, tracker.contextWindowTokenCount().value());
        assertEquals(4_000, tracker.declaredOutputTokensTokenCount().value());
        assertEquals(124_000, tracker.usableWindow());
    }

    @Test
    void outputCapNotSmallerThanTheWindowFallsBackToTheHeuristic() {
        final var cfg = configuration(4_096, 8_192);
        final var tracker = new UsageTracker(new ProviderManager(cfg, mapper), null, null, Clock.systemUTC());
        assertEquals(8_192, tracker.declaredOutputTokensTokenCount().value());
        assertEquals(3_072, tracker.usableWindow(), "a cap larger than the window cannot be honoured");
    }

    @Test
    void contextPercentIsComputedAgainstTheUsableWindow() {
        assertEquals(10.0, calibratedTracker().contextPercent(8_000), 0.001);
    }

    @Test
    void authFailureDoesNotSuppressDetectionAfterRecovery() throws Exception {
        final var calls = new AtomicInteger();
        final var recovered = new AtomicBoolean();
        // adjustable clock: an auth failure only anti-spams for 60s, so the recovery is observed by
        // advancing the clock past the retry window (as it elapses in real sessions between turns)
        final var now = new AtomicLong(System.currentTimeMillis());
        final Clock clock = new Clock() {
            @Override
            public long millis() {
                return now.get();
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }
        };
        final var client = new ChatClient(configuration(-1), mapper, providerManager, Clock.systemUTC()) {
            @Override
            public ModelsResponse models(final ChatSettings s) {
                if (!recovered.get()) {
                    calls.incrementAndGet();
                    throw new CodexNotAuthenticatedException("codex-yupiik");
                }
                return toModels("{\"data\":[{\"id\":\"test-model\",\"context_window\":272000}]}");
            }
        };
        try {
            final var tracker = new UsageTracker(
                    providerManager, client, new ContextWindowCache(configuration, mapper, Clock.systemUTC()), clock);
            // first resolution: auth failure -> conservative baseline, no permanent backoff
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker.contextWindowTokenCount().value());
            assertEquals("auto", tracker.windowOrigin());
            // auth recovers (user ran /codex login) and the short anti-spam window elapses: the next
            // resolution must retry and succeed, never stay pinned to the baseline for a day
            recovered.set(true);
            now.addAndGet(61_000);
            assertEquals(
                    272_000,
                    tracker.contextWindowTokenCount().value(),
                    "an auth failure must not arm the 24h backoff: detection retries after login");
            assertEquals("explicit", tracker.windowOrigin());
        } finally {
            client.close();
        }
    }

    @Test
    void permanentFailureStillBacksOffForADay() throws Exception {
        final var calls = new AtomicInteger();
        final var client = new ChatClient(configuration(-1), mapper, providerManager, Clock.systemUTC()) {
            @Override
            public ModelsResponse models(final ChatSettings s) {
                calls.incrementAndGet();
                throw new IllegalStateException("connection refused");
            }
        };
        try {
            final var tracker = new UsageTracker(
                    providerManager,
                    client,
                    new ContextWindowCache(configuration, mapper, Clock.systemUTC()),
                    Clock.systemUTC());
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker.contextWindowTokenCount().value());
            assertEquals(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    tracker.contextWindowTokenCount().value());
            assertEquals(
                    1,
                    calls.get(),
                    "a permanent detection failure must not re-attempt on every resolution (24h backoff)");
        } finally {
            client.close();
        }
    }

    @Test
    void staleCacheIsUsedWhenDetectionFails() throws Exception {
        final var cfg = configuration(-1);
        final var cache = new ContextWindowCache(cfg, mapper, Clock.systemUTC());
        // seed an entry already out of TTL at resolution time
        cache.put(
                "default",
                "test-model",
                new ModelsResponse.TokenCount(
                        272000, true, ModelsResponse.SourceType.DETECTED_ABSOLUTE, "/context_window"),
                System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
        final var client = new ChatClient(cfg, mapper, providerManager, Clock.systemUTC()) {
            @Override
            public ModelsResponse models(final ChatSettings s) {
                throw new IllegalStateException("provider unreachable");
            }
        };
        try {
            final var tracker = new UsageTracker(providerManager, client, cache, Clock.systemUTC());
            assertEquals(
                    272_000,
                    tracker.contextWindowTokenCount().value(),
                    "a stale (expired) cache entry must beat the conservative baseline when detection fails");
        } finally {
            client.close();
        }
    }

    @Test
    void detectionUsesTheSessionProviderSettings() throws Exception {
        final var seen = new AtomicReference<ChatSettings>();
        final var cfg = configuration(-1);
        final var marker = new ChatSettings("default", "test-model", null, null);
        final var client = new ChatClient(cfg, mapper, providerManager, Clock.systemUTC()) {
            @Override
            public ModelsResponse models(final ChatSettings s) {
                seen.set(s);
                return toModels("{\"data\":[{\"id\":\"test-model\",\"context_window\":500000}]}");
            }
        };
        try {
            final var tracker = new UsageTracker(
                    providerManager, client, new ContextWindowCache(cfg, mapper, Clock.systemUTC()), Clock.systemUTC());
            tracker.attach(marker);
            assertEquals(500_000, tracker.contextWindowTokenCount().value());
            assertEquals(
                    marker,
                    seen.get(),
                    "detection must resolve the models list against the session's provider settings");
        } finally {
            client.close();
        }
    }

    private ModelsResponse toModels(final String body) {
        return mapper.fromString(ModelsResponse.class, body);
    }

    private UsageTracker calibratedTracker() {
        return new UsageTracker(new ProviderManager(configuration(100_000), mapper), null, null, Clock.systemUTC());
    }

    private UsageTracker tracker() {
        return new UsageTracker(
                providerManager,
                client,
                new ContextWindowCache(configuration, mapper, Clock.systemUTC()),
                Clock.systemUTC());
    }

    private CodingAgentConfiguration configuration(final int contextWindow) {
        return configuration(contextWindow, -1);
    }

    private CodingAgentConfiguration configuration(final int contextWindow, final int outputTokens) {
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry(
                        "yca.provider.providers.0.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions"),
                Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                Map.entry("yca.provider.providers.0.model", "test-model"),
                Map.entry("yca.provider.providers.0.api", "auto"),
                Map.entry("yca.provider.providers.0.contextWindow", String.valueOf(contextWindow)),
                Map.entry("yca.provider.providers.0.outputTokens", String.valueOf(outputTokens)),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "5"),
                Map.entry("yca.llm.retriesMaxAttempts", "1"),
                Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                Map.entry("yca.llm.maxConcurrent", "1"),
                Map.entry("yca.llm.retryOnContextOverflow", "true"),
                Map.entry("yca.llm.maxIterations", "250")));
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) {
        try {
            exchange.getRequestBody().readAllBytes();
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
