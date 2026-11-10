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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Hardening coverage: permit acquire/release pairing, retry-after floor, legacy retryable classification. */
class LlmClientResilienceWrapperHardeningTest {

    private static LlmResilienceConfig config(final int attempts, final long baseDelayMs, final int maxConcurrent) {
        return new LlmResilienceConfig(
                attempts, baseDelayMs, 0, maxConcurrent, false, true, 300_000L, 1.0d, 900_000L, 3_600_000L, 300_000L);
    }

    private static LlmResilienceConfig config(
            final int attempts, final long baseDelayMs, final int maxConcurrent, final double waitScale) {
        return new LlmResilienceConfig(
                attempts,
                baseDelayMs,
                0,
                maxConcurrent,
                false,
                true,
                300_000L,
                waitScale,
                900_000L,
                3_600_000L,
                300_000L);
    }

    @Test
    void aNeverAcquiredPermitIsNotReleasedWhenTheGateAppearsMidFlight() throws Exception {
        // regression for the permit-inflation race: with an unlimited config the gate is absent,
        // so the first request acquires nothing; a concurrency-limit 429 then lazily creates the
        // gate. The old code released unconditionally, inflating the new gate above its cap.
        final var dial = new ScriptedDialect(
                new IOException("LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\","
                        + "\"message\":\"Too many concurrent requests\",\"limit\":2}}"));
        final var gate = new CountingGate(2);
        final var wrapper =
                new LlmClientResilienceWrapper(config(2, 0, 0, 0.0001d), null, null, null, null, capacity -> gate);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        // the attempt which failed on the 429 never held a slot: only the retried one did, and it
        // gave it back. A phantom release from the first attempt would show up as released > acquired
        assertEquals(1, gate.acquired.get(), "only the retried attempt takes a slot");
        assertEquals(1, gate.released.get(), "no slot must be released without an acquire");

        // the lazily-created gate must still hold exactly its announced limit (2)
        final var first = gate.acquire().toCompletableFuture().join();
        final var second = gate.acquire().toCompletableFuture().join();
        final var third = gate.acquire().toCompletableFuture();
        assertFalse(third.isDone(), "an inflated gate would admit a third request");
        first.run();
        second.run();
        assertTrue(third.isDone(), "the third request is served once a slot is returned");
        third.join().run();
    }

    @Test
    void cancelledStreamNeverAcquiredDoesNotReleaseIntoTheNewGate() throws Exception {
        // stream path: the first (unlimited) stream attempt fails with a concurrency-limit 429,
        // creating the gate; the retried live stream is then cancelled by the subscriber. The
        // wrapper must only release the slot the retry actually acquired.
        final var calls = new AtomicInteger();
        final Dialect dial = new ScriptedDialect() {
            @Override
            public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                    final LlmRequest request, final BooleanSupplier cancelled) {
                final var call = calls.getAndIncrement();
                if (call == 0) {
                    return failed(new IOException("LLM request failed (429): {\"error\":{\"code\":"
                            + "\"concurrency_limit_exceeded\",\"message\":\"Too many concurrent requests\","
                            + "\"limit\":2}}"));
                }
                return CompletableFuture.completedFuture(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(final long n) {}

                    @Override
                    public void cancel() {}
                }));
            }
        };
        final var gate = new CountingGate(2);
        final var wrapper =
                new LlmClientResilienceWrapper(config(2, 0, 0, 0.0001d), null, null, null, null, capacity -> gate);
        final var published =
                wrapper.stream(dial, null, () -> false).toCompletableFuture().join();
        // cancel immediately from the subscriber side
        published.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override
            public void onNext(final LlmStreamEvent item) {}

            @Override
            public void onError(final Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        // the retry acquired one slot and released it exactly once on cancel: a failed setup never
        // held one, so the pair must be 1/1 and the gate must be back to its announced limit
        assertEquals(1, gate.acquired.get(), "only the successful stream setup takes a slot");
        assertEquals(1, gate.released.get(), "cancel must release exactly the acquired slot");
        final var first = gate.acquire().toCompletableFuture().join();
        final var second = gate.acquire().toCompletableFuture().join();
        final var third = gate.acquire().toCompletableFuture();
        assertFalse(third.isDone(), "a leaked slot would admit a third stream");
        first.run();
        second.run();
        third.join().run();
    }

    @Test
    void serverRetryAfterIsAFloorNotACandidate() {
        // retry-after=2 with a zero jitter: the wait must be the server floor (2s), not capped
        // down to the jitter; with waitScale the actual sleep stays tiny
        final var dial = new ScriptedDialect(new IOException("LLM request failed (429): {\"error\":{}} retry-after=2"));
        final var pauses = new CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4, 0.0001d), pauses::add);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        final var pause = pauses.stream()
                .filter(p -> p.reason() != null && p.reason().contains("retry-after=2s"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a retry-after pause, got: " + pauses));
        assertEquals(2_000L, pause.waitMs(), "the server retry-after must be a floor (2s), got: " + pause);
    }

    @Test
    void serverRetryAfterFloorDoesNotCapALargerJitter() {
        // base delay 5s -> jitter in [2.5s, 5s): larger than the 2s retry-after, so the wait must
        // be the jitter (the floor must not become a cap): waitMs in [2500, 5000)
        final var dial = new ScriptedDialect(new IOException("LLM request failed (429): {\"error\":{}} retry-after=2"));
        final var pauses = new CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 5_000, 4, 0.0001d), pauses::add);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        final var pause = pauses.stream()
                .filter(p -> p.reason() != null && p.reason().contains("retry-after=2s"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a retry-after pause, got: " + pauses));
        assertTrue(
                pause.waitMs() >= 2_500 && pause.waitMs() < 5_000,
                "a larger jitter must win over the retry-after floor (no cap), got: " + pause);
    }

    @Test
    void transientLegacyTransportMessageIsRetried() {
        final var dial = new ScriptedDialect(new IOException("Connection reset by peer"));
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4), null);
        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());
        assertEquals(2, dial.calls.get(), "a transient transport message must be retried");
    }

    @Test
    void deterministicParseErrorIsNotRetried() {
        // a plain (non-"LLM request failed") message used to be retried unconditionally; a
        // deterministic parse/rejection error must now fail fast
        final var dial = new ScriptedDialect(new IOException("boom: cannot parse response"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("cannot parse response"),
                "got: " + failure.getCause());
        assertEquals(1, dial.calls.get(), "a deterministic parse error must not be retried");
    }

    /**
     * In-memory gate counting the acquire/release pairs: the point of the regression coverage is the
     * pairing (a slot must never be returned without having been taken), not the semaphore internals.
     */
    private static class CountingGate implements ConcurrencyGate {
        private final InMemoryGate delegate;
        private final AtomicInteger acquired = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();

        private CountingGate(final int capacity) {
            this.delegate = new InMemoryGate(capacity);
        }

        @Override
        public CompletionStage<Runnable> acquire() {
            acquired.incrementAndGet();
            return delegate.acquire().thenApply(release -> () -> {
                released.incrementAndGet();
                release.run();
            });
        }

        @Override
        public void setCapacity(final int capacity) {
            delegate.setCapacity(capacity);
        }
    }

    private static LlmResponse response() {
        return new LlmResponse(
                "id",
                "model",
                0L,
                List.of(new LlmResponse.LlmChoice(
                        0, new LlmMessage("assistant", "ok", null, null, null, null), "stop")),
                null,
                null);
    }

    private static <A> CompletableFuture<A> failed(final Throwable error) {
        final var future = new CompletableFuture<A>();
        future.completeExceptionally(error);
        return future;
    }

    private static class ScriptedDialect implements Dialect {
        private final Throwable[] failures;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedDialect(final Throwable... failures) {
            this.failures = failures;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request) {
            return chat(request, null);
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
            final var call = calls.getAndIncrement();
            return call < failures.length ? failed(failures[call]) : CompletableFuture.completedFuture(response());
        }

        @Override
        public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                final LlmRequest request, final BooleanSupplier cancelled) {
            return failed(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<LlmModels> models() {
            return CompletableFuture.completedFuture(new LlmModels(List.of()));
        }

        @Override
        public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
            return failed(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
            return failed(new UnsupportedOperationException());
        }
    }
}
