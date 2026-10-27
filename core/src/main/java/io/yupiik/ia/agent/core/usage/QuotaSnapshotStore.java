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
package io.yupiik.ia.agent.core.usage;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-session cache of the provider account usage snapshot. Keyed by session id so every tab (TUI,
 * web, CLI) keeps its own quota independent of the others - and, critically, of the session's own
 * provider (each tab can run on its own provider). The quota refresh runs here, entirely in core:
 * the Web UI only consumes the quota fields broadcast through the regular backend event.
 *
 * <p>Refresh cadence: fetched at provider attach, then at most once per {@link #TTL_MILLIS} (~5 min)
 * unless forced (a {@code /usage} slash command always forces a refresh). The access token is
 * resolved through {@link CodexAuthTemplateHelper} and never leaves yca.</p>
 */
@ApplicationScoped
public class QuotaSnapshotStore {
    private static final Logger LOGGER = Logger.getLogger(QuotaSnapshotStore.class.getName());

    /** Refresh TTL: at most one quota fetch every 5 minutes per session unless forced. */
    public static final long TTL_MILLIS = 5L * 60L * 1000L;

    private final CodexUsageClient client;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final ConcurrentHashMap<String, SessionQuota> sessions = new ConcurrentHashMap<>();

    /** A session-scoped quota: the provider it runs on and the cached snapshot + fetch time. */
    private static final class SessionQuota {
        volatile ChatSettings settings;
        volatile ProviderConfiguration provider;
        volatile CodexUsageSnapshot snapshot = CodexUsageSnapshot.empty();
        volatile long lastFetchMillis;

        boolean stale(final long now) {
            return snapshot == null || now - lastFetchMillis >= TTL_MILLIS;
        }
    }

    public QuotaSnapshotStore(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodexAuthTemplateHelper codexAuth,
            final ProviderManager providerManager,
            final Clock clock) {
        this.client = new CodexUsageClient(mapper, httpClient, codexAuth);
        this.providerManager = providerManager;
        this.clock = clock;
        // precompute nothing; provider/root resolution is lazy per session
    }

    protected QuotaSnapshotStore() {
        this.client = null;
        this.providerManager = null;
        this.clock = null;
    }

    /** Records the session's provider selection so quota follows the tab's own provider. */
    public void attach(final String sessionId, final ChatSettings settings) {
        final SessionQuota quota = sessions.computeIfAbsent(sessionId, s -> new SessionQuota());
        final ProviderConfiguration provider = resolveProvider(settings);
        quota.settings = settings;
        quota.provider = provider;
        if (usageEnabled(provider)) {
            refreshIfStale(sessionId, quota, true);
        }
    }

    /**
     * Returns the current quota snapshot for a session, refreshing it when the TTL elapsed or when
     * {@code force} is set (a {@code /usage} call). Never throws for a quota-less/unauthenticated
     * provider: it returns an empty snapshot carrying the capability state so UIs can hide the gauge.
     *
     * @param sessionId the session to read.
     * @param force whether to bypass the TTL and re-fetch now.
     * @return the snapshot view (never {@code null}).
     */
    public QuotaView snapshot(final String sessionId, final boolean force) {
        final SessionQuota quota = sessions.get(sessionId);
        if (quota == null) {
            return new QuotaView(false, false, CodexUsageSnapshot.empty());
        }
        final var provider = quota.provider;
        final boolean enabled = usageEnabled(provider);
        final boolean supported = enabled && isCodexProvider(provider);
        if (supported && (force || quota.stale(clock.millis()))) {
            refreshIfStale(sessionId, quota, force);
        }
        return new QuotaView(enabled, supported, quota.snapshot == null ? CodexUsageSnapshot.empty() : quota.snapshot);
    }

    /** Forces a refresh and returns the fresh view (used by the /usage command). */
    public QuotaView refresh(final String sessionId) {
        final SessionQuota quota = sessions.get(sessionId);
        if (quota == null) {
            return new QuotaView(false, false, CodexUsageSnapshot.empty());
        }
        return snapshot(sessionId, true);
    }

    /** Releases a session's quota entry (called when the session closes). */
    public void close(final String sessionId) {
        sessions.remove(sessionId);
    }

    private void refreshIfStale(final String sessionId, final SessionQuota quota, final boolean force) {
        final var provider = quota.provider;
        if (!usageEnabled(provider) || !isCodexProvider(provider)) {
            return;
        }
        final String root = codexRoot(provider);
        if (root == null || root.isBlank()) {
            return;
        }
        try {
            final CodexUsageSnapshot fresh = client.fetch(root, providerId(provider));
            quota.snapshot = fresh;
            quota.lastFetchMillis = clock.millis();
        } catch (final CodexUsageClient.NeedsLoginException e) {
            LOGGER.log(Level.FINE, () -> "Codex usage not refreshed for session " + sessionId + ": " + e.getMessage());
            quota.lastFetchMillis = clock.millis(); // do not hammer the backend while unauthenticated
        } catch (final RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "Could not refresh Codex usage for session " + sessionId + ": " + e.getMessage());
            quota.lastFetchMillis = clock.millis(); // back off on transient failures too
        }
    }

    private ProviderConfiguration resolveProvider(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        // the session's own provider (per-tab), never the process-global default
        final String providerId = settings == null ? null : settings.providerId();
        return providerId == null || providerId.isBlank()
                ? providerManager.current()
                : providerManager.provider(providerId);
    }

    private static boolean usageEnabled(final ProviderConfiguration provider) {
        return provider != null && provider.usageEnabled();
    }

    private static boolean isCodexProvider(final ProviderConfiguration provider) {
        if (provider == null || provider.baseUrl() == null) {
            return false;
        }
        final String url = provider.baseUrl().toLowerCase(Locale.ROOT);
        return url.contains("/codex");
    }

    private static String providerId(final ProviderConfiguration provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            return "default";
        }
        return provider.id();
    }

    /**
     * Derives the Codex API root from the provider base URL: strip any {@code /codex} route suffix
     * (the Responses chat path) to reach the root that serves {@code /wham/usage}. Eg.
     * {@code https://chatgpt.com/backend-api/codex} -> {@code https://chatgpt.com/backend-api}.
     */
    static String codexRoot(final ProviderConfiguration provider) {
        if (provider == null || provider.baseUrl() == null) {
            return null;
        }
        String url = provider.baseUrl();
        final int codex = url.toLowerCase(Locale.ROOT).indexOf("/codex");
        if (codex >= 0) {
            url = url.substring(0, codex);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.isEmpty() ? null : url;
    }

    /** The quota view a session consumes: capability state plus the underlying snapshot. */
    public record QuotaView(boolean enabled, boolean supported, CodexUsageSnapshot snapshot) {

        /** The primary rate-limit window (the one that drives the sidebar gauge), or {@code null}. */
        public CodexUsageSnapshot.RateLimit primaryWindow() {
            if (snapshot == null || snapshot.rateLimits() == null) {
                return null;
            }
            for (final var limit : snapshot.rateLimits()) {
                if (CodexUsageSnapshot.RateLimit.PRIMARY.equals(limit.kind()) && limit.usedPercent() != null) {
                    return limit;
                }
            }
            return null;
        }
    }

    /** Conceptual wiring hook for the session id used by tests to pre-seed entries. visible for tests. */
    Map<String, SessionQuota> sessions() {
        return sessions;
    }
}
