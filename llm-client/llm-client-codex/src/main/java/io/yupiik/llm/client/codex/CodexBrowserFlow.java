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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * PKCE authorization-code browser flow for the Codex OAuth, backed by the JDK {@link HttpServer}.
 *
 * <p>A loopback HTTP server is started <em>only for the duration of the interactive login</em>: it
 * binds {@code localhost} (never a public interface), captures the redirect carrying the
 * authorization code, then is stopped immediately for both success and failure paths. The flow never
 * needs a long-lived server component.</p>
 */
final class CodexBrowserFlow {

    /** How long to wait for the browser redirect before giving up. */
    private static final int TIMEOUT_SECONDS = 300;

    private CodexBrowserFlow() {
        // utility
    }

    /**
     * Runs the whole PKCE browser flow: starts the callback server, builds the authorize URL, and
     * blocks until the user authorizes in a browser. The callback server is stopped in every exit
     * path once the flow settles. {@code authorizeUrlConsumer} receives the URL the caller must open
     * (printed in a terminal, shown in a TUI modal, or opened in a web tab).
     *
     * @param oauth the OAuth client used for the token exchange.
     * @param authorised the pre-computed authorize URL consumer.
     * @param cancelled cancellation probe checked before waiting and while polling the device flow.
     * @return the authenticated token store (never {@code null}; the account id is derived from the id_token).
     * @throws IllegalStateException when the flow fails or is cancelled.
     */
    static CodexTokenStore run(
            final CodexOAuthClient oauth, final Consumer<String> authorised, final Predicate<Boolean> cancelled) {
        final SecureRandom random = new SecureRandom();
        final String verifier = base64Url(randomBytes(random, 32));
        final String challenge = sha256(verifier);
        final String state = base64Url(randomBytes(random, 32));
        final var config = oauth.config();
        final CompletableFuture<String> codeFuture = new CompletableFuture<>();

        HttpServer server = null;
        try {
            try {
                server = bindServer(config.callbackPort());
            } catch (final IOException e) {
                throw new IllegalStateException(
                        "Cannot start the Codex OAuth callback server on localhost (loopback unavailable): "
                                + e.getMessage(),
                        e);
            }
            final int port = server.getAddress().getPort();
            server.createContext("/auth/callback", exchange -> handleCallback(exchange, state, codeFuture));
            server.setExecutor(null); // one-shot callback: the default single-threaded dispatcher is enough
            server.start();

            // the redirect_uri mirrors the loopback callback server and must be exactly
            // http://127.0.0.1:{port}/auth/callback: OpenAI registers 127.0.0.1 as the allowed
            // loopback redirect for the Codex client (a "localhost" variant is rejected with
            // invalid_authorize_request). The exchange must use the same redirect_uri.
            final String redirectUri = "http://127.0.0.1:" + port + "/auth/callback";
            final String authorizeUrl = authorizeUrl(config, state, verifier, challenge, redirectUri);

            // surface the authorize URL to the caller (open a browser / show a modal / print)
            if (authorised != null) {
                authorised.accept(authorizeUrl);
            }
            if (cancelled != null && cancelled.test(Boolean.TRUE)) {
                throw new IllegalStateException("Codex browser login cancelled before authorization");
            }

            try {
                final String code = codeFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                final Map<String, Object> tokens = oauth.exchangeCode(code, verifier, redirectUri);
                return oauth.toStore(tokens);
            } catch (final TimeoutException e) {
                throw new IllegalStateException("Codex browser login timed out waiting for the redirect", e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Codex browser login interrupted", e);
            } catch (final ExecutionException e) {
                final Throwable cause = e.getCause();
                throw new IllegalStateException(
                        "Codex browser login failed: " + (cause == null ? e.toString() : cause.getMessage()), cause);
            }
        } finally {
            if (server != null) {
                server.stop(0); // torn down in all exit paths
            }
        }
    }

    /**
     * Binds a loopback-only callback server. Prefers {@code preferredPort}; if it is already taken
     * (eg. a previous login still pending) it falls back to any free port, so a second login never
     * hard-fails just because the default port is busy. Only errors if even the ephemeral port cannot
     * be bound. The caller reads the actual bound port for the {@code redirect_uri}.
     */
    private static HttpServer bindServer(final int preferredPort) throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", preferredPort), 0);
        } catch (final IOException busy) {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
    }

    private static void handleCallback(
            final HttpExchange exchange, final String expectedState, final CompletableFuture<String> codeFuture) {
        try {
            final String query = exchange.getRequestURI().getRawQuery();
            final Map<String, String> params = parseQuery(query);
            final String receivedState = params.get("state");
            final String code = params.get("code");
            if (receivedState == null || !receivedState.equals(expectedState) || code == null) {
                respond(exchange, 400, "State mismatch or missing code.");
                return;
            }
            respond(exchange, 200, "Login complete. You can close this tab and return to the terminal.");
            codeFuture.complete(code);
        } catch (final RuntimeException e) {
            respond(exchange, 500, "Server error checking your login.");
            codeFuture.completeExceptionally(e);
        }
    }

    private static void respond(final HttpExchange exchange, final int status, final String message) {
        try {
            final byte[] body =
                    ("<html><body><h2>" + status + "</h2><p>" + message + "</p></body></html>").getBytes(UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (final IOException ignored) {
            // the client closed the connection; nothing else to do
        } finally {
            exchange.close();
        }
    }

    /**
     * Builds the authorization-code authorize URL with PKCE S256 challenge, mirroring the Codex CLI:
     * the loopback {@code redirect_uri}, the Codex connector scopes and the simplified-flow/originator
     * extras the backend requires (the authorize call is invalid without them).
     */
    static String authorizeUrl(
            final CodexOAuthConfig config,
            final String state,
            final String verifier,
            final String challenge,
            final String redirectUri) {
        // Default scope when the config does not override it (matches the Codex CLI's required scopes).
        final String scopes = config.scopes() == null || config.scopes().isBlank()
                ? "openid profile email offline_access api.connectors.read api.connectors.invoke"
                : config.scopes();
        final String raw = config.issuer() + "/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + urlEncode(config.clientId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode(scopes)
                + "&code_challenge=" + urlEncode(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state)
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true"
                + "&originator=codex_cli_rs";
        // Underscores are kept literal in both parameter names and values: it is the standard OAuth/OpenID
        // request form and auth.openai.com validates the raw parameter names against them (an encoded
        // "response%5Ftype" still works, but there is no reason to risk a strict proxy/logger that rejects
        // encoded names). The console frontends surface this URL as a commonmark autolink (<https://...>)
        // and/or an OSC-8 hyperlink, so the "_" is never interpreted as markdown emphasis and the full URL
        // stays clickable and copyable even when it wraps across terminal rows.
        return raw;
    }

    /** Parses a raw query string into a key/value map (first value wins). */
    static Map<String, String> parseQuery(final String query) {
        final Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (final String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            final String key = eq < 0 ? pair : pair.substring(0, eq);
            final String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            map.putIfAbsent(key, value);
        }
        return map;
    }

    private static byte[] randomBytes(final SecureRandom random, final int count) {
        final byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String base64Url(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(final String value) {
        try {
            return base64Url(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String urlEncode(final String value) {
        return URLEncoder.encode(value, UTF_8);
    }
}
