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
import java.util.LinkedHashMap;
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
                Math.toIntExact(usage.promptTokens()),
                Math.toIntExact(usage.completionTokens()),
                Math.toIntExact(usage.totalTokens()));
    }

    static Message pump(
            final Flow.Publisher<LlmStreamEvent> publisher,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var done = new CompletableFuture<Message>();
        final var text = new StringBuilder();
        final var toolCalls = new ArrayList<ToolCall>();
        // Streaming dialects emit one delta per fragment: the first carries the name,
        // the following ones carry only argument fragments and a null name (and possibly
        // a missing id). Coalesce them back into a single logical tool call per (id, index).
        final var partials = new LinkedHashMap<String, PartialToolCall>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(final Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final LlmStreamEvent event) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    subscription.cancel();
                    done.complete(new Message("assistant", text.toString(), null, toNull(toolCalls), null, null));
                    return;
                }
                if (event instanceof TextDelta(String t)) {
                    text.append(t);
                    onDelta.accept(event);
                } else if (event instanceof ReasoningDelta) {
                    onDelta.accept(event);
                } else if (event instanceof ToolCallDelta(int index, String id, String name, String args)) {
                    final var key = id == null ? Integer.toString(index) : id;
                    final var partial = partials.computeIfAbsent(key, ignored -> new PartialToolCall(index, id));
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

            @Override
            public void onError(final Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                toolCalls.addAll(partials.values().stream()
                        .map(partial -> partial.toToolCall())
                        .toList());
                done.complete(new Message("assistant", text.toString(), null, toNull(toolCalls), null, null));
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

    /**
     * Mutable accumulator for a single streaming tool call: fragments coalesced by (id, index)
     * carry the first non-null name and the concatenated argument JSON.
     */
    private static final class PartialToolCall {

        private final int index;
        private final String id;
        private final StringBuilder arguments = new StringBuilder();
        private String name;

        private PartialToolCall(final int index, final String id) {
            this.index = index;
            this.id = id;
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
