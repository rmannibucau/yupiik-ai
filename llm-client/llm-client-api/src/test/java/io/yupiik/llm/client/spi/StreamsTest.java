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

import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StreamsTest {

    @Test
    void multiByteUtf8SplitAcrossChunksIsDecodedIntact() throws Exception {
        final var full = ("data: héllo → 世界 ünïcödé ✓\r\n\n").getBytes(StandardCharsets.UTF_8);
        // split the SSE stream at arbitrary byte boundaries, including mid multi-byte sequence
        final var chunks = new ArrayList<List<ByteBuffer>>();
        for (var i = 0; i < full.length; i += 4) {
            final var end = Math.min(i + 4, full.length);
            chunks.add(List.of(ByteBuffer.wrap(Arrays.copyOfRange(full, i, end))));
        }

        final var events = collect(chunks, false);

        assertEquals(
                List.of(new LlmStreamEvent.TextDelta("héllo → 世界 ünïcödé ✓"), new LlmStreamEvent.End("stop", null)),
                events);
    }

    @Test
    void crlfLineEndingsAreTrimmed() throws Exception {
        final var chunks = List.of(
                List.of(ByteBuffer.wrap("data: one\r\n\r\n".getBytes(StandardCharsets.UTF_8))),
                List.of(ByteBuffer.wrap("data: two\r\n\r\n".getBytes(StandardCharsets.UTF_8))));
        final var events = collect(chunks, false);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("one"),
                        new LlmStreamEvent.TextDelta("two"),
                        new LlmStreamEvent.End("stop", null)),
                events);
    }

    @Test
    void unterminatedTailIsFedToTheParserWithoutDispatch() throws Exception {
        // SSE events only dispatch on a blank line; an unterminated tail is handed to the parser
        // (no crash, no partial mojibake) and the terminal End is still emitted
        final var chunks = List.of(List.of(ByteBuffer.wrap("data: tail".getBytes(StandardCharsets.UTF_8))));
        final var events = collect(chunks, false);
        assertEquals(List.of(new LlmStreamEvent.End("stop", null)), events);
    }

    private static List<LlmStreamEvent> collect(final List<List<ByteBuffer>> chunks, final boolean cancelled)
            throws InterruptedException {
        final var events = new CopyOnWriteArrayList<LlmStreamEvent>();
        final var done = new CountDownLatch(1);
        final SubmissionPublisher<List<ByteBuffer>> source = new SubmissionPublisher<>();
        Streams.toEvents(
                        source,
                        () -> cancelled,
                        event -> event.data() == null ? List.of() : List.of(new LlmStreamEvent.TextDelta(event.data())),
                        () -> new LlmStreamEvent.End("stop", null))
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {
                        events.add(item);
                    }

                    @Override
                    public void onError(final Throwable throwable) {
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });
        chunks.forEach(source::submit);
        source.close();
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("stream did not complete in time, got: " + events);
        }
        return events;
    }
}
