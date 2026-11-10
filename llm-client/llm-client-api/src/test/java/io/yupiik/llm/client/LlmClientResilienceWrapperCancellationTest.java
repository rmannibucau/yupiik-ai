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
package io.yupiik.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.ConcurrencyGate;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.InMemoryGate;
import io.yupiik.llm.client.spi.StreamAttempt;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the two defects the gate refactor fixes: a slot must never be held during a retry wait, and
 * an abandoned turn must neither keep queuing nor issue a request.
 */
@Timeout(20)
class LlmClientResilienceWrapperCancellationTest {
    private static LlmResilienceConfig config(final int attempts, final long baseDelayMs, final int maxConcurrent) {
        return new LlmResilienceConfig(
                attempts, baseDelayMs, 0, maxConcurrent, false, true, 300_000L, 0.0001d, 900_000L, 3_600_000L, 60_000L);
    }

    @Test
    void retryWaitDoesNotHoldTheSlot() throws Exception {
        // cap 1 with a retry-after=1 producer: during the ~1s backoff the only slot must already be
        // free, so a concurrent request is served meanwhile (the old implementation parked on the
        // slot for the whole wait and the second request only started after the retry completed)
        final var slow = new FailingOnceDialect(new LlmHttpFailureException(429, "boom", "1"));
        final var quick = new FailingOnceDialect(null);
        final var wrapper = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(2, 0, 0, 1, false, true, 300_000L, 1.0d, 900_000L, 3_600_000L, 60_000L), null);

        final var retrying = wrapper.chat(slow, null, () -> false).toCompletableFuture();
        // wait for the failure to be handled, i.e. for the wrapper to be inside its backoff
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (slow.calls.get() < 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(slow.calls.get() >= 1, "the first attempt must have failed");

        final var startedAt = System.nanoTime();
        assertEquals(
                "ok",
                wrapper.chat(quick, null, () -> false)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)
                        .text());
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(
                elapsedMs < 900, "the concurrent request must not queue behind the backoff, took " + elapsedMs + "ms");

        assertEquals("ok", retrying.get(10, TimeUnit.SECONDS).text());
    }

    @Test
    void cancelledWhileQueuedNeverCallsTheProvider() {
        final var dial = new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean());
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 1), null);

        // fill the only slot with a request which never completes
        wrapper.chat(dial, null, () -> false);

        final var cancelled = new AtomicBoolean();
        final var queued = wrapper.chat(dial, null, cancelled::get).toCompletableFuture();
        assertFalse(queued.isDone(), "the second request must be queued behind the slot holder");

        cancelled.set(true);
        // the queued turn gives up: it must fail as cancelled and never reach the provider
        final var failure = assertThrows(ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("Cancelled", failure.getCause().getMessage());
    }

    @Test
    void timeoutFailsWithoutLeakingTheSlot() throws Exception {
        final var dial = new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean());
        final var wrapper = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(1, 0, 0, 1, false, false, 300_000L, 1.0d, 900_000L, 3_600_000L, 100L), null);

        wrapper.chat(dial, null, () -> false); // holds the only slot

        final var queued = wrapper.chat(
                        new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean()), null, () -> false)
                .toCompletableFuture();
        final var failure = assertThrows(ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("Timed out"),
                "got: " + failure.getCause());
    }

    @Test
    void unlimitedWrapperNeverInflatesALazilyCreatedGate() throws Exception {
        // no gate configured: the permit is a no-op, and the first 429 lazily creates a bounded gate;
        // the request which never held a slot must not release into it
        final var gate = new InMemoryGate(1);
        final var dialect = new FailingOnceDialect(new IOException(
                "LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\",\"limit\":1}}"));
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 0), null, null, null, null, capacity -> gate);

        assertEquals(
                "ok",
                wrapper.chat(dialect, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        // the lazily-created gate still has exactly one slot: take it twice, the second must block
        final var first = gate.acquire().toCompletableFuture().join();
        final var second = gate.acquire().toCompletableFuture();
        assertFalse(second.isDone(), "a phantom release would have inflated the gate");
        first.run();
        assertTrue(second.isDone());
        second.join().run();
    }

    @Test
    void lateGrantAfterTimeoutReturnsTheSlot() throws Exception {
        // regression for the late-grant race: the timeout wins a microsecond before the gate's grant
        // lands - the grant must be handed back, not dropped (a lost slot stalls the budget forever)
        final var gate = new LateGrantGate();
        final var wrapper = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(1, 0, 0, 1, false, false, 300_000L, 1.0d, 900_000L, 3_600_000L, 50L),
                null,
                null,
                null,
                null,
                capacity -> gate);

        // the first request takes and holds the only slot forever
        final var holder = new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean());
        wrapper.chat(holder, null, () -> false);

        // the second one queues and gives up when the acquire timeout wins
        final var queued = wrapper.chat(
                        new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean()), null, () -> false)
                .toCompletableFuture();
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!queued.isDone() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(queued.isDone(), "the queued request must have timed out");
        final var failure = assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS));
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("Timed out"),
                "got: " + failure.getCause());

        // the gate keeps granting attendants even after the timeout won: the grant must be returned,
        // so the slot is free again
        gate.grantLate();
        assertTrue(gate.slotFree(), "the late grant must have been returned, not leaked");
    }

    /**
     * A gate whose queued grant survives the wrapper's {@code cancel(false)} (it overrides {@link
     * CompletableFuture#cancel(boolean)} to do nothing), so the test can complete it after the
     * timeout won - the deterministic replay of the late-grant race.
     */
    private static class LateGrantGate implements ConcurrencyGate {
        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile CompletableFuture<Runnable> queued;

        @Override
        public CompletionStage<Runnable> acquire() {
            if (inFlight.getAndIncrement() == 0) { // the first caller gets the only slot
                return CompletableFuture.completedFuture(() -> inFlight.decrementAndGet());
            }
            inFlight.decrementAndGet(); // no slot: only queue, like a real contended gate
            final var future = new CompletableFuture<Runnable>() {
                @Override
                public boolean cancel(final boolean mayInterruptIfRunning) {
                    return false; // the grant must still be completable after the wrapper's cancel
                }
            };
            queued = future;
            return future;
        }

        @Override
        public void setCapacity(final int capacity) {}

        /** Hands the queued waiter its slot, as the real gate would after the timeout won. */
        void grantLate() {
            final var future = queued;
            if (future != null) {
                future.complete(() -> inFlight.decrementAndGet());
            }
        }

        boolean slotFree() {
            return inFlight.get() == 0;
        }
    }

    private static LlmResponse response(final String text) {
        return new LlmResponse(
                "id",
                "model",
                0L,
                List.of(new LlmResponse.LlmChoice(
                        0, new LlmMessage("assistant", text, null, null, null, null), "stop")),
                null,
                null);
    }

    /** Fails once, then answers: the wrapper pays one retry wait in between. */
    private static class FailingOnceDialect implements Dialect {
        private final Throwable failure;
        private final AtomicInteger calls = new AtomicInteger();

        private FailingOnceDialect(final Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String name() {
            return "failing-once";
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request) {
            return chat(request, null);
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
            final var call = calls.getAndIncrement();
            if (call == 0 && failure != null) {
                final var failed = new CompletableFuture<LlmResponse>();
                failed.completeExceptionally(failure);
                return failed;
            }
            return CompletableFuture.completedFuture(response("ok"));
        }

        @Override
        public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<LlmModels> models() {
            return CompletableFuture.completedFuture(new LlmModels(List.of()));
        }

        @Override
        public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    /** Never answers until {@code backlog} completes; flags when it was entered. */
    private static class BlockingDialect implements Dialect {
        private final CompletableFuture<LlmResponse> backlog;
        private final AtomicBoolean released;

        private BlockingDialect(final CompletableFuture<LlmResponse> backlog, final AtomicBoolean released) {
            this.backlog = backlog;
            this.released = released;
        }

        @Override
        public String name() {
            return "blocking";
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request) {
            return chat(request, null);
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
            return released.get() ? CompletableFuture.completedFuture(response("released")) : backlog;
        }

        @Override
        public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<LlmModels> models() {
            return CompletableFuture.completedFuture(new LlmModels(List.of()));
        }

        @Override
        public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
