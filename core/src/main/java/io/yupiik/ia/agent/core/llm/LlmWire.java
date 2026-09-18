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

import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.ToolCallDelta;
import io.yupiik.llm.client.model.LlmTool;
import io.yupiik.llm.client.model.LlmToolCall;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Neutral model translation and blocking bridge between the core request/response types and the
 * {@code llm-client} library types. Also pumps a streamed publisher into the historical blocking
 * {@code Message} result.
 */
final class LlmWire {

    private LlmWire() {
        // utility
    }

    static LlmRequest toLlmRequest(final ChatCompletionRequest request) {
        return new LlmRequest(
                request.model(),
                request.messages() == null
                        ? null
                        : request.messages().stream().map(LlmWire::toLlmMessage).toList(),
                request.temperature(),
                null,
                request.maxTokens(),
                null,
                request.tools() == null
                        ? null
                        : request.tools().stream().map(LlmWire::toLlmTool).toList(),
                request.toolChoice(),
                null,
                request.reasoningEffort(),
                request.stream(),
                request.others() == null ? Map.of() : request.others());
    }

    static LlmMessage toLlmMessage(final Message message) {
        return new LlmMessage(
                message.role(),
                message.content(),
                message.name(),
                message.toolCalls() == null
                        ? null
                        : message.toolCalls().stream()
                                .map(LlmWire::toLlmToolCall)
                                .toList(),
                message.toolCallId(),
                message.others() == null ? Map.of() : message.others());
    }

    static LlmTool toLlmTool(final Tool tool) {
        final var fn = tool.function();
        return new LlmTool(fn.name(), fn.description(), fn.parameters(), null);
    }

    static LlmToolCall toLlmToolCall(final ToolCall call) {
        return new LlmToolCall(
                call.id(),
                call.type(),
                call.function() == null
                        ? null
                        : new LlmToolCall.FunctionCall(
                                call.function().name(), call.function().arguments()),
                call.index());
    }

    static ChatResult toChatResult(final LlmResponse response) {
        final var choice = response.first();
        final var message = choice == null ? null : choice.message();
        return new ChatResult(message == null ? Message.user("") : toMessage(message), toUsage(response.usage()));
    }

    static Message toMessage(final LlmMessage message) {
        return new Message(
                message.role(),
                message.content(),
                message.name(),
                message.toolCalls() == null
                        ? null
                        : message.toolCalls().stream()
                                .map(LlmWire::fromLlmToolCall)
                                .toList(),
                message.toolCallId(),
                message.extensions() == null ? Map.of() : message.extensions());
    }

    static ToolCall fromLlmToolCall(final LlmToolCall call) {
        return new ToolCall(
                call.id(),
                call.type(),
                call.function() == null
                        ? null
                        : new ToolCall.FunctionCall(
                                call.function().name(), call.function().arguments()),
                call.index());
    }

    static ModelsResponse toModelsResponse(final LlmModels models) {
        return new ModelsResponse(models.data().stream()
                .map(m -> new ModelsResponse.Model(m.id(), m.ownedBy(), m.others()))
                .toList());
    }

    static Usage toUsage(final io.yupiik.llm.client.model.Usage usage) {
        if (usage == null) {
            return Usage.EMPTY;
        }
        return new Usage(
                Math.toIntExact(usage.prompt()),
                Math.toIntExact(usage.completion()),
                Math.toIntExact(usage.total()),
                Math.toIntExact(usage.cacheRead()),
                Math.toIntExact(usage.cacheWrite()));
    }

    static Message pump(
            final Flow.Publisher<LlmStreamEvent> publisher,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var done = new CompletableFuture<Message>();
        final var text = new StringBuilder();
        final var toolCalls = new ArrayList<ToolCall>();
        // Streaming dialects emit one delta per fragment. The stable identity of a logical tool call
        // is not uniform across dialects: OpenAI Responses carries the same id (item_id) on every
        // fragment but varies the index, while Anthropic/Completions keep the index stable and only
        // put the id/name on the first fragment. So a fragment belongs with an existing partial when
        // it shares either its id or its index; index -gt id links are resolved lazily on arrival.
        // Reactive publishers must signal sequentially per the Flow.Subscriber contract, but the
        // coalescing state below is guarded by a monitor to stay correct even if the publisher is
        // non-conformant and delivers fragments concurrently.
        final var byId = new HashMap<String, PartialToolCall>();
        final var byIndex = new HashMap<Integer, PartialToolCall>();
        // lookup maps carry no ordering, so the emission order of the logical calls is tracked
        // separately: partials are appended once, when they are created
        final var creationOrder = new ArrayList<PartialToolCall>();
        final var mergeLock = new Object();
        // a transport failure after fragments were delivered (connection cut mid-chunk) is a
        // MidStreamFailureException so the caller can retry the whole request, while a failure
        // before any fragment is left untouched for the resilience layer
        final var delivered = new boolean[] {false};
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(final Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final LlmStreamEvent event) {
                synchronized (mergeLock) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        subscription.cancel();
                        done.complete(new Message("assistant", text.toString(), null, toNull(toolCalls), null, null));
                        return;
                    }
                    if (event instanceof TextDelta(String t)) {
                        delivered[0] = true;
                        text.append(t);
                        onDelta.accept(event);
                    } else if (event instanceof ReasoningDelta) {
                        delivered[0] = true;
                        onDelta.accept(event);
                    } else if (event instanceof ToolCallDelta(int index, String id, String name, String args)) {
                        delivered[0] = true;
                        final var partial = resolvePartial(byId, byIndex, index, id, creationOrder);
                        if (id != null) {
                            byId.put(id, partial);
                        }
                        byIndex.put(index, partial);
                        if (id != null && partial.id == null) {
                            partial.id = id;
                        }
                        if (name != null) {
                            partial.name = name;
                        }
                        if (args != null) {
                            partial.arguments.append(args);
                        }
                        onDelta.accept(event);
                    } else if (event instanceof End) {
                        onDelta.accept(event);
                    }
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                synchronized (mergeLock) {
                    if (delivered[0]) {
                        done.completeExceptionally(new MidStreamFailureException(
                                "stream interrupted after " + text.length() + " chars of partial content: " + throwable,
                                throwable,
                                text.toString(),
                                creationOrder.isEmpty()
                                        ? null
                                        : creationOrder.stream()
                                                .map(PartialToolCall::toToolCall)
                                                .toList()));
                    } else {
                        done.completeExceptionally(throwable);
                    }
                }
            }

            @Override
            public void onComplete() {
                synchronized (mergeLock) {
                    toolCalls.addAll(creationOrder.stream()
                            .map(PartialToolCall::toToolCall)
                            .toList());
                    done.complete(new Message("assistant", text.toString(), null, toNull(toolCalls), null, null));
                }
            }
        });
        try {
            return done.join();
        } catch (final CompletionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw translate(cause);
        }
    }

    private static List<ToolCall> toNull(final List<ToolCall> calls) {
        return calls.isEmpty() ? null : calls;
    }

    private static PartialToolCall resolvePartial(
            final Map<String, PartialToolCall> byId,
            final Map<Integer, PartialToolCall> byIndex,
            final int index,
            final String id,
            final List<PartialToolCall> creationOrder) {
        final var fromId = id != null ? byId.get(id) : null;
        final var fromIndex = byIndex.get(index);
        if (fromId != null
                && fromIndex != null
                && fromIndex != fromId
                && fromIndex.id != null
                && !fromIndex.id.equals(id)) {
            // a fragment of a known call arriving under an index slot taken over by a newer call:
            // follow the id and re-point the slot, never concatenating the other call's arguments
            byIndex.put(index, fromId);
            return fromId;
        }
        if (fromId == null && fromIndex != null && fromIndex.id != null && id != null && !fromIndex.id.equals(id)) {
            // some OpenAI-compatible servers (llama.cpp style) reuse the fragment index across
            // parallel tool calls: the id is authoritative, so keep the calls separate and take
            // the index slot over - the id-less continuation fragments of the newest call then
            // attach to it instead of bleeding into the previous call's arguments
            final var created = new PartialToolCall(index);
            created.id = id;
            creationOrder.add(created);
            byId.put(id, created);
            byIndex.put(index, created);
            return created;
        }
        if (fromId != null) {
            if (fromIndex != null && fromIndex != fromId) {
                // same logical call surfaced under both identities: merge the grouping so later
                // fragments (which may carry only the index or only the id) find the same partial
                fromId.arguments.append(fromIndex.arguments());
                if (fromId.name == null) {
                    fromId.name = fromIndex.name;
                }
                if (fromId.id == null) {
                    fromId.id = fromIndex.id;
                }
                byIndex.put(index, fromId);
            } else if (fromIndex == null) {
                byIndex.put(index, fromId);
            }
            return fromId;
        }
        if (fromIndex != null) {
            if (id != null) {
                byId.put(id, fromIndex);
            }
            return fromIndex;
        }
        final var created = new PartialToolCall(index);
        creationOrder.add(created);
        if (id != null) {
            byId.put(id, created);
        }
        byIndex.put(index, created);
        return created;
    }

    /**
     * Mutable accumulator for a single streaming tool call: fragments coalesced by their shared id or
     * index carry the first non-null id and name and the concatenated argument JSON.
     */
    private static final class PartialToolCall {

        private final int index;
        private String id;
        private final StringBuilder arguments = new StringBuilder();
        private String name;

        private PartialToolCall(final int index) {
            this.index = index;
        }

        private String arguments() {
            return arguments.toString();
        }

        private ToolCall toToolCall() {
            return new ToolCall(id, "function", new ToolCall.FunctionCall(name, arguments.toString()), index);
        }
    }

    static <A> A join(final CompletionStage<A> stage) throws IOException, InterruptedException {
        try {
            return stage.toCompletableFuture().join();
        } catch (final CompletionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw translate(cause);
        }
    }

    /**
     * Maps a library failure to the blocking API: {@link io.yupiik.llm.client.DialectNotSupportedException}
     * and {@link io.yupiik.llm.client.ContextWindowExceededException} flow through as-is (the router
     * fallback and the agent loop rely on their exact type) and other {@link IOException}s keep their
     * message (status prefix parsing depends on it).
     */
    private static IOException translate(final Throwable error) {
        if (error instanceof io.yupiik.llm.client.DialectNotSupportedException
                || error instanceof io.yupiik.llm.client.ContextWindowExceededException) {
            return (IOException) error;
        }
        if (error instanceof IOException io) {
            return io;
        }
        return new IOException(error.getMessage(), error);
    }
}
