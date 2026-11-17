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
package io.yupiik.ia.agent.github.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.github.configuration.ContextConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheMaintenanceCommandTest {
    @TempDir
    Path root;

    @Test
    void disabledCommandDoesNotCreateAnyCacheOrWorkerDirectories() {
        final var cache = root.resolve("cache");
        command(Map.of("worker.dependencyCache.root", cache.toString())).run();
        assertFalse(Files.exists(cache));
        assertFalse(Files.exists(root.resolve("state")));
    }

    @Test
    void enabledCommandRunsOneFiniteSliceWithoutLaunchingAnAgent() {
        final var cache = root.resolve("cache");
        final var command = command(Map.of(
                "worker.dependencyCache.root",
                cache.toString(),
                "worker.dependencyCache.mode",
                "READ_WRITE",
                "budget",
                "1"));
        assertDoesNotThrow(command::run);
        assertTrue(Files.isRegularFile(cache.resolve("state")));
        assertFalse(Files.exists(root.resolve("state")));
        assertDoesNotThrow(command::run);
    }

    @Test
    void refusesStateAndAttemptStorageOverlapBeforeCacheInitialization() {
        for (final var cache : new String[] {
            "state", "state/cache", "attempts", "attempts/cache", "contexts/cache", "git-cache/dependencies"
        }) {
            final var command = command(Map.of(
                    "worker.dependencyCache.root",
                    root.resolve(cache).toString(),
                    "worker.dependencyCache.mode",
                    "RESTORE_ONLY",
                    "github.executor.attemptRoot",
                    root.resolve("attempts").toString(),
                    "github.context.storageRoot",
                    root.resolve("contexts").toString(),
                    "worker.cacheRoot",
                    root.resolve("git-cache").toString()));
            assertThrows(IllegalArgumentException.class, command::run);
            assertFalse(Files.exists(root.resolve(cache)));
        }
    }

    @Test
    void rejectsUnboundedMaintenanceBudgets() {
        for (final var budget : new String[] {"0", "-1", "100001"}) {
            final var command = command(Map.of("budget", budget));
            assertThrows(IllegalArgumentException.class, command::run);
        }
    }

    private CacheMaintenanceCommand command(final Map<String, String> values) {
        return new CacheMaintenanceCommand(
                new CacheMaintenanceCommand$Options$FusionConfigurationFactory(
                                key -> Optional.ofNullable(values.get(key)))
                        .get(),
                BotTestConfiguration.CLOCK,
                BotTestConfiguration.home(root.resolve("state")),
                new GithubExecutorConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key)))
                        .get()
                        .shared(),
                new ContextConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get());
    }
}
