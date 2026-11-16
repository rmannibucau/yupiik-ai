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
package io.yupiik.ia.agent.slack.command;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackTaskCommandsTest {
    @TempDir
    Path root;

    @Test
    void cancellationIsImmediateOnlyForQueuedTasksAndBlocksAmbiguousTasks() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var queued = task("queued", "U1", "queued", 1);
            final var running = task("running", "U1", "running", 1);
            final var blocked = task("blocked", "U1", "recovery_blocked", 1);
            final var publishing = task("publishing", "U1", "publishing", 1);
            fixture.add(queued, running, blocked, publishing);
            new CancelCommand(new TaskOptions(queued.id()), fixture.store).run();
            new CancelCommand(new TaskOptions(running.id()), fixture.store).run();
            assertThrows(
                    IllegalStateException.class,
                    () -> new CancelCommand(new TaskOptions(blocked.id()), fixture.store).run());
            assertThrows(
                    IllegalStateException.class,
                    () -> new CancelCommand(new TaskOptions(publishing.id()), fixture.store).run());
            assertEquals(
                    List.of("cancelled", "cancel_requested", "recovery_blocked", "publishing"),
                    fixture.store.list().stream().map(t -> t.status()).toList());
        }
    }

    @Test
    void retryRequiresConfirmedFailureOrCancellation() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var failed = task("failed", "U1", "failed", 1);
            final var cancelled = task("cancelled", "U1", "cancelled", 1);
            final var blocked = task("blocked", "U1", "recovery_blocked", 1);
            fixture.add(failed, cancelled, blocked);
            new RetryCommand(new TaskOptions(failed.id()), fixture.store).run();
            new RetryCommand(new TaskOptions(cancelled.id()), fixture.store).run();
            assertThrows(
                    IllegalStateException.class,
                    () -> new RetryCommand(new TaskOptions(blocked.id()), fixture.store).run());
            assertEquals(
                    List.of("queued", "queued", "recovery_blocked"),
                    fixture.store.list().stream().map(t -> t.status()).toList());
        }
    }

    @Test
    void cleanupPreservesUncertainRuntimesRegardlessOfAge() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            fixture.add(
                    task("done", "U1", "done", 1),
                    task("failed", "U1", "failed", 1),
                    task("blocked", "U1", "recovery_blocked", 1),
                    task("running", "U1", "running", 1));
            new CleanupCommand(new CleanupCommand.Options(), fixture.configuration, fixture.store).run();
            assertEquals(
                    List.of("recovery_blocked", "running"),
                    fixture.store.list().stream().map(t -> t.status()).toList());
        }
    }

    @Test
    void taskIdsAreExactBoundedHashesNotPathsOrPatterns() {
        assertDoesNotThrow(() -> new TaskOptions("a".repeat(64)));
        for (final var invalid : new String[] {"../state", ".*", "A".repeat(64), "a".repeat(63), "a".repeat(100000)}) {
            assertThrows(IllegalArgumentException.class, () -> new TaskOptions(invalid));
        }
    }
}
