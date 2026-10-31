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

import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns a raw SSE byte-stream {@link Flow.Publisher} into a {@link Flow.Publisher} of
 * {@link LlmStreamEvent}. Each streamed line is fed to a {@link SseParser}; the supplied mapper turns
 * every parsed event into zero or more neutral events, and the supplier provides the terminal End
 * event (or null when the dialect has no surfacing summary).
 *
 * <p>The stream supports cooperative cancellation through the supplied {@link BooleanSupplier}; once
 * it returns true the subscription is cancelled and only an End event is emitted.</p>
 */
public final class Streams {

    private Streams() {
        // no-op utility
    }

    /**
     * Turns a raw SSE byte-stream publisher into a publisher of neutral stream events.
     *
     * @param source the raw SSE byte publisher.
     * @param cancelled cancellation probe checked between frames.
     * @param mapper per-event translation into zero or more neutral events.
     * @param end terminal End event supplier, or null when the dialect has no surfacing summary.
     * @return the publisher of neutral stream events.
     */
    public static Flow.Publisher<LlmStreamEvent> toEvents(
            final Flow.Publisher<List<ByteBuffer>> source,
            final BooleanSupplier cancelled,
            final Function<SseParser.Event, List<LlmStreamEvent>> mapper,
            final Supplier<LlmStreamEvent> end) {
        return toEvents(source, cancelled, mapper, end, null);
    }

    /**
     * Turns a raw SSE byte-stream publisher into a publisher of neutral stream events.
     *
     * @param source the raw SSE byte publisher.
     * @param cancelled cancellation probe checked between frames.
     * @param mapper per-event translation into zero or more neutral events.
     * @param end terminal End event supplier, or null when the dialect has no surfacing summary.
     * @param onRawLine optional tee for every raw SSE line (before parsing), or null; useful to log
     *     the wire, e.g. to observe tool-call fragments on the streaming responses API.
     * @return the publisher of neutral stream events.
     */
    public static Flow.Publisher<LlmStreamEvent> toEvents(
            final Flow.Publisher<List<ByteBuffer>> source,
            final BooleanSupplier cancelled,
            final Function<SseParser.Event, List<LlmStreamEvent>> mapper,
            final Supplier<LlmStreamEvent> end,
            final Consumer<String> onRawLine) {
        return subscriber -> source.subscribe(new Flow.Subscriber<>() {
            private final SseParser parser = new SseParser();
            // raw pending bytes: UTF-8 sequences split across network chunks stay here until the
            // next newline arrives, and lines are cut on the byte scan (0x0A never appears inside
            // a multi-byte UTF-8 sequence, so byte-level splitting is safe)
            private byte[] pending = new byte[1024];
            private int pendingLength;
            private volatile boolean aborted;

            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final List<ByteBuffer> item) {
                if (aborted) {
                    return;
                }
                for (final var buffer : item) {
                    append(buffer);
                }
                processLines(false);
            }

            @Override
            public void onError(final Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                processLines(true);
                final var endEvent = end.get();
                if (endEvent != null && !aborted) {
                    subscriber.onNext(endEvent);
                }
                subscriber.onComplete();
            }

            private void append(final ByteBuffer src) {
                final var needed = pendingLength + src.remaining();
                if (needed > pending.length) {
                    var capacity = pending.length;
                    while (capacity < needed) {
                        capacity = capacity <= 0 ? 1024 : capacity * 2;
                    }
                    pending = Arrays.copyOf(pending, capacity);
                }
                src.get(pending, pendingLength, src.remaining());
                pendingLength = needed;
            }

            private void processLines(final boolean flushRest) {
                var scan = 0;
                while (!aborted) {
                    var newline = -1;
                    for (var i = scan; i < pendingLength; i++) {
                        if (pending[i] == '\n') {
                            newline = i;
                            break;
                        }
                    }
                    if (newline < 0) {
                        if (flushRest) {
                            if (pendingLength > scan) {
                                handleRaw(trimCr(
                                        new String(pending, scan, pendingLength - scan, StandardCharsets.UTF_8)));
                                scan = pendingLength;
                            }
                            // a final event without a trailing blank line (some backends close the
                            // stream right after the last data line) must still dispatch when it is
                            // a complete JSON frame (terminal usage/response.completed); a tail cut
                            // mid-line is truncated data and must NOT be emitted (it would corrupt
                            // tool-call JSON with a partial payload)
                            if (parser.hasCompleteJsonPayload()) {
                                emit(parser.feed(null));
                            }
                        }
                        break;
                    }
                    handleRaw(trimCr(new String(pending, scan, newline - scan, StandardCharsets.UTF_8)));
                    scan = newline + 1;
                    if (cancelled.getAsBoolean()) {
                        aborted = true;
                    }
                }
                if (scan > 0) {
                    System.arraycopy(pending, scan, pending, 0, pendingLength - scan);
                    pendingLength -= scan;
                }
            }

            private void handleRaw(final String rawLine) {
                if (onRawLine != null) {
                    onRawLine.accept(rawLine);
                }
                emit(parser.feed(rawLine));
            }

            private void emit(final List<SseParser.Event> events) {
                for (final var event : events) {
                    final var mapped = mapper.apply(event);
                    if (mapped == null || mapped.isEmpty()) {
                        continue;
                    }
                    final var toEmit = new ArrayList<LlmStreamEvent>(mapped.size());
                    for (final var e : mapped) {
                        if (!aborted && cancelled.getAsBoolean()) {
                            aborted = true;
                        }
                        if (!aborted) {
                            toEmit.add(e);
                        }
                    }
                    toEmit.forEach(subscriber::onNext);
                }
            }

            private static String trimCr(final String s) {
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
        });
    }
}
