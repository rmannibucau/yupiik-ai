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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.llm.client.codex.CodexAgentIdentityClient;
import io.yupiik.llm.client.codex.CodexAgentIdentityConfig;
import io.yupiik.llm.client.codex.CodexOAuthClient;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.AgentIdentityRecord;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns the Codex login flow and token persistence, shared by the {@code /codex login} slash command
 * and the frontends' auto-login-on-first-use. Tokens are written to
 * {@code <storageHome>/providers/<provider>/codex.auth.json}, the exact location {@link
 * CodexAuthTemplateHelper} reads for {@code {{codexAuth}}}, so a session created here is picked up
 * automatically on the next request.
 */
@ApplicationScoped
public class CodexLoginService {

    private final JsonMapper mapper;
    private final ExtendedHttpClient httpClient;
    private final Path providersHome;

    public CodexLoginService(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        final String home = configuration == null ? null : configuration.resolveStorageHome();
        final String resolvedHome = home == null || home.isBlank() ? "~/.yupiik/coding-agent" : home;
        this.providersHome = Path.of(resolveHome(resolvedHome)).resolve("providers");
    }

    /** Expands a leading {@code ~} to the user home directory; POSIX-style, best effort. */
    private static String resolveHome(final String path) {
        if (path == null || path.isBlank() || !path.startsWith("~/")) {
            return path;
        }
        final String userHome = System.getProperty("user.home");
        return userHome == null || userHome.isBlank() ? path : userHome + path.substring(1);
    }

    /**
     * Runs the interactive ChatGPT/Codex login for a provider and persists the session. The browser
     * PKCE flow is the default; {@code device} opts into the device-code flow (the CLI's
     * {@code --device-auth} path). Blocks until the user authorizes (or {@code cancelled} returns
     * true).
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open; the frontend decides how to
     *     surface it (print, TUI modal, or open in a web tab).
     * @param cancelled cancellation probe.
     * @return the authenticated session.
     */
    public CodexTokenStore signIn(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled) {
        return signIn(provider, device, urlSink, cancelled, CodexOAuthConfig.DEFAULT);
    }

    /**
     * Package-private variant with a tunable OAuth configuration, mainly exposed for tests that point
     * at a mock issuer instead of {@link CodexOAuthConfig#DEFAULT}. {@link #signIn(String, boolean,
     * Consumer, Predicate)} delegates here with the default configuration; the login logic, provenance
     * recording and the browser-only api-key exchange are otherwise identical.
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open.
     * @param cancelled cancellation probe.
     * @param config the tunable OAuth settings (issuer, client id, scopes, callback port).
     * @return the authenticated session.
     */
    CodexTokenStore signIn(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled,
            final CodexOAuthConfig config) {
        final var oauth = new CodexOAuthClient(mapper, httpClient, config);
        CodexTokenStore store;
        if (device) {
            final var challenge = oauth.deviceCode();
            if (urlSink != null) {
                urlSink.accept(challenge.prompt());
            }
            store = oauth.poll(challenge, cancelled);
        } else {
            store = oauth.browserFlow(urlSink, cancelled);
        }
        // record the OAuth authority (provenance) at login, in BOTH flows (mirrors upstream
        // persist_tokens_async which always writes issuer/client_id; the device flow only omits the
        // api key below). Do this BEFORE obtainApiKey so the id token is read from the annotated store.
        store = store.withProvenance(config.issuer(), config.clientId());
        if (!device) {
            // exchange the id token for an API-key token (upstream obtain_api_key); only the browser
            // flow does this (the device flow passes no key upstream), and the failure is tolerated.
            String apiKey = null;
            try {
                apiKey = oauth.obtainApiKey(store.idToken());
            } catch (final RuntimeException ignored) {
                // non-fatal: the login still succeeds without the api-key token (upstream .ok())
                apiKey = null;
            }
            if (apiKey != null && !apiKey.isBlank()) {
                store = store.withApiKey(apiKey);
            }
        }
        save(provider, store);
        return store;
    }

    /**
     * Runs the ChatGPT/Codex login for a provider and additionally enrolls the account in the
     * enterprise Agent Identity flow ({@code /codex login <provider> --enterprise}). This is the path
     * for managed/company (workspace) plans that the plain OAuth browser/device flows cannot reach.
     *
     * <p>It reuses {@link #signIn} to obtain the OAuth session, then, with that access token as the
     * anchor, registers a fresh Ed25519 device identity with the auth API
     * ({@code /v1/agent/register}), registers a run task ({@code /v1/agent/{id}/task/register}), and
     * persists the resulting {@link AgentIdentityRecord} next to the OAuth tokens so requests are
     * authenticated with a signed {@code AgentAssertion} header. This is the eager/forced variant: the
     * same enrollment happens lazily on first {@code {{codexAgentAssertion}} use for a plain session.</p>
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open.
     * @param cancelled cancellation probe.
     * @return the authenticated session carrying the Agent Identity registration.
     */
    public CodexTokenStore signInAgent(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled) {
        return signInAgent(provider, device, urlSink, cancelled, null);
    }

    /**
     * Like {@link #signInAgent(String, boolean, Consumer, Predicate)}, but pins the enrollment to a
     * specific workspace/organization id. When {@code forcedWorkspaceId} is non-blank it must belong
     * to the signed-in account (the id token lists the organizations): the enrollment uses that id as
     * the account id, and fails loudly if the account is not a member - so a user in several
     * workspaces can pick the enterprise one instead of silently using the default. When it is blank
     * (the common case), enrollment uses the account's default organization, matching codex.
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open.
     * @param cancelled cancellation probe.
     * @param forcedWorkspaceId an organization/workspace id to pin, or null/blank for the default.
     * @return the authenticated session carrying the Agent Identity registration.
     */
    public CodexTokenStore signInAgent(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled,
            final String forcedWorkspaceId) {
        final CodexTokenStore oauthSession = signIn(provider, device, urlSink, cancelled);
        return enrollNow(provider, oauthSession, forcedWorkspaceId);
    }

    /**
     * Eagerly enrolls an existing OAuth session in the enterprise Agent Identity flow and persists the
     * result. Used by the forced {@code /codex login --enterprise} path; the shared enrollment logic
     * lives on {@link CodexAgentIdentityClient#enroll}. {@code forcedWorkspaceId} is optional - when
     * blank the enrollment uses the account's default organization.
     *
     * @param provider the provider id.
     * @param oauthSession the already-signed-in OAuth session.
     * @param forcedWorkspaceId an organization/workspace id to pin, or null/blank for the default.
     * @return the session carrying the Agent Identity registration.
     */
    public CodexTokenStore enrollNow(
            final String provider, final CodexTokenStore oauthSession, final String forcedWorkspaceId) {
        if (oauthSession.accessToken() == null || oauthSession.accessToken().isBlank()) {
            throw new IllegalStateException("ChatGPT/Codex login succeeded but produced no access token");
        }
        final var agentIdentity = new CodexAgentIdentityClient(mapper, httpClient, CodexAgentIdentityConfig.DEFAULT);
        final var record = agentIdentity.enroll(
                oauthSession.accessToken(), oauthSession.idToken(), oauthSession.accountId(), forcedWorkspaceId);
        final CodexTokenStore enrolled = oauthSession.withAgentIdentity(record);
        save(provider, enrolled);
        return enrolled;
    }

    /** The token store for a provider, or {@code null} when no session exists yet. */
    public CodexTokenStore load(final String provider) {
        final Path file = codexAuthFile(provider);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.fromBytes(CodexTokenStore.class, Files.readAllBytes(file));
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read the Codex auth file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Persists a session atomically (temp file + move, owner-only perms). */
    public void save(final String provider, final CodexTokenStore store) {
        final var file = codexAuthFile(provider);
        try {
            Files.createDirectories(file.getParent());
            final var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.toBytes(store));
            restrictToOwner(tmp);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot persist the Codex session to " + file + ": " + e.getMessage(), e);
        }
    }

    /** Restricts the auth file to owner-only reads/writes (POSIX 0600); best effort on non-POSIX. */
    private static void restrictToOwner(final Path path) {
        try {
            Files.setPosixFilePermissions(
                    path,
                    java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (final UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem: no-op
        }
    }

    private Path codexAuthFile(final String provider) {
        return providersHome.resolve(provider).resolve("codex.auth.json");
    }
}
