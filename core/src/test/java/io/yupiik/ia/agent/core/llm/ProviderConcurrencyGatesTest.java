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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.llm.client.spi.InMemoryGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The activation rule of the cross-process gate: opt-in capability AND an explicit positive bound. */
@Timeout(30)
class ProviderConcurrencyGatesTest {
    @Test
    void disabledByDefaultStaysInMemory(@TempDir final Path home) {
        final var gates =
                new ProviderConcurrencyGates(configuration(home, Map.of("yca.provider.providers.0.id", "free")));
        final var gate = gates.gate(provider("free", 2, false), 2);

        assertInstanceOf(InMemoryGate.class, gate);
        assertFalse(
                Files.exists(home.resolve("llm-concurrency")),
                "the lease directory must not be created when the capability is off");
    }

    @Test
    void enabledCreatesTheLeaseDirectoryLazily(@TempDir final Path home) {
        final var gates = new ProviderConcurrencyGates(configuration(
                home,
                Map.of(
                        "yca.provider.providers.0.id", "free",
                        "yca.provider.providers.0.maxConcurrent", "2")));
        final var gate = gates.gate(provider("free", 2, true), 2);

        assertInstanceOf(FileLeaseConcurrencyGate.class, gate);
        assertFalse(
                Files.exists(home.resolve("llm-concurrency")),
                "the directory appears on the first acquire, not at construction time");

        final var release = gate.acquire().toCompletableFuture().join();
        assertTrue(
                Files.isDirectory(home.resolve("llm-concurrency").resolve("free")),
                "the lease directory must exist once a slot is taken");
        release.run();
    }

    @Test
    void enabledButUnboundedStaysInMemory(@TempDir final Path home) {
        // a learned 429 limit is not a licence to touch the filesystem of a provider the user did not
        // ask for: without an explicit positive bound the gate must stay in memory
        final var gates =
                new ProviderConcurrencyGates(configuration(home, Map.of("yca.provider.providers.0.id", "free")));
        final var gate = gates.gate(provider("free", -1, true), 1);

        assertInstanceOf(InMemoryGate.class, gate);
        assertFalse(Files.exists(home.resolve("llm-concurrency")));
    }

    @Test
    void oneGatePerProviderIsShared(@TempDir final Path home) {
        final var gates =
                new ProviderConcurrencyGates(configuration(home, Map.of("yca.provider.providers.0.id", "free")));
        final var first = gates.gate(provider("free", 2, true), 2);
        final var second = gates.gate(provider("free", 2, true), 2);

        assertSame(first, second, "every client of a provider must land on the same budget");
        assertNotNull(first);
    }

    @Test
    void globalMaxConcurrentActivatesTheFileGate(@TempDir final Path home) {
        // provider defers to the global yca.llm.maxConcurrent (its own is 0/negative): the effective
        // bound is still positive, so useFileLock must activate the cross-process gate
        final var gates = new ProviderConcurrencyGates(configuration(
                home,
                Map.of(
                        "yca.llm.maxConcurrent", "2",
                        "yca.provider.providers.0.id", "free")));
        final var gate = gates.gate(provider("free", -1, true), 2);

        assertInstanceOf(FileLeaseConcurrencyGate.class, gate);
        assertFalse(Files.exists(home.resolve("llm-concurrency")));

        final var release = gate.acquire().toCompletableFuture().join();
        assertTrue(
                Files.isDirectory(home.resolve("llm-concurrency").resolve("free")),
                "the lease directory must exist once a slot is taken");
        release.run();
    }

    private static ProviderConfiguration provider(final String id, final int maxConcurrent, final boolean useFileLock) {
        // a provider is a real record with a wide surface: build it from its property factory rather
        // than pinning every component index in the test
        final var props = new java.util.HashMap<String, String>();
        props.put("yca.provider.providers.length", "1");
        props.put("yca.provider.providers.0.id", id);
        props.put("yca.provider.providers.0.baseUrl", "https://example.com/v1");
        props.put("yca.provider.providers.0.model", "test-model");
        props.put("yca.provider.providers.0.maxConcurrent", Integer.toString(maxConcurrent));
        props.put("yca.provider.providers.0.capabilities.concurrency.useFileLock", Boolean.toString(useFileLock));
        return configuration(Path.of("/tmp"), props).provider().providers().getFirst();
    }

    private static CodingAgentConfiguration configuration(final Path home, final Map<String, String> extra) {
        final var entries = new java.util.HashMap<String, String>(extra);
        entries.put("yca.storageHome", home.toString());
        entries.put("yca.provider.providers.length", "1");
        return new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> java.util.Optional.ofNullable(entries.get(key)))
                .get();
    }
}
