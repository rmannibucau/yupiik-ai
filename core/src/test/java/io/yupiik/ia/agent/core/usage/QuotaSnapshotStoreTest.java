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
package io.yupiik.ia.agent.core.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Tests the per-session quota cache: TTL refresh, forced refresh and session isolation. */
@ExtendWith(TestJsonMapper.class)
class QuotaSnapshotStoreTest {

    private HttpServer server;
    private String serverUrl;
    private Path storage;
    private ExtendedHttpClient httpClient;
    private HttpClient delegate;
    private CodingAgentConfiguration configuration;
    private ProviderManager providerManager;
    private CodexAuthTemplateHelper auth;
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicInteger usedPercent = new AtomicInteger(10);

    @BeforeEach
    void start(final JsonMapper mapper) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/wham/usage", exchange -> {
            fetches.incrementAndGet();
            respond(exchange, 200, """
                    {"plan_type":"plus",
                     "rate_limit":{"primary_window":{"used_percent":%d,"limit_window_seconds":18000,"reset_at":1700000000}},
                     "credits":{"has_credits":true,"balance":"10"}}
                    """.formatted(usedPercent.get()));
        });
        server.start();
        delegate = HttpClient.newBuilder().build();
        httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
        storage = Files.createTempDirectory("yca-quota-store-test");

        final var props = new HashMap<String, String>();
        props.put("yca.storageHome", storage.toString());
        props.put("yca.provider.providers.length", "1");
        // a codex provider with usage enabled
        props.put("yca.provider.providers.0.id", "codex");
        props.put("yca.provider.providers.0.baseUrl", serverUrl + "/codex");
        props.put("yca.provider.providers.0.apiKey", "");
        props.put("yca.provider.providers.0.capabilities.codex.enableUsage", "true");
        configuration = new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(props.get(key)))
                .get();
        providerManager = new ProviderManager(configuration, mapper);
        auth = new CodexAuthTemplateHelper(mapper, httpClient, configuration);
        auth.setOAuthConfig(new CodexOAuthConfig(serverUrl, "test", "openid", serverUrl + "/device", 1455));
        // seed a valid session
        final Path file = storage.resolve("providers/codex/codex.auth.json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.toBytes(new CodexTokenStore("access", "refresh", "acc", farFuture(), "id")));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (httpClient != null) {
            httpClient.close();
            delegate.shutdownNow();
            delegate.close();
            delegate = null;
            httpClient = null;
        }
    }

    private QuotaSnapshotStore store(final JsonMapper mapper) {
        return new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, Clock.systemUTC());
    }

    @Test
    void ttlAndForcedRefresh(final JsonMapper mapper) {
        final var store = store(mapper);
        final ChatSettings settings = ChatSettings.EMPTY.withProviderId("codex");
        store.attach("s1", settings);
        assertEquals(1, fetches.get(), "attach must fetch on provider init");

        // within the TTL, a non-forced snapshot must NOT refetch
        store.snapshot("s1", false);
        assertEquals(1, fetches.get(), "no refetch within TTL");

        // a forced snapshot must bypass the TTL
        usedPercent.set(55);
        final var fresh = store.snapshot("s1", true);
        assertEquals(2, fetches.get(), "forced refresh must fetch");
        assertEquals(55.0, fresh.primaryWindow().usedPercent(), 0.001);
    }

    @Test
    void unsupportedProviderHidesTheGauge(final JsonMapper mapper) {
        // provider without enableUsage -> supported false, and no fetch attempted
        final var props = new HashMap<String, String>();
        props.put("yca.storageHome", storage.toString());
        props.put("yca.provider.providers.length", "1");
        props.put("yca.provider.defaultProvider", "plain");
        props.put("yca.provider.providers.0.id", "plain");
        props.put("yca.provider.providers.0.baseUrl", serverUrl + "/v1");
        props.put("yca.provider.providers.0.apiKey", "k");
        final var cfg = new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(props.get(key)))
                .get();
        final var pm = new ProviderManager(cfg, mapper);
        final var store = new QuotaSnapshotStore(mapper, httpClient, auth, pm, Clock.systemUTC());
        store.attach("s2", ChatSettings.EMPTY.withProviderId("plain"));
        final var view = store.snapshot("s2", false);
        assertFalse(view.supported(), "a provider without enableUsage is not supported");
        assertEquals(0, fetches.get(), "no quota fetch for an unsupported provider");
    }

    @Test
    void sessionsAreIsolatedAndCloseReleases(final JsonMapper mapper) {
        final var store = store(mapper);
        final ChatSettings codex = ChatSettings.EMPTY.withProviderId("codex");
        store.attach("tab-a", codex);
        store.attach("tab-b", codex);
        assertEquals(2, fetches.get(), "each session attaches its own independent quota fetch");

        // closing one session must not affect the other's cached view
        store.close("tab-a");
        final var remaining = store.snapshot("tab-b", false);
        assertTrue(remaining.supported(), "the remaining session keeps its quota");
        assertEquals(2, fetches.get(), "closing a session does not refetch the other");
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 3_600_000;
    }

    private void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (final var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
