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
package io.yupiik.ia.agent.github.store;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.BotJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskStoreTest {
    @TempDir
    Path home;

    @Test
    void receiptSurvivesTaskDeletionAndRestart() {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var store = new TaskStore(home, json);
            final var id = store.transaction(state -> state.enqueue("comment:123", Map.of("kind", "solve")));
            store.transaction(state -> {
                state.tasks().remove(id);
                return null;
            });
            final var reopened = new TaskStore(home, json);
            assertEquals(id, reopened.transaction(state -> state.enqueue("comment:123", Map.of("kind", "solve"))));
            assertTrue(reopened.transaction(TaskStore.State::list).isEmpty());
        }
    }

    @Test
    void concurrentTransactionsDoNotLoseTasks() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty());
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var store = new TaskStore(home, json);
            final var futures = IntStream.range(0, 40)
                    .mapToObj(index -> executor.submit(() -> new TaskStore(home, json)
                            .transaction(state -> state.enqueue("invocation:" + index, Map.of("kind", "review")))))
                    .toList();
            for (final var future : futures) {
                future.get();
            }
            assertEquals(40, store.transaction(TaskStore.State::list).size());
        }
    }

    @Test
    void symlinkStateIsRejected() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var target = Files.createTempFile("yca-state-test", ".json");
            try {
                Files.createSymbolicLink(home.resolve("state.json"), target);
                assertThrows(
                        IllegalStateException.class,
                        () -> new TaskStore(home, json).transaction(TaskStore.State::list));
            } finally {
                Files.deleteIfExists(target);
            }
        }
    }

    @Test
    void malformedOrFutureStateIsPreservedForOperatorRecovery() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var file = home.resolve("state.json");
            final var store = new TaskStore(home, json);
            for (final var content : List.of(
                    "{broken",
                    "[]",
                    "null",
                    "{\"schemaVersion\":2,\"tasks\":{}}",
                    "{\"schemaVersion\":1.5}",
                    "{\"schemaVersion\":null}",
                    "{\"tasks\":[]}",
                    "{\"receipts\":null}",
                    "{\"cursors\":false}")) {
                Files.writeString(file, content);
                assertThrows(RuntimeException.class, () -> store.transaction(TaskStore.State::list));
                assertEquals(content, Files.readString(file));
            }
        }
    }

    @Test
    void readOnlyTransactionCannotRewriteInvalidUnusedSection() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var content = "{\"schemaVersion\":1,\"tasks\":{},\"receipts\":[]}";
            Files.writeString(home.resolve("state.json"), content);
            assertThrows(
                    IllegalStateException.class, () -> new TaskStore(home, json).transaction(TaskStore.State::list));
            assertEquals(content, Files.readString(home.resolve("state.json")));
        }
    }

    @Test
    void symbolicLockCannotRedirectWrites() throws Exception {
        final var target = home.resolve("protected");
        Files.writeString(target, "unchanged");
        Files.createSymbolicLink(home.resolve("state.lock"), target);
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            assertThrows(
                    IllegalStateException.class, () -> new TaskStore(home, json).transaction(TaskStore.State::list));
            assertEquals("unchanged", Files.readString(target));
        }
    }

    @Test
    void failedTransactionDoesNotCommit() {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var store = new TaskStore(home, json);
            assertThrows(
                    IllegalStateException.class,
                    () -> store.transaction(state -> {
                        state.enqueue("x", Map.of());
                        throw new IllegalStateException();
                    }));
            assertTrue(store.transaction(TaskStore.State::list).isEmpty());
        }
    }
}
