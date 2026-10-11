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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.StreamAttempt;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LlmClientResilienceWrapperTest {

    private static LlmResilienceConfig config(final int attempts, final long baseDelayMs, final int maxConcurrent) {
        return new LlmResilienceConfig(attempts, baseDelayMs, 0, maxConcurrent, false, true, 300_000L, 1.0d, 900_000L);
    }

    private static LlmResilienceConfig config(
            final int attempts, final long baseDelayMs, final int maxConcurrent, final double waitScale) {
        return new LlmResilienceConfig(
                attempts, baseDelayMs, 0, maxConcurrent, false, true, 300_000L, waitScale, 900_000L);
    }

    @Test
    void retryAfterSecondsParsesSecondsAndIgnoresTrailingJunk() {
        final var e = new IOException("LLM request failed (429): {\"error\":{\"message\":\"boom\"}} retry-after=2,");
        assertEquals(2L, LlmClientResilienceWrapper.retryAfterSeconds(e));
    }

    @Test
    void retryAfterSecondsParsesHttpDate() {
        final var later = ZonedDateTime.now().plusMinutes(5).withNano(0);
        final var e = new IOException("LLM request failed (429): {\"error\":{}} retry-after="
                + DateTimeFormatter.RFC_1123_DATE_TIME.format(later));
        final var seconds = LlmClientResilienceWrapper.retryAfterSeconds(e);
        assertNotNull(seconds);
        assertTrue(seconds >= 240 && seconds <= 360, "unexpected delay: " + seconds);
    }

    @Test
    void retryAfterSecondsReturnsNullForMissingAndMalformed() {
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(new IOException("LLM request failed (400): oops")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(
                new IOException("LLM request failed (429): oops retry-after=")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(new IOException("no marker in here")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(null));
    }

    @Test
    void concurrencyLimitDetectionExtractsTheAnnouncedLimit() {
        final var e = new IOException(
                "LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\",\"message\":"
                        + "\"Too many concurrent requests (limit: 2)\",\"limit\":2,\"role\":\"pro\"}} retry-after=1");
        assertTrue(LlmClientResilienceWrapper.isConcurrencyLimit(e));
        assertEquals(2, LlmClientResilienceWrapper.concurrencyLimit(e));
        assertNull(LlmClientResilienceWrapper.concurrencyLimit(new IOException("LLM request failed (429): plain")));
    }

    @Test
    void concurrencyLimitEmitsAPauseWhileBlocked() throws Exception {
        final var firstStarted = new java.util.concurrent.CountDownLatch(1);
        final var releaseFirst = new java.util.concurrent.CountDownLatch(1);
        final var count = new java.util.concurrent.atomic.AtomicInteger();
        final var dial = new ScriptedDialect() {
            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
                if (count.getAndIncrement() == 0) {
                    firstStarted.countDown();
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            releaseFirst.await();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return response();
                    });
                }
                return CompletableFuture.completedFuture(response());
            }
        };
        final var pauseSeen = new java.util.concurrent.CountDownLatch(1);
        final var pauses = new java.util.concurrent.CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(1, 0, 1), pause -> {
            pauses.add(pause);
            if (pause.reason() != null && pause.reason().contains("concurrency limit reached")) {
                pauseSeen.countDown();
            }
        });

        final var first = wrapper.chat(dial, null, () -> false);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "first request must start");
        // the second request blocks on the concurrency permit: the pause must be emitted
        // without waiting for the first request to finish (run it on a worker thread)
        final var second = CompletableFuture.supplyAsync(() ->
                wrapper.chat(dial, null, () -> false).toCompletableFuture().join());
        assertTrue(
                pauseSeen.await(5, TimeUnit.SECONDS),
                "a blocked request must surface a concurrency pause, got: " + pauses);
        releaseFirst.countDown();
        assertEquals("ok", first.toCompletableFuture().get(5, TimeUnit.SECONDS).text());
        assertEquals("ok", second.get(5, TimeUnit.SECONDS).text());
    }

    @Test
    void cancelledStreamReleasesItsConcurrencyPermit() throws Exception {
        // a stream that never emits and never completes on its own: the permit is only ever
        // released when the subscriber cancels it (an Esc-interrupt), exercising the cancel()
        // release and proving the concurrency slot is reclaimed rather than leaked.
        final Dialect holdOpen = new Dialect() {
            @Override
            public String name() {
                return "hold-open";
            }

            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request) {
                return chat(request, null);
            }

            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
                return CompletableFuture.completedFuture(response());
            }

            @Override
            public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                    final LlmRequest request, final BooleanSupplier cancelled) {
                return CompletableFuture.completedFuture(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(final long n) {
                        // intentionally deliver nothing
                    }

                    @Override
                    public void cancel() {
                        // the source aborts; only the wrapper's cancel-release frees the permit
                    }
                }));
            }

            @Override
            public CompletionStage<io.yupiik.llm.client.model.LlmModels> models() {
                return CompletableFuture.completedFuture(new io.yupiik.llm.client.model.LlmModels(List.of()));
            }

            @Override
            public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
                return CompletableFuture.<StreamAttempt>failedFuture(new UnsupportedOperationException());
            }

            @Override
            public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
        };
        // cap = 1: if the cancelled stream leaked its permit, the next request blocks forever
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 1));
        final var subscribed = new java.util.concurrent.CountDownLatch(1);
        final var heldSubscription = new java.util.concurrent.atomic.AtomicReference<Flow.Subscription>();
        wrapper.stream(holdOpen, null, () -> false)
                .toCompletableFuture()
                .thenAccept(publisher -> publisher.subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        heldSubscription.set(subscription);
                        subscribed.countDown();
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {}

                    @Override
                    public void onError(final Throwable throwable) {}

                    @Override
                    public void onComplete() {}
                }));
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "stream must have subscribed");
        // cancel mid-stream, before any terminal signal -> this is the permit-release path under test
        heldSubscription.get().cancel();
        // the permit must be back: with cap=1 a leaked permit would hang the next request
        assertEquals(
                "ok",
                wrapper.chat(holdOpen, null, () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .text());
    }

    @Test
    void retryAfterIsHonoredAndSurfacedInThePauseReason() {
        final var dial = new ScriptedDialect(new IOException("LLM request failed (429): {\"error\":{}} retry-after=2"));
        final var pauses = new java.util.concurrent.CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4, 0.0001d), pauses::add);

        final var response =
                wrapper.chat(dial, null, () -> false).toCompletableFuture().join();

        assertEquals("ok", response.text());
        assertTrue(
                pauses.stream()
                        .anyMatch(p -> p.reason().contains("retry-after=2s")
                                && p.reason().contains("wait")),
                "the retry notice must surface the honored delay, got: " + pauses);
    }

    @Test
    void concurrencyLimitWithoutRetryAfterWaitsADefault() {
        final var dial = new ScriptedDialect(
                new IOException("LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\","
                        + "\"message\":\"Too many concurrent requests\",\"limit\":2}}"));
        final var pauses = new java.util.concurrent.CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4, 0.0001d), pauses::add);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        assertTrue(
                pauses.stream()
                        .anyMatch(p -> p.reason().contains("concurrency-limit")
                                && p.waitMs() == LlmClientResilienceWrapper.CONCURRENCY_LIMIT_DEFAULT_WAIT_MS),
                "the retry notice must surface the default wait and why it waits, got: " + pauses);
        assertTrue(
                pauses.stream()
                        .anyMatch(p -> p.reason() != null && p.reason().startsWith("concurrency limit 2 detected")),
                "an explicit concurrency adaptation notice must be emitted, got: " + pauses);
    }

    @Test
    void concurrencyLimitDuplicatedWhileGateActiveEmitsNoticeOnce() {
        // a 429 storm while the pool is already capped at the announced limit must not
        // re-emit the "capping concurrent requests" notice on every occurrence
        final var dial = new ScriptedDialect(
                new IOException("LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\","
                        + "\"message\":\"Too many concurrent requests\",\"limit\":2}}"));
        final var pauses = new java.util.concurrent.CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4, 0.0001d), pauses::add);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());
        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        assertEquals(
                1,
                pauses.stream()
                        .filter(p -> p.reason() != null && p.reason().startsWith("concurrency limit 2 detected"))
                        .count(),
                "the adaptation notice must be emitted once per provider+limit while the gate is active, got: "
                        + pauses);
    }

    @Test
    void retryAfterSecondsReadsTheTypedHeader() {
        assertEquals(7L, LlmClientResilienceWrapper.retryAfterSeconds(new LlmHttpFailureException(429, "boom", "7")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(new LlmHttpFailureException(429, "boom", null)));
    }

    @Test
    void typedFailuresAreClassifiedFromTheStatusWithoutMessageParsing() {
        final var typed429 = new LlmHttpFailureException(429, "boom", null);
        final var typed400 = new LlmHttpFailureException(400, "boom", null);
        final var dial = new ScriptedDialect(typed429);
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4), null);
        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());
        assertEquals(2, dial.calls.get(), "typed 429 must be retried");

        final var dial400 = new ScriptedDialect(typed400);
        final var wrapper400 = new LlmClientResilienceWrapper(config(3, 0, 4), null);
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> wrapper400
                        .chat(dial400, null, () -> false)
                        .toCompletableFuture()
                        .join());
        assertEquals(1, dial400.calls.get(), "typed 400 must not be retried");
    }

    @Test
    void http400IsNotRetried() {
        final var dial = new ScriptedDialect(new IOException("LLM request failed (400): bad request"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("(400): bad request"),
                "got: " + failure.getCause());
        assertEquals(1, dial.calls.get(), "a 400 must not be retried");
    }

    @Test
    void retriesStopAtMaxAttempts() {
        final var dial = new ScriptedDialect(
                new IOException("LLM request failed (500): boom"),
                new IOException("LLM request failed (500): boom"),
                new IOException("LLM request failed (500): boom"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(3, dial.calls.get(), "3 attempts max then give up");
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("LLM request failed (500): boom"),
                "got: " + failure.getCause());
    }

    @Test
    void unlimitedConcurrencyRunsThrough() throws Exception {
        final var dial = new ScriptedDialect();
        final var wrapper = new LlmClientResilienceWrapper(config(1, 0, -1), null);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .text());
        assertEquals(1, dial.calls.get());
    }

    @Test
    void streamSetupTransportFailureIsRetried() throws Exception {
        // a raw transport failure before any fragment (the JDK surfaces it as
        // "HTTP/1.1 header parser received no bytes") is transient: like chat(),
        // the stream setup must be retried instead of surfacing after the first attempt
        final var dial = new ScriptedDialect(new IOException("HTTP/1.1 header parser received no bytes"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        wrapper.stream(dial, null, () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(2, dial.calls.get(), "a transport stream-setup failure must be retried once");
    }

    @Test
    void streamSetupRetriesStopAtMaxAttempts() {
        final var dial = new ScriptedDialect(
                new IOException("HTTP/1.1 header parser received no bytes"),
                new IOException("HTTP/1.1 header parser received no bytes"),
                new IOException("HTTP/1.1 header parser received no bytes"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> wrapper.stream(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(3, dial.calls.get(), "3 attempts max then give up");
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("header parser received no bytes"),
                "got: " + failure.getCause());
    }

    @Test
    void streamSetupHttp400IsNotRetried() {
        final var dial = new ScriptedDialect(new IOException("LLM request failed (400): bad request"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> wrapper.stream(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(1, dial.calls.get(), "a 400 must not be retried");
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("(400): bad request"),
                "got: " + failure.getCause());
    }

    @Test
    void streamSetupTyped400IsNotRetried() {
        final var dial = new ScriptedDialect(new LlmHttpFailureException(400, "boom", null));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> wrapper.stream(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(1, dial.calls.get(), "a typed 400 must not be retried");
        assertTrue(failure.getCause() instanceof LlmHttpFailureException, "got: " + failure.getCause());
    }

    @Test
    void cancelDuringRetryBackoffAbortsTheWaitWithoutReRequesting() throws Exception {
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30")); // 30s retry-after
        try (final var scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1)) {
            final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null, scheduler);
            final var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            final var future = wrapper.chat(dial, null, cancelled::get);
            // flip the probe far before the 30s wait elapses: the scheduler poll ends the wait early
            cancelled.set(true);
            final var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> future.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(
                    failure.getCause() instanceof IOException
                            && "Cancelled".equals(failure.getCause().getMessage()),
                    "got: " + failure.getCause());
            assertEquals(1, dial.calls.get(), "the cancelled wait must not re-request");
        }
    }

    @Test
    void cancelDuringStreamSetupBackoffAbortsTheWaitWithoutReRequesting() throws Exception {
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30"));
        try (final var scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1)) {
            final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null, scheduler);
            final var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            final var future = wrapper.stream(dial, null, cancelled::get);
            cancelled.set(true);
            final var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> future.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(
                    failure.getCause() instanceof IOException
                            && "Cancelled".equals(failure.getCause().getMessage()),
                    "got: " + failure.getCause());
            assertEquals(1, dial.calls.get(), "the cancelled stream-setup wait must not re-request");
        }
    }

    @Test
    void cancelAlreadySetWhenFailureLandsSkipsTheWait() throws Exception {
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30"));
        try (final var scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1)) {
            final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null, scheduler);
            final var cancelled = new java.util.concurrent.atomic.AtomicBoolean(true);
            final var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> wrapper.chat(dial, null, cancelled::get)
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS));
            assertTrue(
                    failure.getCause() instanceof IOException
                            && "Cancelled".equals(failure.getCause().getMessage()),
                    "got: " + failure.getCause());
            assertEquals(1, dial.calls.get(), "a canceled turn must not retry");
        }
    }

    @Test
    void cancelAlreadySetWhenFailureLandsSkipsTheWaitWithoutInjectedScheduler() throws Exception {
        // regression: without an injected scheduler the wait is a plain delayed executor and
        // the probe is not polled mid-wait, so an already-set cancel used to park the whole
        // retry-after (30s) before the post-wait check could see it
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);
        final var cancelled = new java.util.concurrent.atomic.AtomicBoolean(true);
        final var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> wrapper.chat(dial, null, cancelled::get)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS));
        assertTrue(
                failure.getCause() instanceof IOException
                        && "Cancelled".equals(failure.getCause().getMessage()),
                "got: " + failure.getCause());
        assertEquals(1, dial.calls.get(), "a canceled turn must not retry");
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
            final var call = calls.getAndIncrement();
            if (call < failures.length) {
                return failed(failures[call]);
            }
            // success: an SSE stream of a single "ok" fragment then end
            final var buffers = java.nio.ByteBuffer.wrap("data: {\"id\":\"1\",\"model\":\"m\",\"choices\":[]}\n\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(io.yupiik.llm.client.spi.Streams.toEvents(
                    subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(final long n) {
                            subscriber.onNext(List.of(buffers));
                            subscriber.onComplete();
                        }

                        @Override
                        public void cancel() {
                            // no-op
                        }
                    }),
                    () -> false,
                    event -> List.of(new LlmStreamEvent.TextDelta("ok")),
                    () -> new LlmStreamEvent.End("stop", null)));
        }

        @Override
        public CompletionStage<io.yupiik.llm.client.model.LlmModels> models() {
            return CompletableFuture.completedFuture(new io.yupiik.llm.client.model.LlmModels(List.of()));
        }

        @Override
        public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
            return failed(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
            return failed(new UnsupportedOperationException());
        }

        private static <A> CompletionStage<A> failed(final Throwable error) {
            final var future = new CompletableFuture<A>();
            future.completeExceptionally(error);
            return future;
        }
    }
}
