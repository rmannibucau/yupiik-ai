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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Covers the cross-process lease semantics: exclusion, peer contention, persistence and lifecycle. */
@Timeout(30)
class FileLeaseConcurrencyGateTest {
    @Test
    void deliversItsCapacitySynchronouslyThenQueues(@TempDir final Path home) {
        try (final var gate = new FileLeaseConcurrencyGate(home.resolve("openai"), 2)) {
            final var first = gate.acquire().toCompletableFuture();
            final var second = gate.acquire().toCompletableFuture();
            assertTrue(first.isDone(), "a free slot must be granted without a poll cycle");
            assertTrue(second.isDone());
            assertFalse(gate.acquire().toCompletableFuture().isDone(), "the third request must be queued");

            first.join().run();
            assertTrue(
                    Files.exists(home.resolve("openai").resolve("slot-0.lock")),
                    "the slot files are created on the first acquire");
        }
    }

    @Test
    void twoGatesOfTheSameJvmShareTheSlots(@TempDir final Path home) {
        // FileLock is process-scoped: two gates on one directory must not overlap, which is what the
        // static JVM guard enforces (without it, the second tryLock would raise an exception)
        try (final var first = new FileLeaseConcurrencyGate(home.resolve("openai"), 1);
                final var second = new FileLeaseConcurrencyGate(home.resolve("openai"), 1)) {
            final var lease = first.acquire().toCompletableFuture().join();
            final var queued = second.acquire().toCompletableFuture();
            assertFalse(queued.isDone(), "the only slot is leased by the first gate");

            lease.run();
            final var granted = queued.orTimeout(10, TimeUnit.SECONDS).join();
            assertNotNull(granted);
            granted.run();
        }
    }

    @Test
    void aPeerProcessHoldingTheSlotBlocksTheGate(@TempDir final Path home) throws Exception {
        final var dir = home.resolve("openai");
        Files.createDirectories(dir);
        final var slot = dir.resolve("slot-0.lock");
        Files.createFile(slot);

        try (final var peer = FileChannel.open(slot, StandardOpenOption.WRITE);
                final FileLock peerLock = peer.tryLock()) {
            assertNotNull(peerLock, "the peer must get the lock first");

            try (final var gate = new FileLeaseConcurrencyGate(dir, 1)) {
                final var queued = gate.acquire().toCompletableFuture();
                assertFalse(queued.isDone(), "the slot is held by a peer process");
                // ... and it is granted as soon as the peer releases it (the try-with-resources closes
                // below, so the check happens after this block)
                gate.setCapacity(1);
                assertEquals(1, gate.acquire().toCompletableFuture().isDone() ? 2 : 1);
            }
        }
        // a fresh gate sees the freed slot again
        try (final var gate = new FileLeaseConcurrencyGate(dir, 1)) {
            assertTrue(gate.acquire().toCompletableFuture().isDone(), "the lease of a dead peer must not be sticky");
        }
    }

    @Test
    void persistedCapacityIsHonouredUntilItExpires(@TempDir final Path home) throws Exception {
        final var dir = home.resolve("freeinference");

        try (final var gate = new FileLeaseConcurrencyGate(dir, 4)) {
            gate.setCapacity(2);
            assertTrue(gate.acquire().toCompletableFuture().isDone());
            assertTrue(gate.acquire().toCompletableFuture().isDone());
            assertFalse(gate.acquire().toCompletableFuture().isDone(), "the narrowed capacity is 2");
        }
        assertTrue(Files.exists(dir.resolve("capacity")), "the learned capacity must be persisted");
        assertEquals("2", Files.readString(dir.resolve("capacity")).trim());

        // a fresh gate (a new process) starts narrowed instead of burning a 429 to rediscover it
        try (final var gate = new FileLeaseConcurrencyGate(dir, 4)) {
            assertTrue(gate.acquire().toCompletableFuture().isDone());
            assertTrue(gate.acquire().toCompletableFuture().isDone());
            assertFalse(gate.acquire().toCompletableFuture().isDone(), "the persisted capacity must be reused");
        }

        // and a stale file is ignored
        Files.setLastModifiedTime(
                dir.resolve("capacity"), FileTime.fromMillis(System.currentTimeMillis() - 7 * 24 * 3600_000L));
        try (final var gate = new FileLeaseConcurrencyGate(dir, 4)) {
            for (var i = 0; i < 4; i++) {
                assertTrue(gate.acquire().toCompletableFuture().isDone(), "a stale capacity must be ignored");
            }
            assertFalse(gate.acquire().toCompletableFuture().isDone());
        }
    }

    @Test
    void restoringTheConfiguredCapacityClearsThePersistedNarrowing(@TempDir final Path home) {
        final var dir = home.resolve("openai");
        try (final var gate = new FileLeaseConcurrencyGate(dir, 3)) {
            gate.setCapacity(1);
            assertTrue(Files.exists(dir.resolve("capacity")));
            gate.setCapacity(3);
            assertFalse(Files.exists(dir.resolve("capacity")), "restoring the ceiling erases the narrowing");
        }
    }

    @Test
    void cancelledWaiterLeavesNoLeakedSlot(@TempDir final Path home) {
        try (final var gate = new FileLeaseConcurrencyGate(home.resolve("openai"), 1)) {
            final var held = gate.acquire().toCompletableFuture().join();
            final var queued = gate.acquire().toCompletableFuture();
            assertTrue(queued.cancel(false), "a queued waiter must be cancellable");

            held.run();
            // the released slot must be free again for a real caller
            final var next = gate.acquire().toCompletableFuture();
            assertTrue(next.isDone(), "the cancelled waiter must not consume the released slot");
            next.join().run();
        }
    }

    @Test
    void closeFailsPendingWaiters(@TempDir final Path home) {
        final var gate = new FileLeaseConcurrencyGate(home.resolve("openai"), 1);
        gate.acquire(); // holds the only slot
        final var queued = gate.acquire().toCompletableFuture();

        gate.close();

        final var failure = assertThrows(ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException, "got: " + failure.getCause());
        assertThrows(
                ExecutionException.class,
                () -> gate.acquire().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void unusableDirectoryDegradesToAnInMemoryBudget(@TempDir final Path home) throws Exception {
        // a regular file where the lease directory should be: every filesystem operation fails, the
        // gate must keep serving requests instead of failing them
        final var path = home.resolve("blocked");
        Files.writeString(path, "not a directory");

        try (final var gate = new FileLeaseConcurrencyGate(path.resolve("openai"), 2)) {
            final var first = gate.acquire().toCompletableFuture();
            assertTrue(first.isDone(), "the degraded gate serves the request");
            first.join().run();
        }
    }
}
