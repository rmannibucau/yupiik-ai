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
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.openai.model.ResponsesInput;
import io.yupiik.llm.client.openai.model.ResponsesRequest;
import io.yupiik.llm.client.openai.model.ResponsesTool;
import io.yupiik.llm.client.spi.AbstractDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.spi.SseParser;
import io.yupiik.llm.client.spi.Streams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** OpenAI {@code /responses} reactive dialect mapping the neutral model to the Responses wire format. */
public final class OpenAIResponsesDialect extends AbstractDialect {

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
            return post(payload).thenApply(response -> toLlmResponse(asMap(parse(Map.class, response.body()))));
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var payload = mapper.toBytes(toWire(request, true));
            return openStream(payload, cancelled)
                    .thenCompose(attempt -> attempt.ok()
                            ? CompletableFuture.completedFuture(
                                    Streams.toEvents(attempt.live(), cancelled, this::mapEvent, () -> null))
                            : streamError(attempt));
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    @Override
    public CompletionStage<LlmModels> models() {
        try {
            return getModels().thenApply(response -> toModels(parse(Map.class, response.body())));
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
                    case "tool" ->
                        input.add(ResponsesInput.functionCallOutput(message.toolCallId(), str(message.content())));
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
        final var reasoning =
                request.reasoningEffort() == null ? null : Map.<String, Object>of("effort", request.reasoningEffort());
        return new ResponsesRequest(
                request.model(),
                input,
                instructions.isEmpty() ? null : String.join("\n\n", instructions),
                null,
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
        final var toolCalls = new ArrayList<LlmMessage>(); // not used; tool calls are separate output items
        final var choices = new ArrayList<LlmResponse.LlmChoice>();
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
                        toolCalls.add(new LlmMessage(
                                "assistant",
                                null,
                                null,
                                List.of(new LlmToolCall(
                                        str(map.get("id")),
                                        "function",
                                        new LlmToolCall.FunctionCall(str(map.get("name")), str(map.get("arguments"))),
                                        index.getAndIncrement())),
                                null,
                                null));
                    }
                }
            }
        }
        if (!toolCalls.isEmpty()) {
            choices.add(new LlmResponse.LlmChoice(0, toolCalls.get(0), "tool_calls"));
        } else {
            choices.add(new LlmResponse.LlmChoice(
                    0, new LlmMessage("assistant", messageText.toString(), null, null, null, null), "stop"));
        }
        final var usage = usage(root.get("usage"));
        return new LlmResponse(str(root.get("id")), str(root.get("model")), null, choices, usage, null);
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
                case "response.reasoning_text.delta" -> {
                    final var text = str(root.get("delta"));
                    if (text != null && !text.isEmpty()) {
                        result.add(new LlmStreamEvent.ReasoningDelta(text));
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    final var text = str(root.get("delta"));
                    final var callId = str(root.get("item_id"));
                    result.add(new LlmStreamEvent.ToolCallDelta(0, callId, null, text == null ? "" : text));
                }
                case "response.output_item.done" -> {
                    if (root.get("output") instanceof Map<?, ?> item) {
                        result.addAll(mapOutputItem(item));
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

    private List<LlmStreamEvent> mapOutputItem(final Map<?, ?> item) {
        final var type = str(item.get("type"));
        if ("function_call".equals(type)) {
            return List.of(new LlmStreamEvent.ToolCallDelta(
                    0,
                    str(item.get("id")),
                    str(item.get("name")),
                    item.get("arguments") == null ? "" : str(item.get("arguments"))));
        }
        return List.of();
    }

    private static Usage usage(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return new Usage(
                    number(map.get("input_tokens")), number(map.get("output_tokens")), number(map.get("total_tokens")));
        }
        return Usage.EMPTY;
    }

    private static long number(final Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
