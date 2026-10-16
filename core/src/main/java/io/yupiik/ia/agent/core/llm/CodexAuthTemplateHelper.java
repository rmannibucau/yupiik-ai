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
 * Provides the {@code codexAuth} handlebars helper used by a Codex-flavored provider {@code
 * dynamicHeaders} entry: {@code "authorization": "Bearer {{codexAuth \"mycodex\"}}"}. The helper
 * resolves the session access token for the named provider and returns it as a raw {@link
 * SafeString}.
 *
 * <p>The token store is cached under {@code <storageHome>/providers/&lt;providerName&gt;/codex.auth.json}
 * (mirroring the Codex CLI {@code auth.json} {@code tokens} shape). Access tokens are short lived: the
 * helper refreshes lazily — only on {@code {{codexAuth}}} render, i.e. only when a request for that
 * provider is actually made — and persists the possibly-rotated refresh token. If no session exists
 * yet (no prior {@code codex login}), the helper fails loudly so the caller is told to log in first.
 * A single-flight lock per provider prevents concurrent requests from triggering overlapping refreshes
 * that could race the refresh-token rotation.</p>
 */
@ApplicationScoped
public class CodexAuthTemplateHelper {

    private static final Logger LOGGER = Logger.getLogger(CodexAuthTemplateHelper.class.getName());

    private final JsonMapper mapper;
    private final ExtendedHttpClient httpClient;
    private final Path providersHome;
    private volatile CodexOAuthClient oauth;
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();

    public CodexAuthTemplateHelper(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.oauth = null; // built lazily on first use (or via setOAuthConfig in tests)
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

    /**
     * The {@code codexAuth} helper: resolves an access token for {@code providerName}, refreshing it
     * lazily if expired.
     *
     * @return the helper function.
     */
    public Function<HelperContext, Object> codexAuth() {
        return this::apply;
    }

    private Object apply(final HelperContext context) {
        final var args = context.args();
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("codexAuth expects the provider name as its single argument");
        }
        final var providerName = String.valueOf(args.get(0));
        if (providerName.isBlank()) {
            throw new IllegalArgumentException("codexAuth provider name must not be blank");
        }
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
            save(providerName, refreshed);
            return refreshed;
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
