package io.yupiik.ia.agent.core.context;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.ModelsResponse$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$Model$FusionJsonCodec;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        mapper = new JsonMapperImpl(List.of(
                new ModelsResponse$FusionJsonCodec(),
                new ModelsResponse$Model$FusionJsonCodec(),
                new ContextWindowCache$Entry$FusionJsonCodec()), key -> Optional.empty());
        configuration = configuration(-1);
        providerManager = new ProviderManager(configuration, mapper);
        client = new ChatClient(configuration, mapper, providerManager);
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
        final var tracker = new UsageTracker(new ProviderManager(cfg, mapper), null, null);
        assertEquals(32000, tracker.contextWindow());
    }

    @Test
    void detectsFromModelsAndCachesIt() {
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"llamacpp\",\"meta\":{\"n_ctx\":196608,\"n_ctx_train\":262144}}]}";
        final var tracker = tracker();
        assertEquals(196608, tracker.contextWindow());
        server.stop(0);
        server = null;
        assertEquals(196608, tracker.contextWindow());
        assertEquals(Optional.of(196608), new ContextWindowCache(configuration, mapper).get("default", "test-model"));
    }

    @Test
    void staleCacheTriggersRefetchAndRefresh() {
        final var cache = new ContextWindowCache(configuration, mapper);
        cache.put("default", "test-model", 4096, System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"context_window\":8192}]}";
        assertEquals(8192, tracker().contextWindow());
        assertEquals(Optional.of(8192), cache.get("default", "test-model"));
    }

    @Test
    void freshCacheSkipsModelsCall() {
        new ContextWindowCache(configuration, mapper).put("default", "test-model", 64000, System.currentTimeMillis());
        modelsStatus = 500;
        assertEquals(64000, tracker().contextWindow());
    }

    @Test
    void missingVendorValueFallsBackToDefault() {
        modelsBody = "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"x\"}]}";
        assertEquals(ModelsResponse.DEFAULT_CONTEXT_WINDOW, tracker().contextWindow());
    }

    @Test
    void modelsFailureFallsBackToDefault() {
        modelsStatus = 500;
        assertEquals(ModelsResponse.DEFAULT_CONTEXT_WINDOW, tracker().contextWindow());
    }

    private UsageTracker tracker() {
        return new UsageTracker(providerManager, client, new ContextWindowCache(configuration, mapper));
    }

    private CodingAgentConfiguration configuration(final int contextWindow) {
        final var provider = new ProviderConfiguration("default",
                "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key", "test-model",
                null, null, contextWindow, 5, "");
        return new CodingAgentConfiguration(storageHome.toString(), null, null,
                new ProviderListConfiguration(List.of(provider), "", null, null, null, null, null, contextWindow, 120),
                null, new LlmConfiguration(1, 1L, 0, 1, true, 250),
                null, null, null, null, null, null, null, null, null, null, null);
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
