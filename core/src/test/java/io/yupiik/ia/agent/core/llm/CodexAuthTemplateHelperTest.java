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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.Ed25519Util;
import io.yupiik.llm.client.codex.model.AgentIdentityRecord;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Tests the {@code {{codexAuth}}} dynamic-header helper: token store persistence and lazy refresh. */
@ExtendWith(TestJsonMapper.class)
class CodexAuthTemplateHelperTest {

    private HttpServer issuer;
    private String issuerUrl;
    private Path storage;
    private ExtendedHttpClient client;
    private HttpClient delegate;
    private final AtomicInteger refreshCalls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        issuer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuerUrl = "http://localhost:" + issuer.getAddress().getPort();
        issuer.createContext("/oauth/token", exchange -> {
            refreshCalls.incrementAndGet();
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("grant_type=refresh_token")) {
                respond(
                        exchange,
                        200,
                        "{\"access_token\":\"refreshed\",\"refresh_token\":\"rotated\",\"expires_in\":3600}");
            } else {
                respond(exchange, 200, "{\"access_token\":\"fresh\"}");
            }
        });
        issuer.start();
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
        storage = Files.createTempDirectory("yca-codex-auth-test");
    }

    @AfterEach
    void stop() {
        if (issuer != null) {
            issuer.stop(0);
            issuer = null;
        }
        if (client != null) {
            client.close();
            delegate.shutdownNow();
            delegate.close();
            delegate = null;
            client = null;
        }
    }

    @Test
    void freshTokenNeedsNoRoundTrip(final JsonMapper mapper) throws Exception {
        seed(mapper, "p1", new CodexTokenStore("valid-access", "refresh", "acc", farFuture(), "id"));
        final var helper = helper(mapper);
        assertEquals("valid-access", render(helper, "{{codexAuth \"p1\"}}"));
        assertEquals(0, refreshCalls.get(), "a valid token must not trigger a refresh");
    }

    @Test
    void expiredTokenRefreshesAndPersistsRotation(final JsonMapper mapper) throws Exception {
        seed(mapper, "p2", new CodexTokenStore("stale", "refresh-tok", "acc", past(), "id"));
        final var helper = helper(mapper);
        assertEquals("refreshed", render(helper, "{{codexAuth \"p2\"}}"));
        assertEquals(1, refreshCalls.get());
        // the rotated refresh token and the new access token must be persisted
        final CodexTokenStore saved = mapper.fromBytes(
                CodexTokenStore.class, Files.readAllBytes(storage.resolve("providers/p2/codex.auth.json")));
        assertEquals("refreshed", saved.accessToken());
        assertEquals("rotated", saved.refreshToken());
        // a subsequent render reuses the freshly persisted token (no second refresh)
        assertEquals("refreshed", render(helper, "{{codexAuth \"p2\"}}"));
        assertEquals(1, refreshCalls.get(), "persisted fresh token must not re-refresh");
    }

    @Test
    void missingSessionFailsLoudly(final JsonMapper mapper) {
        final var helper = helper(mapper);
        final var e = assertThrows(
                io.yupiik.llm.client.codex.CodexNotAuthenticatedException.class,
                () -> render(helper, "{{codexAuth \"absent\"}}"));
        assertEquals("absent", e.provider());
        assertTrue(e.getMessage().contains("interactive login"), e.getMessage());
    }

    @Test
    void codexAccountIdUsesAgentIdentityAccountWhenPresent(final JsonMapper mapper) throws Exception {
        final var key = Ed25519Util.generate();
        seed(
                mapper,
                "agent-p",
                new CodexTokenStore(
                        "access",
                        "refresh",
                        "oauth-account",
                        farFuture(),
                        "id",
                        // the agent identity carries a different (managed workspace) account id
                        new AgentIdentityRecord(
                                "runtime-1",
                                key.privateKeyPkcs8Base64(),
                                "workspace-account",
                                "user-9",
                                null,
                                "enterprise",
                                false,
                                "task-1")));
        final var helper = helper(mapper);
        final var rendered =
                render(helper, "{{codexAccountId \"agent-p\"}}", Map.of("codexAccountId", helper.codexAccountId()));
        assertEquals("workspace-account", rendered, "agent identity account id wins over the oauth one");
    }

    @Test
    void codexAccountIdFallsBackToOAuthAccount(final JsonMapper mapper) throws Exception {
        seed(mapper, "plain-p", new CodexTokenStore("access", "refresh", "oauth-account", farFuture(), "id"));
        final var helper = helper(mapper);
        final var rendered =
                render(helper, "{{codexAccountId \"plain-p\"}}", Map.of("codexAccountId", helper.codexAccountId()));
        assertEquals("oauth-account", rendered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void codexAgentAssertionProducesVerifiableSignedHeader(final JsonMapper mapper) throws Exception {
        final var key = Ed25519Util.generate();
        seed(
                mapper,
                "agent-a",
                new CodexTokenStore(
                        "access",
                        "refresh",
                        "workspace-account",
                        farFuture(),
                        "id",
                        new AgentIdentityRecord(
                                "runtime-9",
                                key.privateKeyPkcs8Base64(),
                                "workspace-account",
                                "user-9",
                                null,
                                "business",
                                false,
                                "task-77")));
        final var helper = helper(mapper);
        final var rendered = render(
                helper,
                "{{codexAgentAssertion \"agent-a\"}}",
                Map.of("codexAgentAssertion", helper.codexAgentAssertion()));
        assertTrue(rendered.startsWith("AgentAssertion "), rendered);
        // decode the envelope and verify the signature against the stored Ed25519 private key,
        // exactly the guarantee codex relies on when the server checks the assertion
        final String envelope = rendered.substring("AgentAssertion ".length());
        final Map<String, Object> parsed = mapper.fromString(Map.class, envelope);
        assertEquals("runtime-9", parsed.get("agent_runtime_id"));
        assertEquals("task-77", parsed.get("task_id"));
        final String timestamp = String.valueOf(parsed.get("timestamp"));
        final String signature = String.valueOf(parsed.get("signature"));
        assertTrue(
                Ed25519Util.verify(key.privateKeyPkcs8Base64(), "runtime-9:task-77:" + timestamp, signature),
                "assertion signature must verify against the seeded key");
    }

    @Test
    void codexAgentAssertionRejectsPlainOAuthSession(final JsonMapper mapper) throws Exception {
        seed(mapper, "plain-agent", new CodexTokenStore("access", "refresh", "acc", farFuture(), "id"));
        final var helper = helper(mapper);
        assertThrows(
                IllegalStateException.class,
                () -> render(
                        helper,
                        "{{codexAgentAssertion \"plain-agent\"}}",
                        Map.of("codexAgentAssertion", helper.codexAgentAssertion())));
    }

    private CodingAgentConfiguration config() {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", storage.toString());
        return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(map.get(key))).get();
    }

    private CodexOAuthConfig oauthConfig() {
        return new CodexOAuthConfig(issuerUrl, "test-client", "openid", issuerUrl + "/codex/device", 1455);
    }

    private CodexAuthTemplateHelper helper(final JsonMapper mapper) {
        final var helper = new CodexAuthTemplateHelper(mapper, client, config());
        helper.setOAuthConfig(oauthConfig());
        return helper;
    }

    private void seed(final JsonMapper mapper, final String provider, final CodexTokenStore store) throws IOException {
        final Path file = storage.resolve("providers/" + provider + "/codex.auth.json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.toBytes(store));
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 3600_000;
    }

    private static long past() {
        return System.currentTimeMillis() - 1000;
    }

    private static String render(final CodexAuthTemplateHelper helper, final String source) {
        return render(helper, source, Map.of("codexAuth", helper.codexAuth()));
    }

    private static String render(
            final CodexAuthTemplateHelper helper,
            final String source,
            final Map<String, Function<HelperContext, Object>> helpers) {
        final Template template = new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(helpers), source));
        return template.render(Map.of());
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
