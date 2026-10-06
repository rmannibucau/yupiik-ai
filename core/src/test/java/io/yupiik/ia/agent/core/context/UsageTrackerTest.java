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
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        assertEquals(32000, tracker.contextWindow());
    }

    @Test
    void perModelContextWindowOverridesProviderAndDetection() {
        final var cfg = configuration(32000); // provider window
        // re-resolve with a per-model override the provider's own model name
        final var entries = new java.util.HashMap<String, String>();
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
        assertEquals(1_000_000, tracker.contextWindow(), "the per-model override must win over the provider value");
        assertEquals("explicit", tracker.windowOrigin(), "a per-model override counts as explicit, never auto-halved");
    }

    @Test
    void perModelOverrideIsNeverHalvedAndPersisted() throws Exception {
        // provider has no window -> detection via /v1/models (empty here) -> baseline; but the
        // per-model override pins it, and rememberShrinkOnOverflow must not persist the halved value
        final var cfg = configuration(-1);
        final var entries = new java.util.HashMap<String, String>();
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
        assertEquals(1_000_000, tracker.contextWindow());
        // an overflow-triggered shrink must not persist (the override is explicit, not auto)
        tracker.rememberShrinkOnOverflow();
        // a fresh tracker resolves the same 1M again, never a halved/cached 500k
        final var fresh = new UsageTracker(pm, client, null, Clock.systemUTC());
        assertEquals(1_000_000, fresh.contextWindow(), "the per-model override must survive overflow auto-halving");
    }

    @Test
    void auxiliarySpendIsCountedGloballyAndSeparately() {
        final var tracker = new UsageTracker(providerManager, null, null, Clock.systemUTC());
        tracker.record(new Usage(100, 20, 120));
        tracker.recordAuxiliary(new Usage(11, 7, 18));
        assertEquals(111, tracker.promptTokens());
        assertEquals(27, tracker.completionTokens());
        assertEquals(2, tracker.requests());
        assertEquals(11, tracker.auxiliaryPromptTokens());
        assertEquals(7, tracker.auxiliaryCompletionTokens());
        assertEquals(1, tracker.auxiliaryRequests());
        tracker.reset();
        assertEquals(0, tracker.promptTokens());
        assertEquals(0, tracker.completionTokens());
        assertEquals(0, tracker.requests());
        assertEquals(0, tracker.auxiliaryPromptTokens());
        assertEquals(0, tracker.auxiliaryCompletionTokens());
        assertEquals(0, tracker.auxiliaryRequests());
    }

    @Test
    void reasoningTokensAreCountedAndReset() {
        final var tracker = new UsageTracker(null, null, null, Clock.systemUTC());
        tracker.record(new Usage(100, 120, 220, 0, 0, 50, 0));
        assertEquals(50, tracker.reasoningTokens(), "reasoning must accumulate regular calls");
        tracker.recordAuxiliary(new Usage(11, 13, 24, 0, 0, 7, 0));
        assertEquals(57, tracker.reasoningTokens(), "auxiliary spend adds to the same reasoning total");
        tracker.reset();
        assertEquals(0, tracker.reasoningTokens());
    }

    @Test
    void detectsFromModelsAndCachesIt() {
        modelsBody =
                "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"llamacpp\",\"meta\":{\"n_ctx\":196608,\"n_ctx_train\":262144}}]}";
        final var tracker = tracker();
        assertEquals(196608, tracker.contextWindow());
        server.stop(0);
        server = null;
        assertEquals(196608, tracker.contextWindow());
        assertEquals(
                Optional.of(196608),
                new ContextWindowCache(configuration, mapper, Clock.systemUTC()).get("default", "test-model"));
    }

    @Test
    void staleCacheTriggersRefetchAndRefresh() {
        final var cache = new ContextWindowCache(configuration, mapper, Clock.systemUTC());
        cache.put("default", "test-model", 4096, System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_window\":8192}]}";
        assertEquals(8192, tracker().contextWindow());
        assertEquals(Optional.of(8192), cache.get("default", "test-model"));
    }

    @Test
    void freshCacheSkipsModelsCall() {
        new ContextWindowCache(configuration, mapper, Clock.systemUTC())
                .put("default", "test-model", 64000, System.currentTimeMillis());
        modelsStatus = 500;
        assertEquals(64000, tracker().contextWindow());
    }

    @Test
    void missingVendorValueFallsBackToAutoBaseline() {
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"x\"}]}";
        assertEquals(ModelsResponse.AUTO_DETECTED_BASELINE, tracker().contextWindow());
    }

    @Test
    void modelsFailureFallsBackToAutoBaseline() {
        modelsStatus = 500;
        assertEquals(ModelsResponse.AUTO_DETECTED_BASELINE, tracker().contextWindow());
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
        tracker.reset();
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
    void contextPercentIsComputedAgainstTheUsableWindow() {
        assertEquals(10.0, calibratedTracker().contextPercent(8_000), 0.001);
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
