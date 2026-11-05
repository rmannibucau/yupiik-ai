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
package io.yupiik.ia.agent.core.shell.slash;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the /usage slash command proxying a mock Codex usage backend. */
@ExtendWith(TestJsonMapper.class)
class UsageCommandsTest {

    private HttpServer server;
    private String serverUrl;

    @TempDir
    Path storage;

    private ExtendedHttpClient httpClient;
    private HttpClient delegate;

    @BeforeEach
    void start(final JsonMapper mapper) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/wham/usage", exchange -> respond(exchange, 200, """
                {"plan_type":"plus",
                 "rate_limit":{"primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_at":1700000000}},
                 "credits":{"has_credits":true,"balance":"10"}}
                """));
        server.start();
        delegate = HttpClient.newBuilder().build();
        httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
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

    @Test
    void unsupportedProviderReportsNotSupported(final JsonMapper mapper) {
        final var store = store(mapper, "plain", serverUrl + "/v1/codex", false);
        final var cmd = new UsageCommands(store);
        final var env = env(new ShellSession().sessionId("s1"));
        final var outcome = cmd.commands()
                .get(0)
                .action()
                .apply(null, env)
                .toCompletableFuture()
                .join();
        assertTrue(content(outcome).toLowerCase().contains("not supported"), content(outcome));
    }

    @Test
    void renderedTableForSupportedProvider(final JsonMapper mapper) throws IOException {
        final var store = store(mapper, "codex", serverUrl + "/codex", true);
        seed(mapper, "codex", new CodexTokenStore("access", "refresh", "acc", farFuture(), "id"));
        // attach the session with the codex provider so the command's forced refresh has a snapshot to render
        store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
        final var cmd = new UsageCommands(store);
        final var env = env(new ShellSession().sessionId("s1"));
        final var outcome = cmd.commands()
                .get(0)
                .action()
                .apply(null, env)
                .toCompletableFuture()
                .join();
        final var content = content(outcome);
        assertTrue(content.contains("plus"), "plan type must be rendered: " + content);
        assertTrue(content.contains("42%"), "used percent must be rendered: " + content);
    }

    /** Extracts the human-facing text of a status/transcript/error outcome. */
    private static String content(final ShellOutcome outcome) {
        if (outcome instanceof ShellOutcome.Status(String message)) {
            return message;
        }
        if (outcome instanceof ShellOutcome.Transcript(String markdown)) {
            return markdown;
        }
        if (outcome instanceof ShellOutcome.Error(String message)) {
            return message;
        }
        return "";
    }

    private QuotaSnapshotStore store(
            final JsonMapper mapper, final String id, final String baseUrl, final boolean usage) {
        final var props = new HashMap<String, String>();
        props.put("yca.storageHome", storage.toString());
        props.put("yca.provider.providers.length", "1");
        props.put("yca.provider.providers.0.id", id);
        props.put("yca.provider.providers.0.baseUrl", baseUrl);
        props.put("yca.provider.providers.0.apiKey", "");
        props.put("yca.provider.providers.0.capabilities.codex.enableUsage", Boolean.toString(usage));
        final var cfg = new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(props.get(key)))
                .get();
        final var auth = new CodexAuthTemplateHelper(mapper, httpClient, cfg);
        auth.setOAuthConfig(new CodexOAuthConfig(serverUrl, "test", "openid", serverUrl + "/device", 1455));
        return new QuotaSnapshotStore(mapper, httpClient, auth, new ProviderManager(cfg, mapper), Clock.systemUTC());
    }

    private static ShellEnv env(final ShellSession session) {
        return new ShellEnv(null, null, session, new ConversationTranscript());
    }

    private void seed(final JsonMapper mapper, final String provider, final CodexTokenStore t) throws IOException {
        final Path file = storage.resolve("providers/" + provider + "/codex.auth.json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.toBytes(t));
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 3_600_000;
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (final var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
