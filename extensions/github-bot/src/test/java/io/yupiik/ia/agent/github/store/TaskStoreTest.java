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

import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
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
    void typedTaskTransitionsPersistAndFailedMutationsRollBack() {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "typed",
                    TaskFixtures.task(Map.of("kind", "review", "repository", "acme/repo", "future", "preserved"))));
            store.transaction(state -> {
                final var task = state.task(id);
                task.state(TaskState.running);
                task.attempts(1);
                task.runtimeId("runtime-1");
                return null;
            });
            assertThrows(
                    IllegalStateException.class,
                    () -> store.transaction(state -> {
                        state.task(id).state(TaskState.done);
                        state.task(id).runtimeId(null);
                        throw new IllegalStateException("abort");
                    }));
            final var restored = new TaskStore(BotTestConfiguration.home(home), json)
                    .transaction(state -> state.task(id).document());
            assertEquals(TaskState.running, restored.state());
            assertEquals(TaskKind.review, restored.kind());
            assertEquals(1, restored.attempts());
            assertEquals("runtime-1", restored.runtimeId());
            assertEquals("preserved", restored.recovery().get("future"));
        }
    }

    @Test
    void receiptSurvivesTaskDeletionAndRestart() {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(
                    state -> state.enqueue("comment:123", TaskFixtures.task(Map.of("kind", "solve"))));
            store.transaction(state -> {
                state.tasks().remove(id);
                return null;
            });
            final var reopened = new TaskStore(BotTestConfiguration.home(home), json);
            assertEquals(
                    id,
                    reopened.transaction(
                            state -> state.enqueue("comment:123", TaskFixtures.task(Map.of("kind", "solve")))));
            assertTrue(reopened.transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList())
                    .isEmpty());
        }
    }

    @Test
    void concurrentTransactionsDoNotLoseTasks() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty());
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var futures = IntStream.range(0, 40)
                    .mapToObj(index -> executor.submit(() -> new TaskStore(BotTestConfiguration.home(home), json)
                            .transaction(state ->
                                    state.enqueue("invocation:" + index, TaskFixtures.task(Map.of("kind", "review"))))))
                    .toList();
            for (final var future : futures) {
                future.get();
            }
            assertEquals(
                    40,
                    store.transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList())
                            .size());
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
                        () -> new TaskStore(BotTestConfiguration.home(home), json)
                                .transaction(state -> state.list().stream()
                                        .map(TaskFixtures::map)
                                        .toList()));
            } finally {
                Files.deleteIfExists(target);
            }
        }
    }

    @Test
    void malformedStateIsPreservedForOperatorRecovery() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var file = home.resolve("state.json");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            for (final var content : List.of("{broken", "[]", "null", "{\"tasks\":[]}", "{\"cursors\":false}")) {
                Files.writeString(file, content);
                assertThrows(
                        RuntimeException.class,
                        () -> store.transaction(state ->
                                state.list().stream().map(TaskFixtures::map).toList()));
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
                    IllegalStateException.class,
                    () -> new TaskStore(BotTestConfiguration.home(home), json)
                            .transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList()));
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
                    IllegalStateException.class,
                    () -> new TaskStore(BotTestConfiguration.home(home), json)
                            .transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList()));
            assertEquals("unchanged", Files.readString(target));
        }
    }

    @Test
    void failedTransactionDoesNotCommit() {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            assertThrows(
                    IllegalStateException.class,
                    () -> store.transaction(state -> {
                        state.enqueue("x", TaskFixtures.task(Map.of()));
                        throw new IllegalStateException();
                    }));
            assertTrue(store.transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList())
                    .isEmpty());
        }
    }
}
