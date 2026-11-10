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
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.llm.client.spi.ConcurrencyGate;
import io.yupiik.llm.client.spi.InMemoryGate;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns one concurrency gate per provider for the whole process.
 *
 * <p>Two independent client instances (the main {@link ChatClient} and the dedicated one the
 * {@code /btw} side questions build) must share the budget of a provider, otherwise each of them gets
 * its own copy of the limit and the provider sees twice the configured concurrency. The gate also has
 * to outlive the clients: a stream leased by a client which is being recycled still releases its slot
 * into the gate it captured, and a fresh gate would immediately try to re-lease the same slot files.
 * Hence this registry owns them and disposes of them once, at shutdown.</p>
 *
 * <p>The gate is a per-process in-memory budget unless the provider opts into
 * {@code capabilities.concurrency.useFileLock} <em>and</em> a strictly positive bound is configured
 * (the provider's own {@code maxConcurrent}, or the global {@code yca.llm.maxConcurrent} when the
 * provider defers to it): only then are file leases worth their cost (and their directory), since a
 * learned limit (a {@code 429} adaptation) is not a licence to create files for a provider the user
 * did not opt in. It also means {@code useFileLock} must be paired with a configured positive
 * {@code maxConcurrent} somewhere - provider or global.</p>
 */
@ApplicationScoped
public class ProviderConcurrencyGates {
    private static final Logger LOGGER = Logger.getLogger(ProviderConcurrencyGates.class.getName());

    private final CodingAgentConfiguration configuration;
    private final Map<String, ConcurrencyGate> gates = new ConcurrentHashMap<>();

    public ProviderConcurrencyGates(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * The gate of a provider, created on first use.
     *
     * @param provider the provider, may be {@code null} (falls back to the default id).
     * @param bound the effective capacity to use when the gate is created.
     * @return the shared gate, never {@code null}.
     */
    public ConcurrencyGate gate(final ProviderConfiguration provider, final int bound) {
        final var id =
                provider == null || provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
        final var capacity = Math.max(1, bound);
        return gates.computeIfAbsent(id, key -> create(provider, key, capacity));
    }

    @Destroy
    public void close() {
        gates.values().forEach(gate -> {
            if (gate instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (final Exception e) {
                    LOGGER.log(Level.FINE, e, () -> "cannot close a provider concurrency gate");
                }
            }
        });
        gates.clear();
    }

    private ConcurrencyGate create(final ProviderConfiguration provider, final String id, final int capacity) {
        // the provider defers to the global yca.llm.maxConcurrent for 0/negative values: use the
        // same effective bound here, so a global cap + useFileLock activates the cross-process gate
        final var effectiveMax = provider == null || provider.maxConcurrent() <= 0
                ? configuration.llm().maxConcurrent()
                : provider.maxConcurrent();
        if (provider != null
                && effectiveMax > 0
                && provider.capabilities() != null
                && provider.capabilities().concurrency() != null
                && provider.capabilities().concurrency().useFileLock()) {
            return new FileLeaseConcurrencyGate(
                    Path.of(configuration.resolveStorageHome(), "llm-concurrency", sanitize(id)), capacity);
        }
        return new InMemoryGate(capacity);
    }

    private static String sanitize(final String id) {
        final var sb = new StringBuilder(id.length());
        for (final var c : id.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }
}
