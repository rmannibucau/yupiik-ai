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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link ConcurrencyGate}: an in-memory budget, private to this JVM.
 *
 * <p>The capacity, the in-flight count and the waiter queue live under the same monitor so there is
 * a single source of truth: a waiter is granted only when it is at the head of the queue, which
 * makes the admission order strict FIFO and prevents a newcomer from barging a slot a queued caller
 * was about to get. That ordering is deliberately the same as the one a cross-process (file lease)
 * gate can honour, so switching implementation does not change the semantics callers observe.</p>
 *
 * <p>{@link #acquire()} never blocks: the uncontended path takes a slot and completes the returned
 * stage synchronously on the calling thread, a contended one enqueues a future completed later by
 * the releasing thread. A cancelled waiter is skipped by the drain and never granted.</p>
 */
public final class InMemoryGate implements ConcurrencyGate {
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int capacity;
    private int inFlight;
    private boolean closed;

    /**
     * Creates a gate.
     *
     * @param capacity the configured slot count, clamped to {@code >= 1}.
     */
    public InMemoryGate(final int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public CompletionStage<Runnable> acquire() {
        final var waiter = new Waiter();
        final boolean immediate;
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("concurrency gate is closed"));
            }
            immediate = inFlight < capacity && waiters.isEmpty();
            if (immediate) {
                inFlight++;
            } else {
                waiters.addLast(waiter);
                // a cancelled (or otherwise failed) waiter leaves the queue; a grant cannot race it:
                // once the future is completed, cancel() no longer succeeds
                waiter.granted.whenComplete((release, error) -> {
                    if (release == null) {
                        synchronized (this) {
                            waiters.remove(waiter);
                        }
                    }
                });
            }
        }
        if (immediate) {
            waiter.granted.complete(waiter.release());
            return waiter.granted;
        }
        return waiter.granted;
    }

    @Override
    public void setCapacity(final int capacity) {
        synchronized (this) {
            this.capacity = Math.max(1, capacity);
            // shrink: the outstanding grants keep running, the drain below naturally hands slots over
            // only once the real in-flight count is back under the new capacity; growth wakes waiters
            drain();
        }
    }

    /** Fails the pending waiters and refuses new admissions; idempotent. */
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            for (final var waiter : waiters) {
                waiter.granted.completeExceptionally(new IllegalStateException("concurrency gate is closed"));
            }
            waiters.clear();
        }
    }

    /** Grants the head waiters while slots are free, skipping the ones their caller abandoned. */
    private void drain() {
        while (inFlight < capacity && !waiters.isEmpty()) {
            final var head = waiters.pollFirst();
            inFlight++;
            if (!head.granted.complete(head.release())) {
                // the caller cancelled the grant concurrently: no slot was handed over, undo the
                // increment so the next waiter (or the released slot) can be served
                inFlight--;
            }
        }
    }

    private void released() {
        synchronized (this) {
            if (inFlight > 0) {
                inFlight--;
            }
            drain();
        }
    }

    /** One queued caller: its grant stage plus the idempotent release action handed over with it. */
    private final class Waiter {
        private final CompletableFuture<Runnable> granted = new CompletableFuture<>();
        private final AtomicBoolean released = new AtomicBoolean();

        private Runnable release() {
            return () -> {
                if (released.compareAndSet(false, true)) {
                    released();
                }
            };
        }
    }
}
