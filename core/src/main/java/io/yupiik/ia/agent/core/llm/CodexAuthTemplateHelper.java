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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
    private final ConcurrentHashMap<String, Lock> refreshLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CodexTokenStore> authCache = new ConcurrentHashMap<>();

    public CodexAuthTemplateHelper(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.oauth = null; // built lazily on first use (or via setOAuthConfig in tests)
        this.agentIdentity = null; // built lazily on first use (or via setAgentIdentityConfig in tests)
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
     * {@code "authorization": "{{codexAgentAssertion \"default\"}}"}.
     *
     * <p>The assertion is signed with the stored Ed25519 key and does not need the OAuth access
     * token, so this helper never triggers a refresh and keeps working even when the refresh token
     * has been revoked or expired by an enterprise session-lifetime policy. If the run task id is
     * missing or stale it is lazily re-registered with the stored key, so an expired task id does
     * not force a re-login.</p>
     *
     * <p>Only valid for Agent Identity sessions: the helper fails loudly (and tells the user to run
     * {@code /codex login --enterprise}) when the session is only a regular OAuth one.</p>
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
            final var record = ensureEnrolledTask(providerName, store);
            return new SafeString(agentIdentity().assertionHeader(record));
        };
    }

    /**
     * The {@code codexAccountId} helper: resolves the account id for {@code providerName} (from the
     * Agent Identity record when present, else from the OAuth session). Use it as the
     * {@code ChatGPT-Account-ID} header value so the account id is always correct without manual
     * configuration. Never triggers an OAuth refresh; the id is read from the stored session.
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
            final String accountId = store.agentIdentity() != null
                            && store.agentIdentity().accountId() != null
                            && !store.agentIdentity().accountId().isBlank()
                    ? store.agentIdentity().accountId()
                    : store.accountId();
            return new SafeString(accountId == null ? "" : accountId);
        };
    }

    /**
     * Returns the Agent Identity record for {@code providerName}, lazily re-registering the run task
     * (from the stored key + agent runtime) when the recorded task id is missing or stale. Uses the
     * stored state - no OAuth refresh - so a revoked/expired refresh token cannot block the assertion.
     */
    private io.yupiik.llm.client.codex.model.AgentIdentityRecord ensureEnrolledTask(
            final String providerName, final CodexTokenStore store) {
        final var record = store.agentIdentity();
        if (record == null) {
            throw new IllegalStateException("provider '" + providerName + "' is not enrolled in Agent Identity; "
                    + "run /codex login <provider> --enterprise");
        }
        if (!record.needsTaskRegistration()) {
            return record;
        }
        final Lock lock = refreshLocks.computeIfAbsent(providerName, k -> new ReentrantLock());
        lock.lock();
        try {
            final CodexTokenStore current = load(providerName);
            final var fresh = current == null ? store : current;
            final var freshRecord = fresh.agentIdentity();
            if (freshRecord != null && !freshRecord.needsTaskRegistration()) {
                return freshRecord;
            }
            final var registered = agentIdentity().registerTask(freshRecord == null ? record : freshRecord);
            final CodexTokenStore updated = fresh.withAgentIdentity(registered);
            save(providerName, updated);
            return registered;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The {@code codexFedramp} helper: resolves the {@code X-OpenAI-Fedramp} header value for
     * {@code providerName} - {@code true} when the enrolled Agent Identity is a FedRAMP account,
     * otherwise an empty string (blank renders are skipped by the headers customizer). Use it so
     * FedRAMP accounts always send the routing header without manual configuration:
     * {@code "X-OpenAI-Fedramp": "{{codexFedramp \"codex\"}}"}.
     *
     * @return the helper function.
     */
    public Function<HelperContext, Object> codexFedramp() {
        return context -> {
            final String providerName = providerName(context, "codexFedramp");
            final CodexTokenStore store = load(providerName);
            if (store == null) {
                throw new CodexNotAuthenticatedException(providerName);
            }
            final boolean fedramp =
                    store.agentIdentity() != null && store.agentIdentity().chatgptAccountIsFedramp();
            return new SafeString(fedramp ? "true" : "");
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
        final Lock lock = refreshLocks.computeIfAbsent(providerName, k -> new ReentrantLock());
        lock.lock();
        try {
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
            // refresh() already carries the Agent Identity and the previous id token through
            save(providerName, refreshed);
            return refreshed;
        } finally {
            lock.unlock();
        }
    }

    private CodexTokenStore load(final String providerName) {
        final CodexTokenStore cached = authCache.get(providerName);
        if (cached != null) {
            return cached;
        }
        final Path file = codexAuthFile(providerName);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            final CodexTokenStore store = mapper.fromBytes(CodexTokenStore.class, Files.readAllBytes(file));
            authCache.put(providerName, store);
            return store;
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
            restrictToOwner(tmp);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException moveFailure) {
                Files.deleteIfExists(tmp);
                throw moveFailure;
            }
            authCache.put(providerName, store);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot write the Codex auth file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Restricts {@code codex.auth.json} to owner-only reads/writes (POSIX 0600): the file holds the
     * refresh token and the Agent Identity Ed25519 private key. Standard only, not enforced on FAT/exFAT.
     */
    private static void restrictToOwner(final Path path) {
        try {
            final java.nio.file.attribute.PosixFilePermission[] owner = {
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            };
            Files.setPosixFilePermissions(path, java.util.Set.of(owner));
        } catch (final UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem: best effort
        }
    }

    private Path codexAuthFile(final String providerName) {
        return providersHome.resolve(providerName).resolve("codex.auth.json");
    }
}
