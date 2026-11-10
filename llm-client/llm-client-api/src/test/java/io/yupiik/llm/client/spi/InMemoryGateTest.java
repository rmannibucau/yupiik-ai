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
package io.yupiik.llm.client.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Pins the admission semantics callers rely on: FIFO, no barging, idempotent release, adaptation. */
@Timeout(10)
class InMemoryGateTest {
    @Test
    void uncontendedAcquireCompletesSynchronously() {
        final var gate = new InMemoryGate(1);
        final var stage = gate.acquire();

        assertTrue(stage.toCompletableFuture().isDone(), "the fast path must not hop threads");
        stage.toCompletableFuture().join().run(); // release
    }

    @Test
    void followsFifoWithoutBarging() throws Exception {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().toCompletableFuture().join();

        final var first = gate.acquire().toCompletableFuture();
        final var second = gate.acquire().toCompletableFuture();
        assertFalse(first.isDone(), "a queued waiter must not be granted while the slot is taken");
        assertFalse(second.isDone());

        held.run();

        // the head of the queue is served first, even though `second` was posted later: no barging
        assertTrue(first.isDone(), "the first waiter must be granted on release");
        assertFalse(second.isDone(), "a newcomer must not barge the queued caller");

        first.join().run();
        assertTrue(second.isDone(), "the second waiter is granted by the next release");
        second.join().run();
    }

    @Test
    void releaseIsIdempotent() {
        final var gate = new InMemoryGate(1);
        final var release = gate.acquire().toCompletableFuture().join();

        release.run();
        release.run(); // must not inflate the gate above its capacity

        final var first = gate.acquire().toCompletableFuture();
        assertTrue(first.isDone(), "the freed slot must be available again");
        final var second = gate.acquire().toCompletableFuture();
        assertFalse(second.isDone(), "a double release must not create a phantom second slot");
        first.join().run();
    }

    @Test
    void cancelledWaiterLeavesTheQueueAndIsNeverGranted() {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().toCompletableFuture().join();

        final var cancelled = gate.acquire().toCompletableFuture();
        assertTrue(cancelled.cancel(false), "a queued waiter must be cancellable");

        final var next = gate.acquire().toCompletableFuture();
        held.run();

        assertTrue(next.isDone(), "the cancelled waiter must have been skipped");
        assertFalse(cancelled.isDone() && !cancelled.isCancelled());
        next.join().run();
    }

    @Test
    void growthWakesWaiters() {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().toCompletableFuture().join();
        final var queued = gate.acquire().toCompletableFuture();
        assertFalse(queued.isDone());

        gate.setCapacity(2);

        assertTrue(queued.isDone(), "raising the capacity must serve the queued waiter immediately");
        queued.join().run();
        held.run();
    }

    @Test
    void shrinkDoesNotRevokeOutstandingGrantsAndAppliesToTheNextAdmission() {
        final var gate = new InMemoryGate(2);
        final var first = gate.acquire().toCompletableFuture().join();
        final var second = gate.acquire().toCompletableFuture().join();

        gate.setCapacity(1); // both grants keep running

        final var queued = gate.acquire().toCompletableFuture();
        assertFalse(queued.isDone(), "the shrink must apply to the next admission");

        first.run();
        assertFalse(queued.isDone(), "the in-flight grant still holds its slot after the shrink");

        second.run();
        assertTrue(queued.isDone(), "the slot is handed over once the in-flight count is back under capacity");
        queued.join().run();
    }

    @Test
    void capacityIsClampedToOne() {
        final var gate = new InMemoryGate(1);
        gate.setCapacity(0);

        final var first = gate.acquire().toCompletableFuture();
        assertTrue(first.isDone(), "a capacity of 0 must be clamped to 1 instead of deadlocking");
        final var second = gate.acquire().toCompletableFuture();
        assertFalse(second.isDone());
        first.join().run();
    }

    @Test
    void closeFailsPendingWaitersAndRefusesNewOnes() {
        final var gate = new InMemoryGate(1);
        gate.acquire().toCompletableFuture().join();
        final var queued = gate.acquire().toCompletableFuture();

        gate.close();

        final var failure = assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertInstanceOf(
                IllegalStateException.class,
                assertThrows(
                                ExecutionException.class,
                                () -> gate.acquire().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        .getCause());
    }

    @Test
    void concurrentWaitersAreAllServedExactlyOnce() {
        final var gate = new InMemoryGate(2);
        final var stages = new ArrayList<CompletableFuture<Runnable>>();
        for (var i = 0; i < 32; i++) {
            stages.add(gate.acquire().toCompletableFuture());
        }

        var outstanding = 0;
        for (final var stage : stages) {
            if (stage.isDone()) {
                outstanding++;
            }
        }
        assertEquals(2, outstanding, "only the capacity's worth of grants may be outstanding");

        // drain wave by wave: each release hands its slot to the next queued waiter
        for (var guard = 0; guard < 100 && stages.stream().anyMatch(stage -> !stage.isDone()); guard++) {
            final var wave = stages.stream()
                    .filter(CompletableFuture::isDone)
                    .map(CompletableFuture::join)
                    .toList();
            assertFalse(wave.isEmpty(), "a queued waiter must be served when a slot is released");
            wave.forEach(Runnable::run);
        }
        assertTrue(stages.stream().allMatch(CompletableFuture::isDone), "every waiter must eventually be served");
    }

    @Test
    void cancelledWaiterNeverLeaksTheSlotAcrossRaces() throws Exception {
        // regression for the drain race: a waiter whose grant is cancelled concurrently with the
        // release must not keep the slot (the old drain incremented inFlight without checking the
        // complete() return, so a lost race shrunk the budget by one slot permanently). Stress the
        // window: any leaked slot makes the final acquire block forever.
        for (var round = 0; round < 100; round++) {
            final var gate = new InMemoryGate(1);
            final var held = gate.acquire().toCompletableFuture().join();
            final var cancelling = gate.acquire().toCompletableFuture();
            assertFalse(cancelling.isDone(), "the waiter is queued");

            // cancel on another thread while the main thread releases: the drain and the cancel race
            // on the same waiter, exactly the window the fix closes
            final var canceller = Thread.ofPlatform().start(() -> cancelling.cancel(false));
            held.run();
            canceller.join();

            // two legitimate outcomes: the cancel won (the drain skipped the waiter, the slot was
            // given back) or the grant landed before the cancel (the waiter owns the slot and its
            // release returns it). Either way the budget must be intact.
            if (cancelling.isDone() && !cancelling.isCancelled()) {
                cancelling.join().run();
            }

            // a fresh acquire must be served: with the fix the slot was given back, without it a
            // lost race keeps the in-flight count inflated forever
            final var probe = gate.acquire().toCompletableFuture();
            assertTrue(probe.isDone(), "the slot must not be leaked, round " + round);
            probe.join().run();
        }
    }
}
