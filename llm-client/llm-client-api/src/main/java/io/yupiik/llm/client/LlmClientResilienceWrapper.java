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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.Dialect;
import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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

    /** Cadence (ms) at which the cancellation probe is polled during a scheduled retry wait. */
    private static final long CANCEL_POLL_MS = 100L;

    private static final Pattern CONCURRENCY_LIMIT_JSON = Pattern.compile("\"limit\"\\s*:\\s*(\\d+)");
    private static final Pattern CONCURRENCY_FAILED = Pattern.compile("concurrency_limit_exceeded");
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final LlmResilienceConfig config;
    private final Consumer<PauseEvent> pauseListener;
    /**
     * Scheduler parking the retry waits and polling the cancellation probe; {@code null} falls
     * back to the JDK delayed executor where the probe is only checked before the wait and
     * again when it elapses (no mid-wait polling). Never shut down by the wrapper.
     */
    private final ScheduledExecutorService scheduler;
    /**
     * Clock used to decide whether a provider usage-limit window has really passed when the error
     * body carries {@code resets_at}. Injected by the caller (assumed non-null in the init path);
     * the {@code protected} subclass constructor leaves it null.
     */
    private final Clock clock;
    /**
     * Mapper used to softly parse a provider {@code 429} error body for {@code usage_limit_reached}.
     * May be {@code null}: without it the quota window is never inspected and every 429 keeps the
     * normal retry behavior.
     */
    private final JsonMapper mapper;
    /**
     * Concurrency gate. May be {@code null} while the configured cap is unlimited
     * (negative/zero): the first provider {@code concurrency_limit_exceeded} response
     * lazily creates it bounded by the announced limit so the adaptation still applies
     * even without a statically configured cap.
     */
    private volatile Semaphore semaphore;

    private final ArrayDeque<Long> requestTimes;
    private final ReentrantLock concurrencyLock;
    private int adaptedConcurrency = -1;
    private long adaptedUntilMs;

    /**
     * Creates the wrapper with explicit clock and mapper.
     *
     * @param config resilience knobs (defaults applied when {@code null}).
     * @param pauseListener listener notified of throttle/backoff pauses (may be {@code null}).
     * @param scheduler scheduler used to park retry waits and poll the cancellation probe; {@code null}
     *     (the default) falls back to the JDK delayed executor where cancellation is checked before
     *     the wait and when it elapses (no mid-wait polling). The scheduler is never shut down by the
     *     wrapper: as the application-wide one, its owner owns its lifecycle.
     * @param clock clock used to evaluate the {@code resets_at} of a {@code usage_limit_reached}
     *     {@code 429} body (assumed non-null).
     * @param mapper mapper used to softly parse a provider {@code 429} body for the usage-limit window;
     *     when {@code null} the window is never inspected and every 429 keeps the normal retry behavior.
     */
    public LlmClientResilienceWrapper(
            final LlmResilienceConfig config,
            final Consumer<PauseEvent> pauseListener,
            final ScheduledExecutorService scheduler,
            final Clock clock,
            final JsonMapper mapper) {
        this.config = config == null ? LlmResilienceConfig.defaults() : config;
        this.pauseListener = pauseListener;
        this.scheduler = scheduler;
        this.clock = clock;
        this.mapper = mapper;
        this.semaphore = this.config.maxConcurrent() > 0 ? new Semaphore(this.config.maxConcurrent()) : null;
        this.requestTimes = new ArrayDeque<>();
        this.concurrencyLock = new ReentrantLock();
    }

    /**
     * Creates the wrapper with explicit pause listener and scheduler, no usage-limit body inspection.
     *
     * @param config resilience knobs (defaults applied when {@code null}).
     * @param pauseListener listener notified of throttle/backoff pauses (may be {@code null}).
     * @param scheduler scheduler used to park retry waits and poll the cancellation probe; {@code null}
     *     (the default) falls back to the JDK delayed executor where cancellation is checked before
     *     the wait and when it elapses (no mid-wait polling). The scheduler is never shut down by the
     *     wrapper: as the application-wide one, its owner owns its lifecycle.
     */
    public LlmClientResilienceWrapper(
            final LlmResilienceConfig config,
            final Consumer<PauseEvent> pauseListener,
            final ScheduledExecutorService scheduler) {
        this(config, pauseListener, scheduler, null, null);
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
     * Creates the wrapper with an explicit pause listener.
     *
     * @param config resilience knobs (defaults applied when {@code null}).
     * @param pauseListener listener notified of throttle/backoff pauses (may be {@code null}).
     */
    public LlmClientResilienceWrapper(final LlmResilienceConfig config, final Consumer<PauseEvent> pauseListener) {
        this(config, pauseListener, null);
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
                .thenCompose(acquired ->
                        attempt(dialect, request, cancelled, pause).whenComplete((result, error) -> release(acquired)));
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
        // Failures while setting the stream up (headers/transport before the first fragment,
        // HTTP 429/5xx...) are transient and safe to retry: nothing was delivered yet, same
        // semantics as chat(). Once the publisher is handed out, mid-stream failures are the
        // caller's concern (it owns the partial content and decides whether to replay).
        return acquire(pause)
                .thenCompose(acquired -> retryStream(dialect, request, cancelled, 1, pause)
                        .whenComplete((publisher, error) -> {
                            if (error != null) {
                                release(acquired);
                            }
                        })
                        .thenApply(publisher -> new ReleaseOnCompletePublisher<>(publisher, semaphore, acquired)));
    }

    /**
     * List models against {@code dialect} with resilience applied.
     *
     * @param dialect the target dialect.
     * @return the listed models.
     */
    public CompletionStage<LlmModels> models(final Dialect dialect) {
        return acquire(pauseListener)
                .thenCompose(acquired -> dialect.models().whenComplete((result, error) -> release(acquired)));
    }

    private void release(final boolean acquired) {
        if (acquired && semaphore != null) {
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

    /** Hard upper bound for a server-provided {@code retry-after} floor (5 minutes). */
    static final long MAX_RETRY_AFTER_FLOOR_MS = 5L * 60_000;

    /**
     * Whether an error is worth retrying: HTTP 408, 429 and 5xx failures are, other explicit
     * provider rejections (eg. 400) are surfaced to the caller immediately. Typed failures are
     * classified from their status; legacy string-only failures fall back to message parsing.
     * A {@code 429} whose body reports {@code usage_limit_reached} with a recovery window beyond
     * the configured threshold is treated as non-retryable (see {@link #quotaExhaustedForLongWindow}).
     */
    private boolean retryable(final Throwable error) {
        if (error instanceof LlmHttpFailureException http) {
            return http.retryableStatus() && !quotaExhaustedForLongWindow(http);
        }
        // Legacy string-only failures: the "LLM request failed (429): ..." shape is matched by
        // status; anything else must be an explicitly transient transport message - a mis-parse or
        // provider rejection must NOT be retried as if it were a transient hiccup.
        final var m = error == null ? null : error.getMessage();
        if (m == null) {
            return false;
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
        final var lower = m.toLowerCase(Locale.ROOT);
        for (final var transientMarker : TRANSIENT_MESSAGES) {
            if (lower.contains(transientMarker)) {
                return true;
            }
        }
        return false;
    }

    /** Substrings marking a legacy string-only failure as a transient transport condition. */
    private static final List<String> TRANSIENT_MESSAGES = List.of(
            "timeout",
            "timed out",
            "timedout",
            "connect",
            "connection reset",
            "reset by peer",
            "read timed out",
            "write timed out",
            "connection refused",
            "unexpected end of file",
            "stream closed",
            "broken pipe",
            "no route to host",
            "sslhandshake",
            "ssl handshake",
            "network is unreachable",
            "host unreachable",
            "request timed out",
            // JDK/HTTP-client transport failures surfaced before any response line
            "header parser received no bytes");

    /**
     * Soft decision for a {@code 429}: when the body parses as JSON and the error type is
     * {@code usage_limit_reached} with a recovery window beyond the configured threshold (either a
     * {@code limit_window_minutes} greater than it, or a {@code resets_at} epoch further out from
     * the injected clock), a retry would still fail, the quota does not clear in retry-seconds, so
     * report {@code true} (not worth retrying). Any unparseable body, missing field, short window,
     * other error type, or a {@code null} mapper falls back to the normal retry behavior ({@code false}).
     *
     * <p>Mirrors the Codex error shape
     * {@code {"error":{"type":"usage_limit_reached","limit_window_minutes":43200,"resets_in_seconds":...,"resets_at":...}}}.</p>
     *
     * @param http the typed failure carrying the {@code 429} status and raw body.
     * @return {@code true} when the quota window is long enough that retrying is pointless.
     */
    private boolean quotaExhaustedForLongWindow(final LlmHttpFailureException http) {
        if (http.status() != 429 || mapper == null || clock == null) {
            return false;
        }
        final var body = http.body();
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            final var error =
                    (Map<String, Object>) mapper.fromString(Map.class, body).get("error");
            if (error == null || !"usage_limit_reached".equals(error.get("type"))) {
                return false;
            }
            final var nowSeconds = clock.millis() / 1000L;
            final var thresholdSeconds = config.usageLimitRetryThresholdMs() / 1000L;
            final var windowMinutes = number(error.get("limit_window_minutes"));
            final var resetsAt = number(error.get("resets_at"));
            return (windowMinutes != null && TimeUnit.MINUTES.toSeconds(windowMinutes) > thresholdSeconds)
                    || (resetsAt != null && resetsAt - nowSeconds > thresholdSeconds);
        } catch (final RuntimeException e) {
            return false; // unparseable or unexpected shape: ignore the body, keep current behavior
        }
    }

    private static Long number(final Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /**
     * Retries a failing stream setup (the {@code CompletionStage<Publisher>} itself failed before
     * any event was delivered) with the same policy as {@link #retry(Dialect, LlmRequest,
     * BooleanSupplier, int, Consumer)}: transient transport and 5xx/429 failures are retried with
     * exponential backoff, typed context/dialect failures keep their own semantics. Once the
     * publisher is produced the permit is handed to {@link ReleaseOnCompletePublisher}, and
     * mid-stream failures belong to the caller (partial content makes a blind replay unsafe).
     */
    private CompletionStage<Flow.Publisher<LlmStreamEvent>> retryStream(
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final int attempt,
            final Consumer<PauseEvent> listener) {
        final var result = new CompletableFuture<Flow.Publisher<LlmStreamEvent>>();
        try {
            dialect.stream(request, cancelled).whenComplete((publisher, error) -> {
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
                            retryStreamAfter(attempt, cause, dialect, request, cancelled, listener)
                                    .whenComplete((r, e) -> completeInto(result, r, e));
                        } else {
                            result.completeExceptionally(cause);
                        }
                    } else if (publisher == null) {
                        result.completeExceptionally(new IOException("null stream"));
                    } else {
                        result.complete(publisher);
                    }
                } catch (final RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (final RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private CompletionStage<Flow.Publisher<LlmStreamEvent>> retryStreamAfter(
            final int attempt,
            final Throwable lastError,
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> listener) {
        final var result = new CompletableFuture<Flow.Publisher<LlmStreamEvent>>();
        final var wait = retryWait(attempt, lastError);
        if (listener != null) {
            listener.accept(new PauseEvent(
                    attempt,
                    lastError.getMessage() + " -> wait " + (wait.waitMs() / 1000) + "s (" + wait.reason() + ")",
                    wait.waitMs()));
        }
        cancellableWait(wait.waitMs(), cancelled).whenComplete((elapsed, waitError) -> {
            if (waitError != null) {
                completeInto(result, null, waitError);
                return;
            }
            retryStream(dialect, request, cancelled, attempt + 1, listener)
                    .whenComplete((r, e) -> completeInto(result, r, e));
        });
        return result;
    }

    /** Backoff (with jitter) and {@code retry-after}/{@code concurrency-limit} honoring for one wait. */
    private RetryWait retryWait(final int attempt, final Throwable lastError) {
        final var jitter = (long) (ThreadLocalRandom.current().nextDouble(0.5d, 1.0d)
                * config.retriesBaseDelayMs()
                * Math.pow(2, attempt - 1));
        var waitMs = Math.min(jitter, config.retryAfterMaxWaitMs());
        var waitReason = "backoff";
        if (config.retryAfterEnabled()) {
            final var seconds = retryAfterSeconds(lastError);
            if (seconds != null) {
                // a server-provided retry-after is a FLOOR, not a candidate: the provider knows
                // when it can serve us again, so wait at least that long (only the jitter-derived
                // portion stays capped). A hard 5-minute bound still protects against absurd
                // server-suggested delays.
                waitMs = Math.max(waitMs, Math.min(seconds * 1000L, MAX_RETRY_AFTER_FLOOR_MS));
                waitReason = "retry-after=" + seconds + "s";
            } else if (isConcurrencyLimit(lastError)) {
                // no explicit header on a concurrency limit: wait a sensible default so the
                // provider has time to release the concurrent slots
                waitMs = Math.min(Math.max(waitMs, CONCURRENCY_LIMIT_DEFAULT_WAIT_MS), config.retryAfterMaxWaitMs());
                waitReason = "concurrency-limit (no retry-after)";
            }
        }
        return new RetryWait(waitMs, waitReason);
    }

    private record RetryWait(long waitMs, String reason) {}

    private CompletionStage<LlmResponse> retryAfter(
            final int attempt,
            final Throwable lastError,
            final Dialect dialect,
            final LlmRequest request,
            final BooleanSupplier cancelled,
            final Consumer<PauseEvent> listener) {
        final var result = new CompletableFuture<LlmResponse>();
        final var wait = retryWait(attempt, lastError);
        final var reason = lastError.getMessage() + " -> wait " + (wait.waitMs() / 1000) + "s (" + wait.reason() + ")";
        if (listener != null) {
            listener.accept(new PauseEvent(attempt, reason, wait.waitMs()));
        }
        cancellableWait(wait.waitMs(), cancelled).whenComplete((elapsed, waitError) -> {
            if (waitError != null) {
                completeInto(result, null, waitError);
                return;
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                // do not restart a retry the user already cancelled
                completeInto(result, null, new IOException("Cancelled"));
                return;
            }
            retry(dialect, request, cancelled, attempt + 1, listener)
                    .whenComplete((r, e) -> completeInto(result, r, e));
        });
        return result;
    }

    /**
     * Parks one retry wait without ever blocking a caller thread: a stage completing (normally)
     * once {@code waitMs} elapsed, or exceptionally with {@code IOException("Cancelled")} as soon
     * as the cancellation probe flips. With an injected scheduler the wait is scheduled there and
     * the probe polled on a short cadence from the same pool; without one the JDK delayed executor
     * is used and the probe is only checked when the wait elapses (legacy behavior).
     */
    private CompletionStage<Boolean> cancellableWait(final long waitMs, final BooleanSupplier cancelled) {
        final var waitMsScaled = Math.max(1L, (long) (waitMs * config.waitScale()));
        final var done = new CompletableFuture<Boolean>();
        // an already-cancelled turn must never pay the wait: fail synchronously before scheduling
        // anything. Without this, an already-set cancel still parked the full retry-after delay
        // (a retry-after=30s cost 30s) before the post-wait check in retryAfter/retryStreamAfter
        // could see it.
        if (cancelled != null && cancelled.getAsBoolean()) {
            done.completeExceptionally(new IOException("Cancelled"));
            return done;
        }
        if (scheduler == null) {
            CompletableFuture.delayedExecutor(waitMsScaled, TimeUnit.MILLISECONDS)
                    .execute(() -> done.complete(Boolean.TRUE));
            return done;
        }
        final var fire = scheduler.schedule(() -> done.complete(Boolean.TRUE), waitMsScaled, TimeUnit.MILLISECONDS);
        // the probe must end the wait early: poll it on a fixed short cadence, cancel the
        // pending fire task when cancellation wins so no orphaned task stays scheduled
        final var poll = scheduler.scheduleAtFixedRate(
                () -> {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        done.completeExceptionally(new IOException("Cancelled"));
                    }
                },
                0,
                CANCEL_POLL_MS,
                TimeUnit.MILLISECONDS);
        done.whenComplete((value, error) -> {
            poll.cancel(false);
            fire.cancel(false);
        });
        return done;
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
            final var date = ZonedDateTime.parse(token, HTTP_DATE);
            final var delay = date.toInstant().toEpochMilli() - System.currentTimeMillis();
            return delay > 0 ? (delay + 999) / 1000 : 0L;
        } catch (final DateTimeParseException e) {
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
        final boolean alreadyAdapted;
        concurrencyLock.lock();
        try {
            // with an unlimited configured cap the gate is null: lazily create it bounded
            // by the provider-announced limit so the adaptation applies even then
            if (semaphore == null && config.maxConcurrent() <= 0) {
                semaphore = new Semaphore(Math.max(1, limit));
            }
            final var current = semaphore == null ? Integer.MAX_VALUE : semaphore.availablePermits();
            if (semaphore != null && (limit < current || limit < config.maxConcurrent())) {
                while (semaphore.availablePermits() > limit) {
                    semaphore.tryAcquire();
                }
            }
            // already adapted to this exact limit and the window has not relaxed yet:
            // a repeated 429 while the cap is active is absorbed silently - only the
            // initial adaptation (or a *changed* limit) is worth surfacing once.
            alreadyAdapted = adaptedConcurrency == Math.max(1, limit) && System.currentTimeMillis() < adaptedUntilMs;
            if (!alreadyAdapted) {
                adaptedConcurrency = Math.max(1, limit);
                adaptedUntilMs = System.currentTimeMillis() + config.concurrencyAdaptationMs();
            }
        } finally {
            concurrencyLock.unlock();
        }
        if (!alreadyAdapted && listener != null) {
            listener.accept(new PauseEvent(
                    0, "concurrency limit " + limit + " detected for this provider, capping concurrent requests", 0));
        }
    }

    private void relaxAdaptedConcurrency() {
        if (semaphore == null || adaptedConcurrency <= 0) {
            return;
        }
        concurrencyLock.lock();
        try {
            if (adaptedConcurrency <= 0 || System.currentTimeMillis() < adaptedUntilMs) {
                return;
            }
            if (config.maxConcurrent() > 0) {
                // restore up to the configured cap
                while (semaphore.availablePermits() < config.maxConcurrent()) {
                    semaphore.release();
                }
            } else {
                // the cap was provider-adapted only: drop the gate back to unlimited
                semaphore = null;
            }
            adaptedConcurrency = -1;
            adaptedUntilMs = 0;
        } finally {
            concurrencyLock.unlock();
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
    private CompletionStage<Boolean> acquire(final Consumer<PauseEvent> listener) {
        try {
            throttle(null, listener);
            relaxAdaptedConcurrency();
            if (semaphore != null) {
                // a concurrency wait is invisible to the UI otherwise: surface it once per
                // blocked attempt through the same pause listener as throttling/backoffs
                if (semaphore.availablePermits() == 0 && listener != null) {
                    listener.accept(new PauseEvent(
                            0,
                            "concurrency limit reached, " + (semaphore.getQueueLength() + 1) + " requests waiting",
                            0));
                }
                semaphore.acquire();
                return completedFuture(true);
            }
            // no gate: there is nothing to release later, so the caller must know the permit
            // was never taken (a release() without an acquire would inflate the gate above its
            // cap once concurrencyLimitDetected() lazily creates it mid-flight)
            return completedFuture(false);
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
    private record ReleaseOnCompletePublisher<T>(Flow.Publisher<T> delegate, Semaphore semaphore, boolean acquired)
            implements Flow.Publisher<T> {

        @Override
        public void subscribe(final Flow.Subscriber<? super T> subscriber) {
            final var released = new CompletableFuture<Void>();
            delegate.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(final Flow.Subscription subscription) {
                    // a cancelled stream never delivers onError/onComplete upstream (the caller
                    // abandons it by calling cancel() mid-stream), so the permit would leak:
                    // cancel() releases it exactly once, like the terminal callbacks below.
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(final long n) {
                            subscription.request(n);
                        }

                        @Override
                        public void cancel() {
                            release();
                            subscription.cancel();
                        }
                    });
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
                    if (released.complete(null) && acquired && semaphore != null) {
                        semaphore.release();
                    }
                }
            });
        }
    }
}
