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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.llm.client.spi.ConcurrencyGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class ProviderConcurrencyGatesTest {
    @Test
    void disabledByDefaultStaysInMemory(@TempDir final Path home) {
        try (final var gates = registry(home)) {
            assertCapacity(gates.gate(provider("free", 2, false), 2), 2);
            assertFalse(Files.exists(home.resolve("llm-concurrency")));
        }
    }

    @Test
    void enabledCreatesTheLeaseDirectoryLazily(@TempDir final Path home) {
        try (final var gates = registry(home)) {
            final var gate = gates.gate(provider("free", 2, true), 2);
            assertFalse(Files.exists(home.resolve("llm-concurrency")));
            assertCapacity(gate, 2);
            assertTrue(Files.isDirectory(home.resolve("llm-concurrency/free")));
        }
    }

    @Test
    void enabledButUnboundedStaysInMemory(@TempDir final Path home) {
        try (final var gates = registry(home)) {
            assertCapacity(gates.gate(provider("free", -1, true), 1), 1);
            assertFalse(Files.exists(home.resolve("llm-concurrency")));
        }
    }

    @Test
    void globalMaxConcurrentActivatesTheFileGate(@TempDir final Path home) {
        try (final var gates =
                new ProviderConcurrencyGates(configuration(home, Map.of("yca.llm.maxConcurrent", "2")))) {
            assertCapacity(gates.gate(provider("free", -1, true), 2), 2);
            assertTrue(Files.isDirectory(home.resolve("llm-concurrency/free")));
        }
    }

    @Test
    void lookupsOnlyNarrowAndKeepOneBudget(@TempDir final Path home) {
        for (final var file : List.of(false, true)) {
            try (final var gates = registry(home)) {
                final var wide = gates.gate(provider("free", 4, file), 4);
                final var narrow = gates.gate(provider("free", 2, file), 2);
                assertSame(wide, narrow);
                assertCapacity(narrow, 2);
                assertSame(wide, gates.gate(provider("free", 4, file), 4));
                assertSame(wide, gates.gate(provider("free", 0, file), 0));
                assertSame(wide, gates.gate(provider("free", -1, file), -1));
                assertCapacity(wide, 2);
                wide.setCapacity(4);
                assertCapacity(wide, 2, "explicit recovery cannot exceed the reconciled ceiling");
            }
        }
    }

    @Test
    void learnedNarrowingSurvivesSameWiderAndNewlyNarrowerRequests(@TempDir final Path home) {
        for (final var file : List.of(false, true)) {
            try (final var gates = registry(home)) {
                final var gate = gates.gate(provider("free", 8, file), 8);
                gate.learnCapacity(1);
                assertSame(gate, gates.gate(provider("free", 8, file), 8));
                assertCapacity(gate, 1);
                gates.gate(provider("free", 12, file), 12);
                assertCapacity(gate, 1);
                gates.gate(provider("free", 4, file), 4);
                assertCapacity(gate, 1);
                gate.setCapacity(3);
                assertCapacity(gate, 3, "explicit adaptation recovery remains supported");
                gates.gate(provider("free", 8, file), 8);
                assertCapacity(gate, 3);
            }
        }
    }

    @Test
    void nonpositiveBoundsAreUnboundedUntilAPositiveRequest(@TempDir final Path home) {
        try (final var gates = registry(home)) {
            final var gate = gates.gate(provider("free", -1, false), -1);
            final var releases = new ArrayList<Runnable>();
            try {
                for (var i = 0; i < 20; i++) {
                    final var release = gate.tryAcquire();
                    assertNotNull(release);
                    releases.add(release);
                }
            } finally {
                releases.forEach(Runnable::run);
            }
            assertSame(gate, gates.gate(provider("free", 3, false), 3));
            assertCapacity(gate, 3);
            gates.gate(provider("free", -1, false), -1);
            assertCapacity(gate, 3);
        }
    }

    @Test
    void persistedNarrowingSurvivesLookupReconciliation(@TempDir final Path home) {
        final var provider = provider("persisted", 8, true);
        try (final var gates = registry(home)) {
            gates.gate(provider, 8).learnCapacity(1);
        }
        try (final var gates = registry(home)) {
            final var gate = gates.gate(provider, 8);
            gates.gate(provider, 4);
            assertCapacity(gate, 1);
        }
    }

    @Test
    void modeAndCapacityConflictsWarnOnceAndRetainTheLiveMode(@TempDir final Path home) {
        final var logger = Logger.getLogger(ProviderConcurrencyGates.class.getName());
        final var records = new ArrayList<LogRecord>();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        final var previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            for (final var file : List.of(false, true)) {
                final var id = "mode-" + file;
                try (final var gates = registry(home)) {
                    final var first = gates.gate(provider(id, 4, file), 4);
                    assertCapacity(first, 4);
                    for (var i = 0; i < 3; i++) {
                        assertSame(first, gates.gate(provider(id, 2, !file), 2));
                    }
                    assertCapacity(first, 2);
                    assertEquals(
                            file,
                            Files.isDirectory(home.resolve("llm-concurrency").resolve(id)));
                }
                assertEquals(
                        1,
                        records.stream()
                                .filter(r -> r.getLevel() == Level.WARNING
                                        && r.getMessage().contains("mode mismatch")
                                        && r.getMessage().contains("'" + id + "'"))
                                .count());
                assertEquals(
                        1,
                        records.stream()
                                .filter(r -> r.getLevel() == Level.WARNING
                                        && r.getMessage().contains("capacity mismatch")
                                        && r.getMessage().contains("'" + id + "'"))
                                .count());
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void directoryKeysAreSafeDistinctAndStable(@TempDir final Path home) throws Exception {
        // These unsafe ids have the same Java hash and the same old sanitized prefix.
        assertEquals("a:~".hashCode(), "a;_".hashCode());
        final var ids = List.of("openai", "a_b", "a/b", "a:~", "a;_", ".", "..", "../escape", "a\\b", "x".repeat(300));
        final var directories = new ArrayList<Path>();
        for (final var id : ids) {
            try (final var gates = registry(home)) {
                final var gate = gates.gate(provider(id, 1, true), 1);
                final var before = new ArrayList<Path>();
                if (Files.exists(home.resolve("llm-concurrency"))) {
                    try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
                        paths.forEach(before::add);
                    }
                }
                assertCapacity(gate, 1);
                try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
                    final var created = paths.filter(p -> !before.contains(p)).toList();
                    assertEquals(1, created.size(), "distinct id must create a distinct child: " + id);
                    final var directory = created.getFirst();
                    assertEquals(home.resolve("llm-concurrency"), directory.getParent());
                    directories.add(directory);
                }
            }
        }
        assertEquals("openai", directories.getFirst().getFileName().toString());
        final var hashedKey = directories.get(2).getFileName().toString();
        try (final var gates = registry(home)) {
            assertCapacity(gates.gate(provider(hashedKey, 1, true), 1), 1);
        }
        try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
            assertEquals(ids.size() + 1L, paths.count(), "a safe-looking id cannot impersonate a generated key");
        }
        try (final var gates = registry(home)) {
            assertCapacity(gates.gate(provider("a/b", 1, true), 1), 1);
        }
        try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
            assertEquals(ids.size() + 1L, paths.count(), "keys must be stable across registry lifecycles");
        }
    }

    @Test
    void closingRejectsExistingAndNewLookups(@TempDir final Path home) {
        for (final var file : List.of(false, true)) {
            final var gates = registry(home);
            final var provider = provider("free", 2, file);
            final var gate = gates.gate(provider, 2);
            final var release = gate.acquire().join();
            gates.close();
            gates.close();
            release.run();
            assertTrue(gate.acquire(100L, null).isCompletedExceptionally());
            assertThrows(IllegalStateException.class, () -> gates.gate(provider, 2));
            assertThrows(IllegalStateException.class, () -> gates.gate(provider("new", 2, file), 2));
        }
    }

    @Test
    void concurrentLookupsAndCloseCannotLeaveAnOpenGate(@TempDir final Path home) throws Exception {
        final var gates = registry(home);
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var lookups = new ArrayList<java.util.concurrent.Future<ConcurrencyGate>>();
            for (var i = 0; i < 20; i++) {
                final var id = "race-" + i;
                lookups.add(executor.submit(() -> {
                    start.await();
                    try {
                        return gates.gate(provider(id, 2, true), 2);
                    } catch (final IllegalStateException closed) {
                        return null;
                    }
                }));
            }
            final var closing = executor.submit(() -> {
                start.await();
                gates.close();
                return null;
            });
            start.countDown();
            closing.get();
            for (final var lookup : lookups) {
                final var gate = lookup.get();
                if (gate != null) {
                    assertTrue(gate.acquire(100L, null).isCompletedExceptionally());
                }
            }
        } finally {
            gates.close();
        }
        assertThrows(IllegalStateException.class, () -> gates.gate(null, 1));
    }

    private static void assertCapacity(final ConcurrencyGate gate, final int capacity) {
        assertCapacity(gate, capacity, "the shared capacity must apply");
    }

    private static void assertCapacity(final ConcurrencyGate gate, final int capacity, final String message) {
        final var releases = new ArrayList<Runnable>();
        try {
            for (var i = 0; i < capacity; i++) {
                final var release = gate.tryAcquire();
                assertNotNull(release, message);
                releases.add(release);
            }
            assertNull(gate.tryAcquire(), message);
        } finally {
            releases.forEach(Runnable::run);
        }
    }

    private static ProviderConcurrencyGates registry(final Path home) {
        return new ProviderConcurrencyGates(configuration(home, Map.of()));
    }

    private static ProviderConfiguration provider(final String id, final int maxConcurrent, final boolean useFileLock) {
        final var props = new HashMap<String, String>();
        props.put("yca.provider.providers.length", "1");
        props.put("yca.provider.providers.0.id", id);
        props.put("yca.provider.providers.0.baseUrl", "https://example.com/v1");
        props.put("yca.provider.providers.0.model", "test-model");
        props.put("yca.provider.providers.0.maxConcurrent", Integer.toString(maxConcurrent));
        props.put("yca.provider.providers.0.capabilities.concurrency.useFileLock", Boolean.toString(useFileLock));
        return configuration(Path.of("/tmp"), props).provider().providers().getFirst();
    }

    private static CodingAgentConfiguration configuration(final Path home, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>(extra);
        entries.put("yca.storageHome", home.toString());
        entries.put("yca.provider.providers.length", "1");
        return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(entries.get(key)))
                .get();
    }
}
