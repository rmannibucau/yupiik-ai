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
import io.yupiik.llm.client.codex.model.IdTokenPayload;
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
            // Device grants being unavailable is only one of several reasons this can fail: a
            // workspace policy block (eg. 3p_delegated_access_policy_denied) or a network error must
            // NOT be masked as "device disabled". Surface those as-is; only wrap a plain 4xx (device
            // grants not served for this account/client) into the device-disabled hint.
            if (e.getMessage() == null
                    || e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("policy_denied")
                    || e.getMessage().contains("3p_delegated_access")
                    || !e.getMessage().startsWith("Codex OAuth HTTP")) {
                throw e;
            }
            throw new CodexDeviceAuthDisabledException(
                    "Device-code sign-in is not enabled for this ChatGPT account/client. "
                            + "Use the default browser login (no --device), or enable device sign-in in your ChatGPT security settings.",
                    e);
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
            final String body;
            try {
                body = postJson(
                        issuer() + "/api/accounts/deviceauth/token",
                        mapper.toString(
                                Map.of("device_auth_id", challenge.deviceAuthId(), "user_code", challenge.userCode())),
                        "application/json");
            } catch (final IllegalStateException e) {
                // The device token endpoint answers a pending authorization with a non-2xx the Codex
                // CLI treats as "still pending" (403/404). Only those are tolerated; anything else
                // (revoked device grants, policy blocks, transport) aborts the poll.
                final var message = e.getMessage();
                if (message != null
                        && (message.contains("Codex OAuth HTTP 403") || message.contains("Codex OAuth HTTP 404"))) {
                    sleep(challenge.interval());
                    continue;
                }
                throw e;
            }
            final var map = parse(body);
            final var code = string(map, "authorization_code");
            if (code != null) {
                // the issuer hands back an authorization code plus its PKCE verifier; exchange it for tokens
                final var tokens =
                        exchangeWithVerifier(code, string(map, "code_verifier"), issuer() + "/deviceauth/callback");
                return toStore(tokens);
            }
            // authorization still pending (or a transient non-authorization body): wait and retry
            sleep(challenge.interval());
        }
        throw new IllegalStateException("Device-code authorization timed out");
    }

    private void sleep(final int interval) {
        try {
            Thread.sleep(interval <= 0 ? 5_000 : interval * 1000L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling device authorization", e);
        }
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
                // keep the previous id token when the response omits one (common on refresh)
                firstNonBlank(string(tokens, "id_token"), current.idToken()),
                current.agentIdentity());
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
        final var accessToken = string(tokens, "access_token");
        final var accountId = parseIdTokenClaims(idToken, mapper).accountId();
        return new CodexTokenStore(
                accessToken, string(tokens, "refresh_token"), accountId, expiry(tokens, accessToken), idToken);
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

    /**
     * Builds the expiry (epoch millis) from {@code expires_in}; when absent reads the {@code exp}
     * claim of the (JWT) access token; as a last resort uses a 1h best-effort default.
     */
    private static Long expiry(final Map<String, Object> tokens, final String accessToken) {
        final var expiresIn = tokens.get("expires_in");
        if (expiresIn instanceof Number n) {
            return System.currentTimeMillis() + n.longValue() * 1000;
        }
        final Long accessTokenExp = accessTokenExpMillis(accessToken);
        if (accessTokenExp != null) {
            return accessTokenExp;
        }
        if (accessToken == null) {
            return null;
        }
        // best effort: 1h default for a ChatGPT account access token
        return System.currentTimeMillis() + 3600_000;
    }

    /** Decodes the {@code exp} (epoch seconds) claim of a JWT access token, in epoch millis, else null. */
    private static Long accessTokenExpMillis(final String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            final var parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            final byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            final var json = new String(decoded, UTF_8);
            final var exp =
                    java.util.regex.Pattern.compile("\"exp\"\\s*:\\s*([0-9]+)").matcher(json);
            return exp.find() ? Long.parseLong(exp.group(1)) * 1000 : null;
        } catch (final RuntimeException e) {
            LOGGER.fine(() -> "Cannot decode access token exp claim: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the {@code chatgpt_account_id} from an id_token JWT payload (not validating the
     * signature: this only reads our own token to derive the routing header, as the Codex CLI does).
     *
     * @param idToken the id token, possibly null/blank.
     * @param mapper the JSON mapper (needed to deserialize the typed payload).
     * @return the account id, or null when the token/claim is absent or malformed.
     */
    public static String extractAccountId(final String idToken, final JsonMapper mapper) {
        return parseIdTokenClaims(idToken, mapper).accountId();
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
     * Parses the account claims out of a Codex JWT payload (access or id token) using the typed
     * {@link IdTokenPayload} model, so parsing is robust to formatting and reaches the nested
     * {@code https://api.openai.com/auth} claims. Falls back to the default/active organization id
     * for the account id when the namespaced claims are absent, exactly like the Codex CLI.
     *
     * @param token the access or id token, possibly null/blank.
     * @param mapper the JSON mapper (needed to deserialize the typed payload).
     * @return the parsed claims, never {@code null} (fields may be null).
     */
    public static IdTokenClaims parseIdTokenClaims(final String token, final JsonMapper mapper) {
        if (token == null || token.isBlank()) {
            return new IdTokenClaims(null, null, null, null, false);
        }
        try {
            final var parts = token.split("\\.");
            if (parts.length < 2) {
                return new IdTokenClaims(null, null, null, null, false);
            }
            final byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            final var payload = mapper.fromString(IdTokenPayload.class, new String(decoded, UTF_8));
            if (payload == null) {
                return new IdTokenClaims(null, null, null, null, false);
            }
            final var auth = payload.auth();
            final String accountId = auth != null
                            && auth.accountId() != null
                            && !auth.accountId().isBlank()
                    ? auth.accountId()
                    : firstNonBlank(payload.flatAuthAccountId(), payload.flatAccountId());
            final String accountIdResolved =
                    accountId != null && !accountId.isBlank() ? accountId : defaultOrganizationId(payload);
            final String userId = auth != null && auth.userId() != null ? auth.userId() : null;
            final String plan = auth != null && auth.planType() != null ? auth.planType() : null;
            final boolean fedramp = auth != null && auth.chatgptAccountIsFedramp();
            final String email = payload.email() != null ? payload.email() : payload.preferredUsername();
            return new IdTokenClaims(accountIdResolved, userId, email, plan, fedramp);
        } catch (final RuntimeException e) {
            LOGGER.fine(() -> "Cannot decode id_token claims: " + e.getMessage());
            return new IdTokenClaims(null, null, null, null, false);
        }
    }

    /** The selected/default organization id, falling back to the first, else null. */
    private static String defaultOrganizationId(final IdTokenPayload payload) {
        if (payload.organizations() == null || payload.organizations().isEmpty()) {
            return null;
        }
        return payload.organizations().stream()
                .filter(org -> org.id() != null && !org.id().isBlank())
                .filter(org -> org.isDefault() || org.isActive())
                .findFirst()
                .or(() -> payload.organizations().stream()
                        .filter(org -> org.id() != null && !org.id().isBlank())
                        .findFirst())
                .map(IdTokenPayload.Organization::id)
                .orElse(null);
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
                // no request/response timeout here on purpose: the one-time code is consumed by the
                // first POST, so a slow-but-successful response must not be aborted (upstream codex
                // applies a connect timeout only, see exchange_code_for_tokens)
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
            throw new IllegalStateException("Codex OAuth request failed: " + describe(e, request), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Codex OAuth request", e);
        } catch (final CompletionException e) {
            final var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Codex OAuth request failed: " + describe(cause, request), cause);
        }
    }

    /**
     * A diagnosable description of a transport failure. The JDK client (and some of the wrappers
     * around it) throw IOExceptions with a {@code null} message: rendering {@code null} in the
     * user-facing error ("Codex OAuth request failed: null") tells nothing. Instead surface the
     * exception class and the sanitized target (no query, no fragments: the authorize URL carries
     * the PKCE state), mirroring the upstream codex diagnostics.
     */
    private static String describe(final Throwable error, final HttpRequest request) {
        final var target = sanitize(request == null ? null : request.uri());
        if (error == null) {
            return "unknown transport error calling " + target;
        }
        final var message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() + " calling " + target
                : message + " (" + error.getClass().getSimpleName() + " calling " + target + ")";
    }

    /** The URL without its query/fragment (those carry the PKCE state/challenge, never log them). */
    private static String sanitize(final URI uri) {
        if (uri == null) {
            return "<unknown>";
        }
        final var raw = uri.toString();
        final int query = raw.indexOf('?');
        final int fragment = raw.indexOf('#');
        final int cut = query >= 0 && (fragment < 0 || query < fragment) ? query : fragment;
        return cut >= 0 ? raw.substring(0, cut) : raw;
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
