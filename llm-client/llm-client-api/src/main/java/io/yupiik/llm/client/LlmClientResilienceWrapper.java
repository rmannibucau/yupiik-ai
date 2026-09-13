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

import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.Dialect;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared resilience layer applied around a target {@link io.yupiik.llm.client.spi.Dialect} so the concurrency-throttling,
 * rate limiting and backoff logic is written once and reused by every resilient implementation
 * (typically {@link DialectLlmClient}).
 *
 * <p>Every operation acquires a concurrency permit (after a per-minute token-bucket throttle), runs
 * against the given target dialect, and retries recoverable failures with exponential backoff and
 * jitter capped at 60s, honoring a {@code retry-after=} delay from provider rate-limit errors when
 * enabled by {@link LlmResilienceConfig#retryAfterEnabled()}. {@link DialectNotSupportedException} and
 * {@link ContextWindowExceededException}
 * flow through; the former is surfacing a missing dialect to the caller (the router decides whether
 * to fall back elsewhere), the latter only when retry is enabled by config.</p>
 */
public final class LlmClientResilienceWrapper {

    private final LlmResilienceConfig config;
    private final Consumer<PauseEvent> pauseListener;
    private final Semaphore semaphore;
    private final java.util.ArrayDeque<Long> requestTimes;

    /**
     * Creates the wrapper with an explicit pause listener.
     *
     * @param config resilience knobs (defaults applied when {@code null}).
     * @param pauseListener listener notified of throttle/backoff pauses (may be {@code null}).
     */
    public LlmClientResilienceWrapper(final LlmResilienceConfig config, final Consumer<PauseEvent> pauseListener) {
        this.config = config == null ? LlmResilienceConfig.defaults() : config;
        this.pauseListener = pauseListener;
        this.semaphore = new Semaphore(Math.max(1, this.config.maxConcurrent()));
        this.requestTimes = new java.util.ArrayDeque<>();
    }

    /**
     * Creates the wrapper without a pause listener.
     *
     * @param config resilience knobs (defaults applied when {@code null}).
     */
    public LlmClientResilienceWrapper(final LlmResilienceConfig config) {
        this(config, null);
    }

    /**
     * Run a non-streaming completion against {@code dialect} with resilience applied.
     *
     * @param dialect the target dialect.
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return the completed response.
     */
    public CompletionStage<LlmResponse> chat(
            final Dialect dialect, final LlmRequest request, final BooleanSupplier cancelled) {
        return acquire()
                .thenCompose(_ -> attempt(dialect, request, cancelled))
                .whenComplete((result, error) -> semaphore.release());
    }

    /**
     * Stream a completion against {@code dialect} with resilience applied.
     *
     * @param dialect the target dialect.
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return a stage completing with the event publisher.
     */
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final Dialect dialect, final LlmRequest request, final BooleanSupplier cancelled) {
        return acquire().thenCompose(_unused -> {
            final var setup = dialect.stream(request, cancelled);
            setup.whenComplete((pub, error) -> {
                if (error != null) {
                    semaphore.release();
                }
            });
            return setup.thenApply(pub -> new ReleaseOnCompletePublisher<>(pub, semaphore));
        });
    }

    /**
     * List models against {@code dialect} with resilience applied.
     *
     * @param dialect the target dialect.
     * @return the listed models.
     */
    public CompletionStage<LlmModels> models(final Dialect dialect) {
        return acquire().thenCompose(_unused -> dialect.models().whenComplete((result, error) -> semaphore.release()));
    }

    private CompletionStage<LlmResponse> attempt(
            final Dialect dialect, final LlmRequest request, final BooleanSupplier cancelled) {
        final var result = new CompletableFuture<LlmResponse>();
        try {
            retry(dialect, request, cancelled, 1).whenComplete((response, error) -> {
                if (error != null) {
                    result.completeExceptionally(unwrap(error));
                } else {
                    result.complete(response);
                }
            });
        } catch (final RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private CompletionStage<LlmResponse> retry(
            final Dialect dialect, final LlmRequest request, final BooleanSupplier cancelled, final int attempt) {
        final var result = new CompletableFuture<LlmResponse>();
        final var future = dialect.chat(request, cancelled);
        future.whenComplete((response, error) -> {
            try {
                if (error != null) {
                    final var cause = unwrap(error);
                    boolean retry = false;
                    if (cause instanceof ContextWindowExceededException) {
                        retry = config.retryOnContextOverflow() && attempt < config.retriesMaxAttempts();
                    } else if (!(cause instanceof DialectNotSupportedException)) {
                        retry = attempt < config.retriesMaxAttempts() && retryable(cause);
                    }
                    if (retry) {
                        retryAfter(attempt, cause, dialect, request, cancelled)
                                .whenComplete((r, e) -> completeInto(result, r, e));
                    } else {
                        result.completeExceptionally(cause);
                    }
                } else if (response == null) {
                    result.completeExceptionally(new IOException("null response"));
                } else {
                    result.complete(response);
                }
            } catch (final RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /**
     * Whether an error is worth retrying: HTTP 408, 429 and 5xx failures are, other explicit
     * provider rejections (eg. 400) are surfaced to the caller immediately.
     */
    private static boolean retryable(final Throwable error) {
        final var m = error.getMessage();
        if (m == null || !m.startsWith("LLM request failed (")) {
            return true;
        }
        for (final var code : List.of(408, 429)) {
            if (m.startsWith("LLM request failed (" + code + ")")) {
                return true;
            }
        }
        for (var i = 500; i <= 599; i++) {
            if (m.startsWith("LLM request failed (" + i + ")")) {
                return true;
            }
        }
        return false;
    }

    private CompletionStage<LlmResponse> retryAfter(
            final int attempt,
            final Throwable lastError,
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled) {
        final var result = new CompletableFuture<LlmResponse>();
        final var jitter = (long) (ThreadLocalRandom.current().nextDouble(0.5d, 1.0d)
                * config.retriesBaseDelayMs()
                * Math.pow(2, attempt - 1));
        var waitMs = Math.min(jitter, 60_000L);
        if (config.retryAfterEnabled()) {
            final var seconds = retryAfterSeconds(lastError);
            if (seconds != null) {
                waitMs = Math.min(Math.max(waitMs, seconds * 1000L), 60_000L);
            }
        }
        if (pauseListener != null) {
            pauseListener.accept(new PauseEvent(attempt, lastError.getMessage(), waitMs));
        }
        CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS)
                .execute(() -> retry(dialect, request, cancelled, attempt + 1)
                        .whenComplete((r, e) -> completeInto(result, r, e)));
        return result;
    }

    /**
     * Extracts a {@code retry-after=} delay in seconds from a provider error message, as written by
     * the dialects on rate-limit responses.
     *
     * @param error the failure to inspect, may be {@code null}.
     * @return the delay in seconds, or {@code null} when absent or not a plain number (date form is
     *     ignored).
     */
    public static Long retryAfterSeconds(final Throwable error) {
        final var message = error == null ? null : error.getMessage();
        if (message == null) {
            return null;
        }
        final var i = message.indexOf("retry-after=");
        if (i < 0) {
            return null;
        }
        try {
            return Long.parseLong(message.substring(i + "retry-after=".length()).trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static <A> void completeInto(final CompletableFuture<A> target, final A value, final Throwable error) {
        if (error != null) {
            target.completeExceptionally(error);
        } else {
            target.complete(value);
        }
    }

    /** Throttle (token bucket) then acquire the concurrency permit; both interruptible. */
    private CompletionStage<Void> acquire() {
        try {
            throttle(null);
            semaphore.acquire();
            return completedFuture(null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(new IOException(e));
        }
    }

    private void throttle(final BooleanSupplier cancelled) throws InterruptedException {
        final var perMinute = config.rateLimitRequestsPerMinute();
        if (perMinute <= 0) {
            return;
        }
        final long windowNanos = 60_000_000_000L;
        while (true) {
            final long waitMs;
            synchronized (requestTimes) {
                final var now = System.nanoTime();
                while (!requestTimes.isEmpty() && now - requestTimes.peek() > windowNanos) {
                    requestTimes.poll();
                }
                if (requestTimes.size() < perMinute) {
                    requestTimes.add(now);
                    return;
                }
                waitMs = (requestTimes.peek() + windowNanos - now) / 1_000_000L;
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new InterruptedException("cancelled");
            }
            if (pauseListener != null) {
                pauseListener.accept(new PauseEvent(0, "throttle", Math.max(1, waitMs)));
            }
            sleepInterruptibly(Math.max(1, waitMs), cancelled);
        }
    }

    @SuppressWarnings("unused")
    private static void sleepInterruptibly(final long waitMs, final BooleanSupplier cancelled)
            throws InterruptedException {
        final var end = System.nanoTime() + waitMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new InterruptedException("cancelled");
            }
            final var remaining = (end - System.nanoTime()) / 1_000_000L;
            Thread.sleep(Math.min(remaining, 50));
        }
    }

    private static Throwable unwrap(final Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static <A> CompletableFuture<A> failed(final Throwable e) {
        final var future = new CompletableFuture<A>();
        future.completeExceptionally(e);
        return future;
    }

    private static <A> CompletableFuture<A> completedFuture(final A value) {
        return CompletableFuture.completedFuture(value);
    }

    /** Wraps a publisher to release the concurrency permit when the stream ends or is cancelled. */
    private record ReleaseOnCompletePublisher<T>(Flow.Publisher<T> delegate, Semaphore semaphore)
            implements Flow.Publisher<T> {

        @Override
        public void subscribe(final Flow.Subscriber<? super T> subscriber) {
            final var released = new CompletableFuture<Void>();
            delegate.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(final Flow.Subscription subscription) {
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(final T item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(final Throwable throwable) {
                    release();
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    release();
                    subscriber.onComplete();
                }

                private void release() {
                    if (released.complete(null)) {
                        semaphore.release();
                    }
                }
            });
        }
    }
}
