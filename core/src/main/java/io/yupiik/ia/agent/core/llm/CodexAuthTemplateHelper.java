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
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.llm.client.codex.CodexAgentIdentityClient;
import io.yupiik.llm.client.codex.CodexAgentIdentityConfig;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import io.yupiik.llm.client.codex.CodexOAuthClient;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Provides the {@code codexAuth} handlebars helpers used by Codex-flavored provider {@code
 * dynamicHeaders}/{@code headers} entries:
 *
 * <ul>
 *   <li>{@code {{codexAuth "provider"}}} - the OAuth access token; typically {@code "authorization":
 *       "Bearer {{codexAuth \"mycodex\"}}"} for a regular (non-enterprise) session.</li>
 *   <li>{@code {{codexAgentAssertion "provider"}}} - the signed {@code AgentAssertion {...}} header
 *       for an enterprise/Agent Identity session ({@code "authorization": "{{codexAgentAssertion
 *       \"default\""}}).</li>
 *   <li>{@code {{codexAccountId "provider"}}} - the account id for the {@code ChatGPT-Account-ID}
 *       header, derived from the session (no manual configuration).</li>
 * </ul>
 *
 * <p>The token store is cached under {@code <storageHome>/providers/&lt;providerName&gt;/codex.auth.json}
 * (mirroring the Codex CLI {@code auth.json} {@code tokens} shape). Access tokens are short lived: the
 * helpers refresh lazily — only on render, i.e. only when a request for that provider is actually made
 * — and persist the possibly-rotated refresh token (preserving any attached Agent Identity
 * registration). If no session exists yet (no prior {@code codex login}), the helpers fail loudly so
 * the caller is told to log in first. A single-flight lock per provider prevents concurrent requests
 * from triggering overlapping refreshes that could race the refresh-token rotation.</p>
 */
@ApplicationScoped
public class CodexAuthTemplateHelper {

    private static final Logger LOGGER = Logger.getLogger(CodexAuthTemplateHelper.class.getName());

    private final JsonMapper mapper;
    private final ExtendedHttpClient httpClient;
    private final Path providersHome;
    private volatile CodexOAuthClient oauth;
    private volatile CodexAgentIdentityClient agentIdentity;
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();

    public CodexAuthTemplateHelper(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.oauth = null; // built lazily on first use (or via setOAuthConfig in tests)
        this.agentIdentity = null; // built lazily on first use (or via setAgentIdentityConfig in tests)
        final String home = configuration == null ? null : configuration.resolveStorageHome();
        this.providersHome = Path.of(home == null || home.isBlank() ? "~/.yupiik/coding-agent" : home)
                .resolve("providers");
    }

    /**
     * Replaces the OAuth client with one bound to a different configuration. Mainly for tests that
     * point at a mock issuer; the CDI path keeps the {@link CodexOAuthConfig#DEFAULT} client.
     *
     * @param config the tunable Codex OAuth settings.
     */
    public void setOAuthConfig(final CodexOAuthConfig config) {
        this.oauth = new CodexOAuthClient(mapper, httpClient, config);
    }

    /**
     * Replaces the Agent Identity client with one bound to a different configuration. Mainly for
     * tests that point at a mock auth API; the CDI path keeps {@link CodexAgentIdentityConfig#DEFAULT}.
     *
     * @param config the tunable Agent Identity settings.
     */
    public void setAgentIdentityConfig(final CodexAgentIdentityConfig config) {
        this.agentIdentity = new CodexAgentIdentityClient(mapper, httpClient, config);
    }

    private CodexOAuthClient oauth() {
        var client = this.oauth;
        if (client == null) {
            synchronized (this) {
                client = this.oauth;
                if (client == null) {
                    client = new CodexOAuthClient(mapper, httpClient, CodexOAuthConfig.DEFAULT);
                    this.oauth = client;
                }
            }
        }
        return client;
    }

    private CodexAgentIdentityClient agentIdentity() {
        var client = this.agentIdentity;
        if (client == null) {
            synchronized (this) {
                client = this.agentIdentity;
                if (client == null) {
                    client = new CodexAgentIdentityClient(mapper, httpClient, CodexAgentIdentityConfig.DEFAULT);
                    this.agentIdentity = client;
                }
            }
        }
        return client;
    }

    /**
     * The {@code codexAuth} helper: resolves the access token for {@code providerName}, refreshing it
     * lazily if expired. When the session is an Agent Identity one, returns the access token anyway
     * (it is still the OAuth anchor); most codex provider headers use {@link #codexAgentAssertion()}
     * for that case instead.
     *
     * @return the helper function.
     */
    public Function<HelperContext, Object> codexAuth() {
        return this::apply;
    }

    /**
     * The {@code codexAgentAssertion} helper: resolves the signed {@code AgentAssertion {...}} header
     * value for {@code providerName} when the session carries an Agent Identity registration
     * (enterprise/company plans). Use it as the {@code authorization} value in a codex provider's
     * {@code dynamicHeaders}, e.g.:
     * {@code "authorization": "{{codexAgentAssertion \"default\""}}.
     *
     * <p>Only valid for Agent Identity sessions: the assertion needs a registered run task, so the
     * helper fails loudly (and tells the user to run {@code /codex login --enterprise}) when the
     * session is only a regular OAuth one.</p>
     *
     * @return the helper function.
     */
    public Function<HelperContext, Object> codexAgentAssertion() {
        return context -> {
            final String providerName = providerName(context, "codexAgentAssertion");
            final CodexTokenStore store = load(providerName);
            if (store == null) {
                throw new CodexNotAuthenticatedException(providerName);
            }
            final CodexTokenStore resolved = ensureFresh(providerName, store);
            if (resolved.agentIdentity() == null) {
                throw new IllegalStateException("provider '" + providerName + "' is not enrolled in Agent Identity; "
                        + "run /codex login <provider> --enterprise");
            }
            if (resolved.agentIdentity().needsTaskRegistration()) {
                throw new IllegalStateException("provider '" + providerName
                        + "' Agent Identity has no run task; re-enroll with " + "/codex login <provider> --enterprise");
            }
            return new SafeString(agentIdentity().assertionHeader(resolved.agentIdentity()));
        };
    }

    /**
     * The {@code codexAccountId} helper: resolves the account id for {@code providerName} (from the
     * Agent Identity record when present, else from the OAuth session). Use it as the
     * {@code ChatGPT-Account-ID} header value so the account id is always correct without manual
     * configuration.
     *
     * @return the helper function.
     */
    public Function<HelperContext, Object> codexAccountId() {
        return context -> {
            final String providerName = providerName(context, "codexAccountId");
            final CodexTokenStore store = load(providerName);
            if (store == null) {
                throw new CodexNotAuthenticatedException(providerName);
            }
            final CodexTokenStore resolved = ensureFresh(providerName, store);
            final String accountId = resolved.agentIdentity() != null
                            && resolved.agentIdentity().accountId() != null
                            && !resolved.agentIdentity().accountId().isBlank()
                    ? resolved.agentIdentity().accountId()
                    : resolved.accountId();
            return new SafeString(accountId == null ? "" : accountId);
        };
    }

    private static String providerName(final HelperContext context, final String helper) {
        final var args = context.args();
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException(helper + " expects the provider name as its single argument");
        }
        final var providerName = String.valueOf(args.get(0));
        if (providerName.isBlank()) {
            throw new IllegalArgumentException(helper + " provider name must not be blank");
        }
        return providerName;
    }

    private Object apply(final HelperContext context) {
        final var providerName = providerName(context, "codexAuth");
        final CodexTokenStore store = load(providerName);
        if (store == null) {
            throw new CodexNotAuthenticatedException(providerName);
        }
        final CodexTokenStore resolved = ensureFresh(providerName, store);
        return new SafeString(resolved.accessToken() == null ? "" : resolved.accessToken());
    }

    private CodexTokenStore ensureFresh(final String providerName, final CodexTokenStore store) {
        final long now = System.currentTimeMillis();
        if (!store.expired(now)) {
            return store;
        }
        if (!store.refreshable()) {
            LOGGER.warning(
                    "Codex access token for provider '" + providerName + "' has no refresh token; re-login needed");
            return store;
        }
        // single-flight per provider so a burst of concurrent renders triggers at most one refresh
        final Object lock = refreshLocks.computeIfAbsent(providerName, k -> new Object());
        synchronized (lock) {
            // re-check after acquiring the lock: another thread may have refreshed already
            final CodexTokenStore current = load(providerName);
            if (current != null && !current.expired(System.currentTimeMillis())) {
                return current;
            }
            final CodexTokenStore refreshed = oauth().refresh(current == null ? store : current);
            if (refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
                throw new IllegalStateException(
                        "Codex refresh for provider '" + providerName + "' returned no access token");
            }
            // the OAuth refresh drops the (agent identity) extras; re-attach them so a refreshed
            // enterprise session keeps its identity registration across token rotation
            final CodexTokenStore kept =
                    refreshed.withAgentIdentity((current == null ? store : current).agentIdentity());
            save(providerName, kept);
            return kept;
        }
    }

    private CodexTokenStore load(final String providerName) {
        final Path file = codexAuthFile(providerName);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.fromBytes(CodexTokenStore.class, Files.readAllBytes(file));
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read the Codex auth file " + file + ": " + e.getMessage(), e);
        }
    }

    private void save(final String providerName, final CodexTokenStore store) {
        final Path file = codexAuthFile(providerName);
        try {
            Files.createDirectories(file.getParent());
            // write atomically: a temp file in the same dir followed by a move avoids a reader seeing a partial JSON
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.toBytes(store));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException moveFailure) {
                Files.deleteIfExists(tmp);
                throw moveFailure;
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot write the Codex auth file " + file + ": " + e.getMessage(), e);
        }
    }

    private Path codexAuthFile(final String providerName) {
        return providersHome.resolve(providerName).resolve("codex.auth.json");
    }
}
