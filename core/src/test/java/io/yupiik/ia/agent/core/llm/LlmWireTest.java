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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.llm.client.model.LlmStreamEvent;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class LlmWireTest {

    @Test
    void coalescesStreamedToolCallFragments() throws Exception {
        final var events = List.<LlmStreamEvent>of(
                new LlmStreamEvent.ToolCallDelta(0, "c1", "webfetch", ""),
                new LlmStreamEvent.ToolCallDelta(0, "c1", null, "{\"url\":\"exa"),
                new LlmStreamEvent.ToolCallDelta(0, "c1", null, "mple.com\"}"),
                new LlmStreamEvent.ToolCallDelta(1, null, "bash", "{\"cmd\""),
                new LlmStreamEvent.ToolCallDelta(1, null, null, ":\"ls\"}"));

        final var message = LlmWire.pump(
                new Flow.Publisher<>() {
                    @Override
                    public void subscribe(final Flow.Subscriber<? super LlmStreamEvent> subscriber) {
                        subscriber.onSubscribe(new Flow.Subscription() {
                            private int cursor = -1;

                            @Override
                            public void request(final long n) {
                                while (cursor + 1 < events.size()) {
                                    subscriber.onNext(events.get(++cursor));
                                }
                                subscriber.onComplete();
                            }

                            @Override
                            public void cancel() {
                                // no-op
                            }
                        });
                    }
                },
                ignored -> {},
                null);

        final var calls = message.toolCalls();
        assertNotNull(calls);
        assertEquals(2, calls.size());

        final var first = calls.get(0);
        assertEquals(0, first.index());
        assertEquals("c1", first.id());
        assertNotNull(first.function());
        assertEquals("webfetch", first.function().name());
        assertEquals("{\"url\":\"example.com\"}", first.function().arguments());

        final var second = calls.get(1);
        assertEquals(1, second.index());
        assertNull(second.id());
        assertNotNull(second.function());
        assertEquals("bash", second.function().name());
        assertEquals("{\"cmd\":\"ls\"}", second.function().arguments());
    }
}
