/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event stream body: its framing, that publishing before anyone reads loses nothing, and that it completes.
 */
class SseBusTest {
    @Test
    void framing() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);

        // published before the HTTP layer subscribes: the events must still be written, in order
        bus.publish("{\"a\":1}");
        bus.publish("{\"b\":2}");

        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        bus.close();

        assertEquals("""
                        id: 1
                        event: message
                        data: {"a":1}

                        id: 2
                        event: message
                        data: {"b":2}

                        """,
                subscriber.text());
        assertTrue(subscriber.completed.get(), "the stream must complete so the response ends");
        assertEquals(1, closed.get(), "the stream must be deregistered exactly once");
    }

    @Test
    void keepAliveIsAComment() {
        final var bus = new SseBus(() -> {
        });
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        bus.keepAlive();

        // a comment commits the response without being an event a client would try to parse
        assertEquals(": ping\n\n", subscriber.text());
        assertFalse(subscriber.completed.get());
    }

    @Test
    void closingDoesNotDropWhatIsNotWrittenYet() {
        final var bus = new SseBus(() -> {
        });
        final var subscriber = new CollectingSubscriber();
        subscriber.autoRequest = false; // the HTTP layer has not asked for the next event yet
        bus.subscribe(subscriber);

        bus.publish("{\"artifact\":1}");
        bus.publish("{\"state\":\"COMPLETED\"}");
        bus.close();

        // only the first event could be written, and the stream must not be over: a terminal status is published right
        // before closing, dropping it would leave the caller waiting forever
        assertTrue(subscriber.text().contains("artifact"), subscriber::text);
        assertFalse(subscriber.text().contains("COMPLETED"), subscriber::text);
        assertFalse(subscriber.completed.get(), "the queued events are still owed to the client");

        subscriber.subscription.request(Long.MAX_VALUE);
        assertTrue(subscriber.text().contains("COMPLETED"), subscriber::text);
        assertTrue(subscriber.completed.get(), "and then it completes");
    }

    @Test
    void publishingOnAClosedBusIsIgnored() {
        final var bus = new SseBus(() -> {
        });
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        bus.close();

        assertEquals(-1, bus.publish("{\"late\":true}"));
        assertTrue(bus.isClosed());
        assertEquals("", subscriber.text());
        assertTrue(subscriber.completed.get());
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final List<String> chunks = new ArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean();

        private Flow.Subscription subscription;
        private boolean autoRequest = true;

        private String text() {
            return String.join("", chunks);
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(final ByteBuffer item) {
            chunks.add(UTF_8.decode(item).toString());
            if (autoRequest) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            throw new IllegalStateException(throwable);
        }

        @Override
        public void onComplete() {
            completed.set(true);
        }
    }
}
