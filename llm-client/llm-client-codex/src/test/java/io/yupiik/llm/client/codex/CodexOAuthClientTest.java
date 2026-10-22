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
package io.yupiik.llm.client.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.codex.model.CodexModels;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class CodexOAuthClientTest {
    private HttpServer issuer;
    private String issuerUrl;
    private ExtendedHttpClient client;
    private HttpClient delegate;
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        issuer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuer.start();
        issuerUrl = "http://localhost:" + issuer.getAddress().getPort();
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
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
    void deviceFlowAndRefresh(final JsonMapper mapper) throws Exception {
        // a fake id_token whose payload carries the namespaced chatgpt_account_id claim
        final String idTokenPayload =
                "{\"https://api.openai.com/auth.chatgpt_account_id\":\"acc-123\"," + "\"sub\":\"user\"}";
        final String idToken = "header."
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(idTokenPayload.getBytes(StandardCharsets.UTF_8))
                + ".sig";

        issuer.createContext("/api/accounts/deviceauth/usercode", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"device_auth_id\":\"da-1\",\"user_code\":\"AB12-CD34\",\"interval\":1}");
        });
        issuer.createContext("/api/accounts/deviceauth/token", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"authorization_code\":\"thecode\",\"code_verifier\":\"verifier\"}");
        });
        issuer.createContext("/oauth/token", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("grant_type=refresh_token")) {
                respond(
                        exchange,
                        200,
                        "{\"access_token\":\"acc-token-2\",\"refresh_token\":\"refresh-2\",\"expires_in\":3600}");
            } else {
                respond(
                        exchange,
                        200,
                        "{\"access_token\":\"acc-token\",\"refresh_token\":\"refresh-1\",\"expires_in\":3600,"
                                + "\"id_token\":\"" + idToken + "\"}");
            }
        });

        final CodexOAuthClient oauth = new CodexOAuthClient(mapper, client, issuerUrl);
        final CodexOAuthClient.DeviceCodeChallenge challenge = oauth.deviceCode();
        assertEquals("da-1", challenge.deviceAuthId());
        assertEquals("AB12-CD34", challenge.userCode());
        assertTrue(challenge.prompt().contains("AB12-CD34"));

        final CodexTokenStore store = oauth.poll(challenge, b -> false);
        assertNotNull(store);
        assertEquals("acc-token", store.accessToken());
        assertEquals("refresh-1", store.refreshToken());
        assertEquals("acc-123", store.accountId());
        assertTrue(store.expiresAt() > System.currentTimeMillis());
        assertFalse(store.expired(System.currentTimeMillis()));

        // a refresh keeps the account id and uses the refresh token
        final CodexTokenStore refreshed = oauth.refresh(store);
        assertEquals("acc-token-2", refreshed.accessToken());
        assertEquals("refresh-2", refreshed.refreshToken(), "rotated refresh token must be persisted");
        assertEquals("acc-123", refreshed.accountId());
    }

    @Test
    void deviceGateIsSurfacedClearly(final JsonMapper mapper) throws Exception {
        issuer.createContext(
                "/api/accounts/deviceauth/usercode",
                exchange -> respond(exchange, 404, "device code login is not enabled"));
        final CodexOAuthClient oauth = new CodexOAuthClient(mapper, client, issuerUrl);
        final var e = assertThrows(CodexDeviceAuthDisabledException.class, oauth::deviceCode);
        assertTrue(e.getMessage().contains("browser login"), e.getMessage());
    }

    @Test
    void browserAuthorizeUrlUsesLoopbackRedirectAndCodexParams() {
        final var url = CodexBrowserFlow.authorizeUrl(
                new CodexOAuthConfig(
                        issuerUrl,
                        "cid",
                        "openid profile email offline_access api.connectors.read api.connectors.invoke",
                        "vurl",
                        1455),
                "st",
                "verifier",
                "challenge",
                "http://127.0.0.1:1455/auth/callback");
        assertTrue(url.startsWith(issuerUrl + "/oauth/authorize"), url);
        assertTrue(url.contains("redirect_uri=" + urlEncode("http://127.0.0.1:1455/auth/callback")), url);
        assertTrue(url.contains("api.connectors.read"), url);
        assertTrue(url.contains("api.connectors.invoke"), url);
        // underscores stay literal (standard request form): the console frontends wrap the URL in an
        // autolink/OSC-8 hyperlink so the "_" is never treated as markdown emphasis and never stripped
        assertTrue(url.contains("codex_cli_simplified_flow=true"), url);
        assertTrue(url.contains("id_token_add_organizations=true"), url);
        assertTrue(url.contains("originator=codex_cli_rs"), url);
        assertTrue(url.contains("code_challenge=challenge"), url);
        assertFalse(url.contains("%5F"), "underscores must stay literal, not %5F-escaped: " + url);
        assertFalse(url.contains("codex_cli_simplified_flow=false"), url);
        assertFalse(url.contains("/deviceauth/callback"), "the authorize URL must not use the device callback");
    }

    private static String urlEncode(final String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void callbackServerIsReleasedAfterSuccess(final JsonMapper mapper) throws Exception {
        // a fake id_token carrying the chatgpt_account_id claim
        final String idTokenPayload = "{\"chatgpt_account_id\":\"acc-9\"}";
        final String idToken = "header."
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(idTokenPayload.getBytes(StandardCharsets.UTF_8))
                + ".sig";
        issuer.createContext("/oauth/token", exchange -> {
            respond(
                    exchange,
                    200,
                    "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"expires_in\":3600,\"id_token\":\"" + idToken
                            + "\"}");
        });

        final CodexOAuthClient oauth = new CodexOAuthClient(mapper, client, issuerUrl);
        final AtomicReference<Integer> callbackPort = new AtomicReference<>();
        final AtomicReference<String> callbackState = new AtomicReference<>();

        // the browser flow is blocking; drive the callback server from the authorised sink and
        // complete it right after the server (and the authorize URL) exist
        final CompletableFuture<CodexTokenStore> result = CompletableFuture.supplyAsync(() -> oauth.browserFlow(
                url -> {
                    callbackPort.set(portOf(url));
                    callbackState.set(stateOf(url));
                },
                b -> false));

        // wait for the sink to learn the port, then simulate the browser redirect to the callback
        final long deadline = System.currentTimeMillis() + 5_000;
        while ((callbackPort.get() == null || callbackState.get() == null) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull(callbackPort.get(), "the authorize URL must expose the loopback port");

        final var http = HttpClient.newHttpClient();
        final var callback = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + callbackPort.get()
                        + "/auth/callback?state=" + callbackState.get() + "&code=thecode"))
                .GET()
                .build();
        http.send(callback, java.net.http.HttpResponse.BodyHandlers.discarding());

        final CodexTokenStore store = result.get(5, TimeUnit.SECONDS);
        assertEquals("acc", store.accessToken());
        assertEquals("acc-9", store.accountId());

        // the callback server must be released: binding on the same port again succeeds
        final var probe = HttpServer.create(new InetSocketAddress("127.0.0.1", callbackPort.get()), 0);
        probe.stop(0);
    }

    private static int portOf(final String authorizeUrl) {
        final var uri = URI.create(authorizeUrl);
        final var query = uri.getRawQuery();
        final var m = java.util.regex.Pattern.compile("redirect_uri=[^&]*127\\.0\\.0\\.1%3A(\\d+)[^&]*")
                .matcher(query);
        assertTrue(m.find(), "cannot find the port in " + authorizeUrl);
        return Integer.parseInt(m.group(1));
    }

    private static String stateOf(final String authorizeUrl) {
        final var uri = URI.create(authorizeUrl);
        final var m = java.util.regex.Pattern.compile("state=([^&]+)").matcher(uri.getRawQuery());
        assertTrue(m.find(), "cannot find the state in " + authorizeUrl);
        return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    @Test
    void accountIdExtraction(final JsonMapper mapper) {
        final String payload = "{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"ns-1\"}}";
        final String token = "h."
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertEquals("ns-1", CodexOAuthClient.extractAccountId(token, mapper));

        final String bare = "{\"chatgpt_account_id\":\"bare-1\"}";
        final String bareToken = "h."
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(bare.getBytes(StandardCharsets.UTF_8));
        assertEquals("bare-1", CodexOAuthClient.extractAccountId(bareToken, mapper));

        assertNull(CodexOAuthClient.extractAccountId("not-a-jwt", mapper));
        assertNull(CodexOAuthClient.extractAccountId(null, mapper));
    }

    @Test
    void nonRefreshableStoreReturnsUnchanged() {
        final CodexTokenStore store = new CodexTokenStore("acc", null, "a", 1L, null);
        assertFalse(store.refreshable());
    }

    @Test
    void modelsFiltering() {
        final var hidden = new CodexModels.CodexModel("hidden", "Hidden", null, "hide", true, List.of(0), null, null);
        final var listed = new CodexModels.CodexModel("listed", "Listed", null, "list", true, List.of(0), null, null);
        final var notApi = new CodexModels.CodexModel("notapi", "NoApi", null, "list", false, List.of(0), null, null);
        final var gated =
                new CodexModels.CodexModel("gated", "Gated", null, "list", true, List.of(0, 200, 0), null, null);

        assertFalse(hidden.selectable());
        assertTrue(listed.selectable());
        assertFalse(notApi.selectable());
        assertTrue(gated.usableWith(200), "client version equal to the minimum is usable");
        assertTrue(gated.usableWith(250), "client version above the minimum is usable");
        assertFalse(gated.usableWith(150), "client version below the minimum is not usable");
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void transportFailureIsDescriptive(final JsonMapper mapper) throws Exception {
        // a dead endpoint: the JDK IOException often carries a null message, the error must still
        // surface the exception class and the sanitized target instead of "... failed: null"
        // (an unreachable port fails fast with ConnectException, no hang risk)
        final int port = 1; // tcpmux, guaranteed closed locally
        final CodexOAuthClient oauth = new CodexOAuthClient(mapper, client, "http://127.0.0.1:" + port);
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> oauth.deviceCode());
        final String message = e.getMessage();
        assertNotNull(message);
        assertFalse(message.trim().endsWith(": null"), "message must not end with 'null': " + message);
        assertTrue(message.contains("/api/accounts/deviceauth/usercode"), "must carry the path: " + message);
        assertFalse(message.contains("?"), "must not leak the query: " + message);
    }
}
