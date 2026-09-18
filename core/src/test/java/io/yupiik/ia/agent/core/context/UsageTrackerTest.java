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
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.ModelsResponse$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$Model$FusionJsonCodec;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
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
import org.junit.jupiter.api.io.TempDir;

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
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, modelsStatus, modelsBody));
        server.start();
        mapper = new JsonMapperImpl(
                List.of(
                        new ModelsResponse$FusionJsonCodec(),
                        new ModelsResponse$Model$FusionJsonCodec(),
                        new ContextWindowCache$Entry$FusionJsonCodec()),
                key -> Optional.empty());
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
                        "http://localhost:" + server.getAddress().getPort() + "/v1"),
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
