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
package io.yupiik.llm.client.openai;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.LlmResponseParseException;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.model.UsageExtensions;
import io.yupiik.llm.client.openai.model.ResponsesInput;
import io.yupiik.llm.client.openai.model.ResponsesRequest;
import io.yupiik.llm.client.openai.model.ResponsesTool;
import io.yupiik.llm.client.spi.AbstractDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.spi.SseParser;
import io.yupiik.llm.client.spi.Streams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** OpenAI {@code /responses} reactive dialect mapping the neutral model to the Responses wire format. */
public final class OpenAIResponsesDialect extends AbstractDialect {

    private volatile java.util.function.Consumer<String> onRawLine;
    // Records the ids of tool calls whose arguments were already streamed incrementally via
    // response.function_call_arguments.delta, so the assembled arguments carried by the completion
    // events are only emitted as a fallback (when no delta was seen) and never double-appended.
    private final java.util.Set<String> streamedArgIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Creates the dialect.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates (base URL, authentication, options).
     */
    public OpenAIResponsesDialect(
            final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
        super(mapper, client, provider);
    }

    /**
     * Registers an optional tee consuming every raw SSE line before parsing (mainly for wire
     * debugging). When null (the default) raw lines are discarded.
     *
     * @param onRawLine the raw-line consumer or null.
     */
    public void setOnRawLine(final java.util.function.Consumer<String> onRawLine) {
        this.onRawLine = onRawLine;
    }

    /**
     * Exposes the routing identifier of this dialect.
     *
     * @return the routing identifier of this dialect, {@code openai-responses}.
     */
    @Override
    public String name() {
        return "openai-responses";
    }

    /**
     * Exposes the chat route appended to the provider base URL.
     *
     * @return the chat route suffix appended to the provider base URL.
     */
    @Override
    protected String chatRouteSuffix() {
        return "/responses";
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var payload = mapper.toBytes(toWire(request, false));
            return post(payload).thenCompose(response -> {
                try {
                    return CompletableFuture.completedFuture(toLlmResponse(asMap(parseResponse(Map.class, response))));
                } catch (final LlmResponseParseException e) {
                    return failed(e);
                }
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            streamedArgIds.clear();
            final var payload = mapper.toBytes(toWire(request, true));
            return openStream(payload, cancelled)
                    .thenCompose(attempt -> attempt.ok()
                            ? CompletableFuture.completedFuture(
                                    Streams.toEvents(attempt.live(), cancelled, this::mapEvent, () -> null, onRawLine))
                            : streamError(attempt));
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    public CompletionStage<LlmModels> models() {
        try {
            return getModels().thenCompose(response -> {
                try {
                    return CompletableFuture.completedFuture(toModels(parseResponse(Map.class, response)));
                } catch (final LlmResponseParseException e) {
                    return failed(e);
                }
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    protected byte[] encodeRequest(final LlmRequest request, final boolean stream) {
        return mapper.toBytes(toWire(request, stream));
    }

    private ResponsesRequest toWire(final LlmRequest request, final boolean stream) {
        final var instructions = new ArrayList<String>();
        final var input = new ArrayList<ResponsesInput>();
        if (request.messages() != null) {
            for (final var message : request.messages()) {
                switch (message.role()) {
                    case "system", "developer" -> instructions.add(str(message.content()));
                    case "assistant" -> {
                        final var text = str(message.content());
                        if (text != null && !text.isBlank()) {
                            input.add(ResponsesInput.message("assistant", text));
                        }
                        if (message.toolCalls() != null) {
                            for (final var call : message.toolCalls()) {
                                if (call.function() != null) {
                                    input.add(ResponsesInput.functionCall(
                                            call.id(),
                                            call.function().name(),
                                            call.function().arguments()));
                                }
                            }
                        }
                    }
                    case "tool" -> {
                        final var extensions = message.extensions();
                        final var isError = extensions != null && Boolean.TRUE.equals(extensions.get("is_error"));
                        input.add(ResponsesInput.functionCallOutput(
                                message.toolCallId(), str(message.content()), isError));
                    }
                    default -> input.add(ResponsesInput.message(message.role(), str(message.content())));
                }
            }
        }
        final var tools = new ArrayList<ResponsesTool>();
        if (request.tools() != null) {
            for (final var tool : request.tools()) {
                tools.add(new ResponsesTool(
                        "function", tool.name(), tool.description(), tool.parameters(), tool.strict()));
            }
        }
        // 'summary' is required by the official API to stream reasoning deltas at all
        // (gateways often emit them unrequested); 'auto' keeps the payload small
        final var reasoning = request.reasoningEffort() == null
                ? null
                : Map.<String, Object>of("effort", request.reasoningEffort(), "summary", "auto");
        return new ResponsesRequest(
                request.model(),
                input,
                instructions.isEmpty() ? null : String.join("\n\n", instructions),
                request.maxTokens(),
                request.temperature(),
                request.topP(),
                stream ? Boolean.TRUE : null,
                tools.isEmpty() ? null : tools,
                request.toolChoice(),
                request.responseFormat(),
                false,
                reasoning,
                request.extensions() == null ? Map.of() : request.extensions());
    }

    private LlmResponse toLlmResponse(final Map<String, Object> root) {
        final var messageText = new StringBuilder();
        final var calls = new ArrayList<LlmToolCall>();
        if (root.get("output") instanceof List<?> output) {
            final var index = new AtomicInteger();
            for (final Object item : output) {
                if (item instanceof Map<?, ?> map) {
                    final var type = str(map.get("type"));
                    if ("message".equals(type)) {
                        final var content = map.get("content");
                        if (content instanceof List<?> parts) {
                            for (final Object part : parts) {
                                if (part instanceof Map<?, ?> p
                                        && "output_text".equals(str(p.get("type")))
                                        && p.get("text") instanceof String text) {
                                    messageText.append(text);
                                }
                            }
                        }
                    } else if ("function_call".equals(type)) {
                        // keep every call: parallel tool calls must land in one assistant message
                        calls.add(new LlmToolCall(
                                str(map.get("id")),
                                "function",
                                new LlmToolCall.FunctionCall(str(map.get("name")), str(map.get("arguments"))),
                                index.getAndIncrement()));
                    }
                }
            }
        }
        final LlmResponse.LlmChoice choice;
        if (!calls.isEmpty()) {
            choice = new LlmResponse.LlmChoice(
                    0, new LlmMessage("assistant", messageText.toString(), null, calls, null, null), "tool_calls");
        } else {
            choice = new LlmResponse.LlmChoice(
                    0, new LlmMessage("assistant", messageText.toString(), null, null, null, null), "stop");
        }
        final var usage = usage(root.get("usage"));
        return new LlmResponse(str(root.get("id")), str(root.get("model")), null, List.of(choice), usage, null);
    }

    private List<LlmStreamEvent> mapEvent(final SseParser.Event event) {
        try {
            final var type = event.event() == null ? "" : event.event();
            if (event.data() == null) {
                return List.of();
            }
            final var root = asMap(parse(Map.class, event.data()));
            final var result = new ArrayList<LlmStreamEvent>();
            switch (type) {
                case "response.output_text.delta" -> {
                    final var text = str(root.get("delta"));
                    if (text != null && !text.isEmpty()) {
                        result.add(new LlmStreamEvent.TextDelta(text));
                    }
                }
                case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                    // reasoning models stream either the full reasoning text or (more
                    // commonly on gateways) only its summary: both are the model thinking
                    final var text = str(root.get("delta"));
                    if (text != null && !text.isEmpty()) {
                        result.add(new LlmStreamEvent.ReasoningDelta(text));
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    final var text = str(root.get("delta"));
                    final var callId = str(root.get("item_id"));
                    // use the item's real output_index (not a constant) so the coalescing subscriber
                    // can match this fragment to the output_item that carries the name; index 0 would
                    // collide across tool calls in a multi-call turn and drop the leading arguments
                    // (eg. the 'command' of a bash call), leaving command empty.
                    final var outputIndex = root.get("output_index") instanceof Number n ? n.intValue() : 0;
                    if (callId != null) {
                        streamedArgIds.add(callId);
                    }
                    result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, callId, null, text == null ? "" : text));
                }
                case "response.function_call_arguments.done" -> {
                    final var callId = str(root.get("item_id"));
                    final var assembled = str(root.get("arguments"));
                    if (callId != null
                            && assembled != null
                            && !assembled.isEmpty()
                            && !streamedArgIds.contains(callId)) {
                        final var outputIndex = root.get("output_index") instanceof Number n ? n.intValue() : 0;
                        result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, callId, null, assembled));
                    }
                }
                case "response.output_item.added", "response.output_item.done" -> {
                    if (root.get("item") instanceof Map<?, ?> item && "function_call".equals(str(item.get("type")))) {
                        // output_item.added carries the name; output_item.done carries the fully
                        // assembled arguments.
                        final var outputIndex = root.get("output_index") instanceof Number n ? n.intValue() : 0;
                        final var id = str(item.get("id"));
                        result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, id, str(item.get("name")), null));
                        final var assembled = str(item.get("arguments"));
                        if ("response.output_item.done".equals(type)
                                && assembled != null
                                && !assembled.isEmpty()
                                && id != null
                                && !streamedArgIds.contains(id)) {
                            // no incremental delta was seen for this call: emit the assembled
                            // arguments so the coalesced tool call is complete (prevents an empty
                            // 'command is required' failure when a provider skips the delta events).
                            result.add(new LlmStreamEvent.ToolCallDelta(outputIndex, id, null, assembled));
                        }
                    }
                }
                case "response.completed", "response.incomplete" ->
                    result.add(new LlmStreamEvent.End(
                            "response.completed".equals(type) ? "stop" : "length", usage(root.get("usage"))));
                case "response.failed", "response.error", "error" -> result.add(new LlmStreamEvent.End("length", null));
                default -> {
                    // ignore unknown event types
                }
            }
            return result;
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static Usage usage(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Long cacheRead = map.get("input_tokens_details") instanceof Map<?, ?> details
                            && details.get("cached_tokens") instanceof Number n
                    ? n.longValue()
                    : null;
            final var completion = number(map.get("output_tokens"));
            final Long reasoning = map.get("output_tokens_details") instanceof Map<?, ?> details
                            && details.get("reasoning_tokens") instanceof Number n
                    ? n.longValue()
                    : map.get("reasoning_tokens") instanceof Number n ? n.longValue() : null;
            return new Usage(
                    number(map.get("input_tokens")),
                    completion,
                    number(map.get("total_tokens")),
                    cacheRead,
                    null,
                    reasoning,
                    UsageExtensions.generationTimeMs(extensions(map), completion));
        }
        return Usage.EMPTY;
    }

    /**
     * The usage payload seen as a plain string keyed map, so the non standard timing fields published
     * by some gateways can be read back. Only the entries of the usage object itself are kept.
     *
     * @param map the usage payload.
     * @return the non standard fields, {@code null} when the payload is not a plain object.
     */
    private static Map<String, Object> extensions(final Map<?, ?> map) {
        final var extensions = new LinkedHashMap<String, Object>(map.size());
        map.forEach((key, value) -> {
            if (key instanceof final String name) {
                extensions.put(name, value);
            }
        });
        return extensions;
    }

    private static long number(final Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
