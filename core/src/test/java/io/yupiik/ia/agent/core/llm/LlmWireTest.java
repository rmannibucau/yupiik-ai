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

import io.yupiik.ia.agent.core.runtime.Cancellations;
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
                Cancellations.NEVER);

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

    /**
     * The Responses dialect emits the id + name on a leading {@code output_item.added} fragment and
     * then only null-id/null-name argument fragments (the coalescing must keep a single call per
     * index — this is the shape captured live from freeinference.org/v1/responses).
     */
    @Test
    void coalescesResponsesStyleToolCallFragments() throws Exception {
        // shape captured live from freeinference.org/v1/responses: output_item.added/done carry the
        // id + name, function_call_arguments.delta fragments carry only argument json and a null name.
        final var events = List.<LlmStreamEvent>of(
                new LlmStreamEvent.ToolCallDelta(0, "fc_abc", "search", null),
                new LlmStreamEvent.ToolCallDelta(0, null, null, "{\"q\""),
                new LlmStreamEvent.ToolCallDelta(0, null, null, ": \"weather paris\"}"),
                new LlmStreamEvent.ToolCallDelta(0, "fc_abc", "search", null));

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
                Cancellations.NEVER);

        final var calls = message.toolCalls();
        assertNotNull(calls);
        assertEquals(1, calls.size());

        final var first = calls.get(0);
        assertEquals(0, first.index());
        assertEquals("fc_abc", first.id());
        assertNotNull(first.function());
        assertEquals("search", first.function().name());
        assertEquals("{\"q\": \"weather paris\"}", first.function().arguments());
    }

    /**
     * Captures the live Responses split that relied on id-only coalescing: the item events report a
     * different {@code output_index} than the argument delta fragments, so the same logical call (same
     * id) arrives under two indexes and must still collapse to a single tool call.
     */
    @Test
    void coalescesResponsesCallAcrossVaryingIndexes() throws Exception {
        final var events = List.<LlmStreamEvent>of(
                new LlmStreamEvent.ToolCallDelta(1, "fc_1decd0", "search", null),
                new LlmStreamEvent.ToolCallDelta(0, "fc_1decd0", null, "{\"q\""),
                new LlmStreamEvent.ToolCallDelta(0, "fc_1decd0", null, ": \"weather paris\"}"),
                new LlmStreamEvent.ToolCallDelta(1, "fc_1decd0", "search", null));

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
                Cancellations.NEVER);

        final var calls = message.toolCalls();
        assertNotNull(calls);
        assertEquals(1, calls.size());

        final var first = calls.get(0);
        assertEquals("fc_1decd0", first.id());
        assertNotNull(first.function());
        assertEquals("search", first.function().name());
        assertEquals("{\"q\": \"weather paris\"}", first.function().arguments());
    }

    /**
     * Two tool calls in one stream, interleaved by their real output_index (as the Responses dialect
     * now delivers after carrying output_index on the argument fragments). This guards against the
     * hardcoded-index-0 regression that dropped the leading arguments (eg. bash 'command') by
     * merging argument fragments from distinct calls into the wrong partial.
     */
    @Test
    void keepsTwoInterleavedToolCallsSepared() throws Exception {
        final var events = List.<LlmStreamEvent>of(
                new LlmStreamEvent.ToolCallDelta(0, "fc_list", "list_dir", null),
                new LlmStreamEvent.ToolCallDelta(0, "fc_list", null, "{\"path\":\"s"),
                new LlmStreamEvent.ToolCallDelta(1, "fc_bash", "bash", null),
                new LlmStreamEvent.ToolCallDelta(1, "fc_bash", null, "{\"command\":\"ls"),
                new LlmStreamEvent.ToolCallDelta(1, "fc_bash", null, "\"}"),
                new LlmStreamEvent.ToolCallDelta(0, "fc_list", null, "rc\"}"));

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
                Cancellations.NEVER);

        final var calls = new java.util.TreeMap<Integer, ToolCall>();
        final var received = message.toolCalls();
        assertNotNull(received);
        for (final var call : received) {
            calls.put(call.index(), call);
        }
        assertEquals(2, calls.size());

        final var listCall = calls.get(0);
        assertEquals("fc_list", listCall.id());
        assertEquals("list_dir", listCall.function().name());
        assertEquals("{\"path\":\"src\"}", listCall.function().arguments());

        final var bashCall = calls.get(1);
        assertEquals("fc_bash", bashCall.id());
        assertEquals("bash", bashCall.function().name());
        assertEquals("{\"command\":\"ls\"}", bashCall.function().arguments());
    }

    /**
     * llama.cpp-style servers reuse the fragment index (0) across parallel tool calls while giving
     * each call its own id: without the id-first collision rule the second call's arguments were
     * appended to the first call, producing concatenated JSON objects that providers reject with a
     * 500 when they re-parse the arguments of the assistant history.
     */
    @Test
    void reusedFragmentIndexWithDistinctIdsKeepsParallelCallsSeparate() throws Exception {
        final var events = List.<LlmStreamEvent>of(
                new LlmStreamEvent.ToolCallDelta(0, "call_a", "grep", "{\"query\":\"foo\",\"max\":10}"),
                new LlmStreamEvent.ToolCallDelta(0, "call_b", "read_file", "{\"path\":\"pom.xml\"}"));

        final var message = LlmWire.pump(publisher(events), ignored -> {}, Cancellations.NEVER);

        final var calls = message.toolCalls();
        assertNotNull(calls);
        assertEquals(2, calls.size());

        final var first = calls.get(0);
        assertEquals("call_a", first.id());
        assertEquals("grep", first.function().name());
        assertEquals("{\"query\":\"foo\",\"max\":10}", first.function().arguments());

        final var second = calls.get(1);
        assertEquals("call_b", second.id());
        assertEquals("read_file", second.function().name());
        assertEquals("{\"path\":\"pom.xml\"}", second.function().arguments());
    }

    @Test
    void continuationFragmentsAfterIndexReuseAttachToTheNewestCall() throws Exception {
        final var events = List.<LlmStreamEvent>of(
                new LlmStreamEvent.ToolCallDelta(0, "a", "grep", "{\"query\":"),
                new LlmStreamEvent.ToolCallDelta(0, null, null, "\"x\"}"),
                new LlmStreamEvent.ToolCallDelta(0, "b", "read_file", "{\"path\":"),
                new LlmStreamEvent.ToolCallDelta(0, null, null, "\"y\"}"));

        final var message = LlmWire.pump(publisher(events), ignored -> {}, Cancellations.NEVER);

        final var calls = message.toolCalls();
        assertNotNull(calls);
        assertEquals(2, calls.size());
        assertEquals("{\"query\":\"x\"}", calls.get(0).function().arguments());
        assertEquals("{\"path\":\"y\"}", calls.get(1).function().arguments());
    }

    private static Flow.Publisher<LlmStreamEvent> publisher(final List<LlmStreamEvent> events) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
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
}
