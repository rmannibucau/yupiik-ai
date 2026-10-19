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

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OAuth2 client for the ChatGPT-backed Codex backend (the flow used by the Codex CLI and the VS Code
 * extension). Two interactive login methods plus a token refresh:
 *
 * <ul>
 *   <li>{@link #deviceCode()} → {@link #poll(DeviceCodeChallenge, Predicate)}: headless/terminal
 *       friendly. The caller prints the verification URL and code, then repeatedly poll until the
 *       user authorizes (or a cancellation/expiry stops the loop).</li>
 *   <li>{@link #browserFlow(String, Predicate)}: PKCE authorization-code flow. A short-lived
 *       {@link com.sun.net.httpserver.HttpServer} on loopback captures the redirect; the caller
 *       opens the returned URL in a browser and the flow completes when the code arrives. The server
 *       lives only for the duration of the interactive login.</li>
 *   <li>{@link #refresh(CodexTokenStore)}: exchanges a refresh token for a fresh access token,
 *       persisting the possibly-rotated refresh token.</li>
 * </ul>
 *
 * <p>The public, well-known constants (issuer, client id, endpoints) mirror {@code openai/codex}
 * so the flow works against the real ChatGPT/Codex backend.</p>
 */
public class CodexOAuthClient {

    private static final Logger LOGGER = Logger.getLogger(CodexOAuthClient.class.getName());

    private final JsonMapper mapper;
    private final ExtendedHttpClient client;
    private final CodexOAuthConfig config;

    public CodexOAuthClient(final JsonMapper mapper, final ExtendedHttpClient client) {
        this(mapper, client, CodexOAuthConfig.DEFAULT);
    }

    /**
     * Creates a client with a custom configuration (mainly for tests and for proxying through a
     * gateway/regional issuer). Defaults to {@link CodexOAuthConfig#DEFAULT}.
     *
     * @param config the tunable OAuth settings.
     */
    public CodexOAuthClient(final JsonMapper mapper, final ExtendedHttpClient client, final CodexOAuthConfig config) {
        this.mapper = mapper;
        this.client = client;
        this.config = config == null ? CodexOAuthConfig.DEFAULT : config;
    }

    /** The effective OAuth configuration. */
    public CodexOAuthConfig config() {
        return config;
    }

    /**
     * Creates a client pointing at a custom issuer (a convenience for {@link #CodexOAuthClient(JsonMapper, ExtendedHttpClient, CodexOAuthConfig)}).
     * The path layout under the issuer is unchanged.
     *
     * @param issuer the OAuth issuer base URL.
     */
    public CodexOAuthClient(final JsonMapper mapper, final ExtendedHttpClient client, final String issuer) {
        this(
                mapper,
                client,
                new CodexOAuthConfig(
                        issuer,
                        CodexOAuthConfig.DEFAULT.clientId(),
                        CodexOAuthConfig.DEFAULT.scopes(),
                        CodexOAuthConfig.DEFAULT.verificationUrl(),
                        CodexOAuthConfig.DEFAULT.callbackPort()));
    }

    /** A pending device-code challenge: what to display and what to poll with. */
    public record DeviceCodeChallenge(String deviceAuthId, String userCode, int interval, String verificationUrl) {

        /** A stable anonymous copy of the code and the URL to open (no auth/account secrets). */
        public String prompt() {
            return "Open " + verificationUrl + " and enter code: " + userCode;
        }
    }

    /**
     * Starts the device-code flow: asks the issuer for a challenge.
     *
     * @return the challenge to display to the user and to poll with.
     */
    public DeviceCodeChallenge deviceCode() {
        final var payload = mapper.toString(Map.of("client_id", config.clientId()));
        try {
            final var body = postJson(issuer() + "/api/accounts/deviceauth/usercode", payload, "application/json");
            final var map = parse(body);
            return new DeviceCodeChallenge(
                    string(map, "device_auth_id"),
                    string(map, "user_code"),
                    map.get("interval") instanceof Number n ? n.intValue() : 5,
                    config.verificationUrl());
        } catch (final IllegalStateException e) {
            // the usercode endpoint only fails when the device grant is disabled for this
            // client/account: surface it clearly instead of forwarding an opaque server response
            throw new CodexDeviceAuthDisabledException(
                    "Device-code sign-in is not enabled for this ChatGPT account/client. "
                            + "Use the default browser login (no --device), or enable device sign-in in your ChatGPT security settings.");
        }
    }

    private String issuer() {
        return config.issuer();
    }

    /**
     * Polls the device-authorization endpoint until the user authorizes, {@code cancelled} returns
     * true, or the token expires. Blocks the calling thread.
     *
     * @param challenge the challenge from {@link #deviceCode()}.
     * @param cancelled cancellation/sleep probe; checked before waiting the interval.
     * @return the exchanged token store (already contains the account id).
     * @throws IllegalStateException when the flow stops before authorization (cancelled or timed out).
     */
    public CodexTokenStore poll(final DeviceCodeChallenge challenge, final Predicate<Boolean> cancelled) {
        final var deadline = System.currentTimeMillis() + 15 * 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (cancelled != null && cancelled.test(Boolean.TRUE)) {
                throw new IllegalStateException("Device-code authorization cancelled");
            }
            final var body = postJson(
                    issuer() + "/api/accounts/deviceauth/token",
                    mapper.toString(
                            Map.of("device_auth_id", challenge.deviceAuthId(), "user_code", challenge.userCode())),
                    "application/json");
            final var map = parse(body);
            final var code = string(map, "authorization_code");
            if (code != null) {
                // the issuer hands back an authorization code plus its PKCE verifier; exchange it for tokens
                final var tokens =
                        exchangeWithVerifier(code, string(map, "code_verifier"), issuer() + "/deviceauth/callback");
                return toStore(tokens);
            }
            // authorization still pending (or a transient non-authorization body): wait and retry
            try {
                Thread.sleep(challenge.interval() <= 0 ? 5_000 : challenge.interval() * 1000L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while polling device authorization", e);
            }
        }
        throw new IllegalStateException("Device-code authorization timed out");
    }

    /**
     * Runs the entire PKCE browser flow: starts a short-lived loopback callback server, builds the
     * authorize URL, hands it to {@code authorised}, and waits (blocking) for the redirect containing
     * the authorization code. The callback server is torn down when the code is captured or the flow
     * fails (success or failure), so it never outlives the interactive login.
     *
     * @param authorised receives the authorize URL; the caller decides how to surface it (print it,
     *     open a TUI modal, open a browser tab). May be {@code null} to skip surfacing.
     * @param cancelled cancellation probe.
     * @return the exchanged token store.
     */
    public CodexTokenStore browserFlow(
            final java.util.function.Consumer<String> authorised, final Predicate<Boolean> cancelled) {
        return CodexBrowserFlow.run(this, authorised, cancelled);
    }

    /**
     * Refreshes an expiring token store. The refresh token may rotate: the returned store carries the
     * rotated refresh token, which callers must persist. When no refresh token is available, the
     * {@code accessToken}/{@code refreshToken} are returned untouched (caller should trigger a re-login).
     *
     * @param current the current store (may be expiring/expired).
     * @return the refreshed store, or {@code current} unchanged when not refreshable.
     */
    public CodexTokenStore refresh(final CodexTokenStore current) {
        if (!current.refreshable()) {
            return current;
        }
        final var map = new LinkedHashMap<String, Object>();
        map.put("grant_type", "refresh_token");
        map.put("refresh_token", current.refreshToken());
        map.put("client_id", config.clientId());
        final var body = postForm(issuer() + "/oauth/token", map);
        final var tokens = parse(body);
        return new CodexTokenStore(
                string(tokens, "access_token"),
                firstNonBlank(string(tokens, "refresh_token"), current.refreshToken()),
                current.accountId(),
                expiry(tokens, current.accessToken()),
                string(tokens, "id_token"));
    }

    /**
     * Exchanges an authorization code (browser flow, optional PKCE verifier) for tokens.
     *
     * @param code the authorization code.
     * @param verifier the PKCE code verifier, or null/blank when the code is not PKCE-bound.
     * @return the parsed token payload (access/refresh/expires_in/id_token).
     */
    Map<String, Object> exchangeCode(final String code, final String verifier) {
        return exchangeWithVerifier(code, verifier, issuer() + "/deviceauth/callback");
    }

    /**
     * Exchanges an authorization code with a specific redirect_uri (the browser flow uses the loopback
     * callback, not the device callback, and both authorize and exchange must agree).
     *
     * @param code the authorization code.
     * @param verifier the PKCE code verifier, or null/blank.
     * @param redirectUri the {@code redirect_uri} sent in the authorize request.
     * @return the parsed token payload (access/refresh/expires_in/id_token).
     */
    Map<String, Object> exchangeCode(final String code, final String verifier, final String redirectUri) {
        return exchangeWithVerifier(code, verifier, redirectUri);
    }

    /** The store derived from a token payload; the account id derives from the id_token when present. */
    CodexTokenStore toStore(final Map<String, Object> tokens) {
        final var idToken = string(tokens, "id_token");
        final var accountId = extractAccountId(idToken);
        return new CodexTokenStore(
                string(tokens, "access_token"),
                string(tokens, "refresh_token"),
                accountId,
                expiry(tokens, null),
                idToken);
    }

    /** Exchanges a code (with optional PKCE verifier) at the token endpoint, returning the parsed payload. */
    private Map<String, Object> exchangeWithVerifier(
            final String code, final String verifier, final String redirectUri) {
        final var map = new LinkedHashMap<String, Object>();
        map.put("grant_type", "authorization_code");
        map.put("code", code);
        map.put("client_id", config.clientId());
        if (verifier != null && !verifier.isBlank()) {
            map.put("code_verifier", verifier);
        }
        map.put("redirect_uri", redirectUri == null ? issuer() + "/deviceauth/callback" : redirectUri);
        final var body = postForm(issuer() + "/oauth/token", map);
        return parse(body);
    }

    /** Build the expiry (epoch millis) from {@code expires_in}, falling back to {@code fallbackToken}'s expiry. */
    private static Long expiry(final Map<String, Object> tokens, final String fallbackToken) {
        final var expiresIn = tokens.get("expires_in");
        if (expiresIn instanceof Number n) {
            return System.currentTimeMillis() + n.longValue() * 1000;
        }
        if (fallbackToken == null) {
            return null;
        }
        // best effort: 1h default for a ChatGPT account access token
        return System.currentTimeMillis() + 3600_000;
    }

    /**
     * Extracts the {@code chatgpt_account_id} from an id_token JWT payload claim (not validating the
     * signature: this only reads our own token to derive the routing header, as the Codex CLI does).
     *
     * @return the account id, or null when the token/claim is absent or malformed.
     */
    public static String extractAccountId(final String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return null;
        }
        try {
            final var parts = idToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            final byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            final var json = new String(decoded, UTF_8);
            return extractClaim(json);
        } catch (final RuntimeException e) {
            LOGGER.fine(() -> "Cannot decode id_token account claim: " + e.getMessage());
            return null;
        }
    }

    private static final Pattern ACCOUNT_CLAIM =
            Pattern.compile("\"([^\"]*chatgpt_account_id[^\"]*)\"\\s*:\\s*\"([^\"]+)\"");

    /** Reads the account id out of a decoded id_token payload JSON. */
    private static String extractClaim(final String json) {
        // preferred: the namespaced claim; fallback: the bare chatgpt_account_id
        for (final String candidate :
                new String[] {"https://api.openai.com/auth.chatgpt_account_id", "chatgpt_account_id"}) {
            final String quoted = Pattern.quote(candidate);
            final Matcher m =
                    Pattern.compile("\"" + quoted + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (m.find()) {
                return m.group(1);
            }
        }
        // organisation fallback: .chatgpt_account_id or organizations[0].id
        final Matcher m = ACCOUNT_CLAIM.matcher(json);
        if (m.find()) {
            return m.group(2);
        }
        final Matcher org = Pattern.compile("\"organizations\"\\s*:\\s*\\[\\s*\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return org.find() ? org.group(1) : null;
    }

    /**
     * The account-relevant claims parsed (without signature verification) from a Codex access/id
     * token, mirroring {@code codex-rs/login/src/token_data.rs IdTokenInfo}. Signature checking is
     * deliberately skipped: this only reads our own token to derive routing/product decisions, as
     * the Codex CLI does.
     *
     * @param accountId the {@code chatgpt_account_id}.
     * @param chatgptUserId the {@code chatgpt_user_id}.
     * @param email the account email, when present.
     * @param planType the ChatGPT subscription plan (eg. free, plus, pro, business, enterprise, edu).
     * @param chatgptAccountIsFedramp whether the account routes through the FedRAMP edge.
     */
    public record IdTokenClaims(
            String accountId, String chatgptUserId, String email, String planType, boolean chatgptAccountIsFedramp) {

        /** True when the account belongs to a managed workspace plan (Team/Business/Enterprise style). */
        public boolean isWorkspacePlan() {
            if (planType == null || planType.isBlank()) {
                return false;
            }
            final var p = planType.toLowerCase(java.util.Locale.ROOT);
            return p.contains("business") || p.contains("enterprise") || p.contains("team") || p.contains("edu");
        }
    }

    /**
     * Parses the account claims out of a Codex JWT payload (access or id token). Falls back to the
     * organization id for the account id when the namespaced claims are absent, exactly like the
     * Codex CLI.
     *
     * @param token the access or id token, possibly null/blank.
     * @return the parsed claims, never {@code null} (fields may be null).
     */
    public static IdTokenClaims parseIdTokenClaims(final String token) {
        if (token == null || token.isBlank()) {
            return new IdTokenClaims(null, null, null, null, false);
        }
        try {
            final var parts = token.split("\\.");
            if (parts.length < 2) {
                return new IdTokenClaims(null, null, null, null, false);
            }
            final byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            final var json = new String(decoded, UTF_8);
            return claimsFrom(json);
        } catch (final RuntimeException e) {
            LOGGER.fine(() -> "Cannot decode id_token claims: " + e.getMessage());
            return new IdTokenClaims(null, null, null, null, false);
        }
    }

    /** Reads all id_token claims from a decoded JWT payload JSON. */
    private static IdTokenClaims claimsFrom(final String json) {
        // plan type / user id / fedramp lookups on the bare claim or the namespaced auth claim.
        final String plan = firstJsonString(
                json, new String[] {"https://api.openai.com/auth.chatgpt_plan_type", "chatgpt_plan_type"});
        final String userId =
                firstJsonString(json, new String[] {"https://api.openai.com/auth.chatgpt_user_id", "chatgpt_user_id"});
        final String email = firstJsonString(json, new String[] {"email", "preferred_username"});
        final boolean fedramp = json.contains("\"chatgpt_account_is_fedramp\"")
                && !json.contains("\"chatgpt_account_is_fedramp\":false");
        return new IdTokenClaims(extractClaim(json), userId, email, plan, fedramp);
    }

    private static String firstJsonString(final String json, final String[] candidates) {
        for (final String candidate : candidates) {
            final Matcher m = Pattern.compile("\"" + Pattern.quote(candidate) + "\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(json);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    // ----- HTTP and JSON plumbing -----

    private String postJson(final String url, final String json, final String contentType) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("content-type", contentType)
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, UTF_8))
                .build();
        return send(request);
    }

    private String postForm(final String url, final Map<String, Object> fields) {
        final var sb = new StringBuilder();
        fields.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(urlEncode(k)).append('=').append(urlEncode(String.valueOf(v)));
        });
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("content-type", "application/x-www-form-urlencoded")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString(), UTF_8))
                .build();
        return send(request);
    }

    private String send(final HttpRequest request) {
        try {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Codex OAuth HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (final IOException e) {
            throw new IllegalStateException("Codex OAuth request failed: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Codex OAuth request", e);
        } catch (final CompletionException e) {
            final var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Codex OAuth request failed: " + cause.getMessage(), cause);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(final String body) {
        final Object o = mapper.fromString(Map.class, body);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String string(final Map<String, Object> map, final String key) {
        final Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static String firstNonBlank(final String preferred, final String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static String urlEncode(final String value) {
        return URLEncoder.encode(value, UTF_8);
    }
}
