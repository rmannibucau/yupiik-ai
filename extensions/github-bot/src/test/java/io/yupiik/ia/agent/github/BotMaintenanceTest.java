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
package io.yupiik.ia.agent.github;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotMaintenanceTest {
    @TempDir
    Path home;

    @Test
    void purgesOnlyExpiredCleanTerminalTasksAndKeepsReceipts() {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var store = new TaskStore(home, json);
            final var now = Instant.parse("2026-11-11T00:00:00Z");
            store.transaction(state -> {
                for (final var status : List.of("done", "failed", "running", "publishing", "pending")) {
                    final var id = state.enqueue(
                            status,
                            Map.of("completedAt", now.minus(Duration.ofDays(8)).toString()));
                    state.task(id).put("state", status);
                }
                final var retained = state.enqueue(
                        "secret-bearing",
                        Map.of(
                                "completedAt",
                                now.minus(Duration.ofDays(8)).toString(),
                                "attemptDirectory",
                                "/private/attempt"));
                state.task(retained).put("state", "done");
                final var fresh = state.enqueue("fresh", Map.of("completedAt", now.toString()));
                state.task(fresh).put("state", "done");
                return null;
            });
            final var maintenance = new BotMaintenance(store, Clock.fixed(now, ZoneOffset.UTC));
            assertEquals(2, maintenance.purge(Duration.ofDays(7)));
            assertEquals(5, store.transaction(state -> state.list().size()).intValue());
            assertEquals(7, store.transaction(state -> state.receipts().size()).intValue());
            final var receipt = store.transaction(state -> state.enqueue("done", Map.of()));
            assertThrows(IllegalArgumentException.class, () -> store.transaction(state -> state.task(receipt)));
            assertThrows(IllegalArgumentException.class, () -> maintenance.purge(Duration.ZERO));
        }
    }
}
