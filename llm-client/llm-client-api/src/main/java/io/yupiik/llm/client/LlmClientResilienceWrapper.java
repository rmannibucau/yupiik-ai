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

    /** Default wait when a concurrency-limit error carries no {@code retry-after} header. */
    static final long CONCURRENCY_LIMIT_DEFAULT_WAIT_MS = 10_000L;

    /** Cooldown before relaxing a concurrency limit adapted from a provider response. */
    static final long CONCURRENCY_LIMIT_RELAX_MS = 60_000L;

    private static final java.util.regex.Pattern CONCURRENCY_LIMIT_JSON =
            java.util.regex.Pattern.compile("\"limit\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern CONCURRENCY_FAILED =
            java.util.regex.Pattern.compile("concurrency_limit_exceeded");
    private static final java.time.format.DateTimeFormatter HTTP_DATE =
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

    private final LlmResilienceConfig config;
    private final Consumer<PauseEvent> pauseListener;
    private final Semaphore semaphore;
    private final java.util.ArrayDeque<Long> requestTimes;
    private final Object concurrencyLock = new Object();
    private int adaptedConcurrency = -1;
    private long adaptedUntilMs;

    /**
     * Creates the wrapper with an explicit pause listener.
     *
     * @param config resilience knobs (defaults applied when {@code null}).
     * @param pauseListener listener notified of throttle/backoff pauses (may be {@code null}).
     */
    public LlmClientResilienceWrapper(final LlmResilienceConfig config, final Consumer<PauseEvent> pauseListener) {
        this.config = config == null ? LlmResilienceConfig.defaults() : config;
        this.pauseListener = pauseListener;
        this.semaphore = this.config.maxConcurrent() > 0 ? new Semaphore(this.config.maxConcurrent()) : null;
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
        return chat(dialect, request, cancelled, null);
    }

    /**
     * Run a non-streaming completion with a pause listener scoped to this call only: throttle,
     * concurrency-cap and retry-backoff notifications go to {@code listener} instead of the
     * process-wide one. Falls back to the constructor listener when {@code listener} is {@code null}.
     * This is how concurrent callers (several tabs/sessions) each see only their own pauses.
     */
    public CompletionStage<LlmResponse> chat(
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> listener) {
        final var pause = listener == null ? pauseListener : listener;
        return acquire(pause)
                .thenCompose(_ -> attempt(dialect, request, cancelled, pause))
                .whenComplete((result, error) -> release());
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
        return stream(dialect, request, cancelled, null);
    }

    /**
     * Stream a completion with a pause listener scoped to this call only (see
     * {@link #chat(Dialect, LlmRequest, BooleanSupplier, Consumer)}).
     */
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> listener) {
        final var pause = listener == null ? pauseListener : listener;
        return acquire(pause).thenCompose(_unused -> {
            final var setup = dialect.stream(request, cancelled);
            setup.whenComplete((pub, error) -> {
                if (error != null) {
                    release();
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
        return acquire(pauseListener)
                .thenCompose(_unused -> dialect.models().whenComplete((result, error) -> release()));
    }

    private void release() {
        if (semaphore != null) {
            semaphore.release();
        }
    }

    private CompletionStage<LlmResponse> attempt(
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> listener) {
        final var result = new CompletableFuture<LlmResponse>();
        try {
            retry(dialect, request, cancelled, 1, listener).whenComplete((response, error) -> {
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
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final int attempt,
            final Consumer<PauseEvent> listener) {
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
                        concurrencyLimitDetected(cause, listener);
                        retryAfter(attempt, cause, dialect, request, cancelled, listener)
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
     * provider rejections (eg. 400) are surfaced to the caller immediately. Typed failures are
     * classified from their status; legacy string-only failures fall back to message parsing.
     */
    private static boolean retryable(final Throwable error) {
        if (error instanceof LlmHttpFailureException http) {
            return http.retryableStatus();
        }
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
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> listener) {
        final var result = new CompletableFuture<LlmResponse>();
        final var jitter = (long) (ThreadLocalRandom.current().nextDouble(0.5d, 1.0d)
                * config.retriesBaseDelayMs()
                * Math.pow(2, attempt - 1));
        var waitMs = Math.min(jitter, config.retryAfterMaxWaitMs());
        var waitReason = "backoff";
        if (config.retryAfterEnabled()) {
            final var seconds = retryAfterSeconds(lastError);
            if (seconds != null) {
                waitMs = Math.min(Math.max(waitMs, seconds * 1000L), config.retryAfterMaxWaitMs());
                waitReason = "retry-after=" + seconds + "s";
            } else if (isConcurrencyLimit(lastError)) {
                // no explicit header on a concurrency limit: wait a sensible default so the
                // provider has time to release the concurrent slots
                waitMs = Math.min(Math.max(waitMs, CONCURRENCY_LIMIT_DEFAULT_WAIT_MS), config.retryAfterMaxWaitMs());
                waitReason = "concurrency-limit (no retry-after)";
            }
        }
        final var reason = lastError.getMessage() + " -> wait " + (waitMs / 1000) + "s (" + waitReason + ")";
        if (listener != null) {
            listener.accept(new PauseEvent(attempt, reason, waitMs));
        }
        CompletableFuture.delayedExecutor(Math.max(1L, (long) (waitMs * config.waitScale())), TimeUnit.MILLISECONDS)
                .execute(() -> retry(dialect, request, cancelled, attempt + 1, listener)
                        .whenComplete((r, e) -> completeInto(result, r, e)));
        return result;
    }

    /**
     * Extracts a {@code retry-after=} delay in seconds from a provider error. Typed failures read
     * the header directly; legacy string-only failures parse the {@code retry-after=} suffix the
     * dialects append to the message.
     *
     * @param error the failure to inspect, may be {@code null}.
     * @return the delay in seconds, or {@code null} when absent, not a plain number and not an
     *     HTTP-date header value.
     */
    public static Long retryAfterSeconds(final Throwable error) {
        if (error instanceof LlmHttpFailureException http
                && http.retryAfterHeader() != null
                && !http.retryAfterHeader().isBlank()) {
            return parseRetryAfter(http.retryAfterHeader().trim());
        }
        final var message = error == null ? null : error.getMessage();
        if (message == null) {
            return null;
        }
        final var i = message.lastIndexOf("retry-after=");
        if (i < 0) {
            return null;
        }
        return parseRetryAfter(message.substring(i + "retry-after=".length()).trim());
    }

    private static Long parseRetryAfter(final String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        final var digits = new StringBuilder();
        for (final var c : token.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (!digits.isEmpty()) {
            try {
                return Long.parseLong(digits.toString());
            } catch (final NumberFormatException e) {
                // fall through to the date parsing below
            }
        }
        // HTTP-date form: retry-after: Sat, 25 Dec 2099 15:00:00 GMT
        try {
            final var date = java.time.ZonedDateTime.parse(token, HTTP_DATE);
            final var delay = date.toInstant().toEpochMilli() - System.currentTimeMillis();
            return delay > 0 ? (delay + 999) / 1000 : 0L;
        } catch (final java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Whether the provider error is a {@code concurrency_limit_exceeded} response.
     *
     * @param error the failure to inspect, may be {@code null}.
     * @return whether the message carries the concurrency limit marker.
     */
    public static boolean isConcurrencyLimit(final Throwable error) {
        final var message = error == null ? null : error.getMessage();
        return message != null && CONCURRENCY_FAILED.matcher(message).find();
    }

    /**
     * The concurrency limit announced by the provider in a {@code concurrency_limit_exceeded}
     * response, when the JSON body carries a {@code "limit": N} field.
     *
     * @param error the failure to inspect, may be {@code null}.
     * @return the announced limit, or {@code null} when absent.
     */
    public static Integer concurrencyLimit(final Throwable error) {
        final var message = error == null ? null : error.getMessage();
        if (message == null || !CONCURRENCY_FAILED.matcher(message).find()) {
            return null;
        }
        final var m = CONCURRENCY_LIMIT_JSON.matcher(message);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private void concurrencyLimitDetected(final Throwable error, final Consumer<PauseEvent> listener) {
        final Integer limit = concurrencyLimit(error);
        if (limit == null || limit <= 0) {
            return;
        }
        synchronized (concurrencyLock) {
            final var current = semaphore == null ? Integer.MAX_VALUE : semaphore.availablePermits();
            if (limit < current || limit < config.maxConcurrent()) {
                if (semaphore != null) {
                    while (semaphore.availablePermits() > limit) {
                        semaphore.tryAcquire();
                    }
                }
            }
            adaptedConcurrency = Math.max(1, limit);
            adaptedUntilMs = System.currentTimeMillis() + CONCURRENCY_LIMIT_RELAX_MS;
        }
        if (listener != null) {
            listener.accept(new PauseEvent(
                    0, "concurrency limit " + limit + " detected for this provider, capping concurrent requests", 0));
        }
    }

    private void relaxAdaptedConcurrency() {
        if (semaphore == null || config.maxConcurrent() <= 0) {
            return;
        }
        synchronized (concurrencyLock) {
            if (adaptedConcurrency <= 0 || System.currentTimeMillis() < adaptedUntilMs) {
                return;
            }
            // restore up to the configured cap
            while (semaphore.availablePermits() < config.maxConcurrent()) {
                semaphore.release();
            }
            adaptedConcurrency = -1;
            adaptedUntilMs = 0;
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
    private CompletionStage<Void> acquire(final Consumer<PauseEvent> listener) {
        try {
            throttle(null, listener);
            relaxAdaptedConcurrency();
            if (semaphore != null) {
                semaphore.acquire();
            }
            return completedFuture(null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(new IOException(e));
        }
    }

    private void throttle(final BooleanSupplier cancelled, final Consumer<PauseEvent> listener)
            throws InterruptedException {
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
            if (listener != null) {
                listener.accept(new PauseEvent(0, "throttle", Math.max(1, waitMs)));
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
                    if (released.complete(null) && semaphore != null) {
                        semaphore.release();
                    }
                }
            });
        }
    }
}
