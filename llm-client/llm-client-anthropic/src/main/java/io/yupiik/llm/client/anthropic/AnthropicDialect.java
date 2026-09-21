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
package io.yupiik.llm.client.anthropic;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.anthropic.model.AnthropicMessage;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesRequest;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesResponse;
import io.yupiik.llm.client.anthropic.model.AnthropicStreamEvent;
import io.yupiik.llm.client.anthropic.model.AnthropicTool;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.spi.AbstractDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.spi.SseParser;
import io.yupiik.llm.client.spi.Streams;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Anthropic Messages reactive dialect mapping the neutral model to the Messages wire format. */
public final class AnthropicDialect extends AbstractDialect {

    private volatile java.util.function.Consumer<String> onRawLine;

    /**
     * Creates the dialect.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates (base URL, authentication, options).
     */
    public AnthropicDialect(final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
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
     * @return the routing identifier of this dialect, {@code anthropic-messages}.
     */
    @Override
    public String name() {
        return "anthropic-messages";
    }

    /**
     * Exposes the chat route appended to the provider base URL.
     *
     * @return the chat route suffix appended to the provider base URL.
     */
    @Override
    protected String chatRouteSuffix() {
        return "/messages";
    }

    @Override
    protected void authenticate(final HttpRequest.Builder builder) {
        if (provider.headers() != null && !provider.headers().containsKey("x-api-key")) {
            builder.header("x-api-key", provider.apiKey());
        }
        if (provider.headers() != null && !provider.headers().containsKey("anthropic-version")) {
            builder.header("anthropic-version", "2023-06-01");
        }
        addHeaders(builder);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        try {
            final var payload = mapper.toBytes(toWire(request, false));
            return post(payload)
                    .thenApply(response -> toLlmResponse(parse(AnthropicMessagesResponse.class, response.body())));
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
                                    Streams.toEvents(attempt.live(), cancelled, this::mapEvent, () -> null, onRawLine))
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

    private AnthropicMessagesRequest toWire(final LlmRequest request, final boolean stream) {
        Map<String, Object> toolChoice = null;
        if (request.toolChoice() instanceof String s) {
            final var choice =
                    switch (s) {
                        case "none" -> "none";
                        case "required", "any" -> "any";
                        default -> "auto";
                    };
            toolChoice = Map.of("type", choice);
        }
        String system = null;
        final List<AnthropicMessage> messages = new ArrayList<>();
        if (request.messages() != null) {
            for (final var message : request.messages()) {
                if ("system".equals(message.role())) {
                    system = textOf(message.content());
                } else if ("assistant".equals(message.role())
                        && message.toolCalls() != null
                        && !message.toolCalls().isEmpty()) {
                    final var content = new ArrayList<Map<String, Object>>();
                    final var text = textOf(message.content());
                    if (text != null && !text.isEmpty()) {
                        content.add(Map.of("type", "text", "text", text));
                    }
                    for (final var call : message.toolCalls()) {
                        content.add(Map.of(
                                "type",
                                "tool_use",
                                "id",
                                call.id(),
                                "name",
                                call.function() == null ? null : call.function().name(),
                                "input",
                                parseRaw(
                                        call.function() == null
                                                ? null
                                                : call.function().arguments())));
                    }
                    messages.add(new AnthropicMessage("assistant", content));
                } else if ("tool".equals(message.role())) {
                    final var extensions = message.extensions();
                    final var isError = extensions != null && Boolean.TRUE.equals(extensions.get("is_error"));
                    final var content = new ArrayList<Map<String, Object>>();
                    content.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", message.toolCallId(),
                            "content", textOf(message.content())));
                    if (isError) {
                        content.get(0).put("is_error", true);
                    }
                    messages.add(new AnthropicMessage("user", content));
                } else {
                    messages.add(AnthropicMessage.from(message));
                }
            }
        }
        final List<AnthropicTool> tools = new ArrayList<>();
        if (request.tools() != null) {
            for (final var tool : request.tools()) {
                tools.add(new AnthropicTool(tool.name(), tool.description(), tool.parameters()));
            }
        }
        final var maxTokens = request.maxTokens() == null ? 4096 : request.maxTokens();
        final Map<String, Object> extensions = request.extensions() == null ? Map.of() : request.extensions();
        Integer topK = null;
        if (extensions.get("top_k") instanceof Number n) {
            topK = n.intValue();
        }
        final var promptCache = !"false".equals(extensions.get("promptCache"));
        Map<String, Object> passthrough = extensions;
        if (extensions.containsKey("promptCache") || topK != null) {
            final var stripped = new java.util.HashMap<String, Object>();
            extensions.forEach((k, v) -> {
                if (!"promptCache".equals(k) && !"top_k".equals(k)) {
                    stripped.put(k, v);
                }
            });
            passthrough = stripped;
        }
        return new AnthropicMessagesRequest(
                request.model(),
                maxTokens,
                system == null ? null : toSystemBlocks(system, promptCache),
                messages,
                request.temperature(),
                request.topP(),
                topK,
                request.stop(),
                stream,
                tools.isEmpty() ? null : tools,
                toolChoice,
                null,
                passthrough);
    }

    /**
     * Anthropic caches the tools + system prefix: sending the system prompt as content blocks with an
     * ephemeral {@code cache_control} breakpoint makes the stable system prompt (rules, project
     * context, skills) a cache hit across turns instead of a full re-read every request.
     */
    private static Object toSystemBlocks(final String system, final boolean promptCache) {
        if (!promptCache) {
            return system;
        }
        return List.of(Map.of("type", "text", "text", system, "cache_control", Map.of("type", "ephemeral")));
    }

    private Object parseRaw(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return parse(Map.class, raw);
        } catch (final RuntimeException e) {
            return raw;
        }
    }

    private LlmResponse toLlmResponse(final AnthropicMessagesResponse wire) {
        final List<LlmToolCall> toolCalls = new ArrayList<>();
        final var text = new StringBuilder();
        if (wire.content() != null) {
            for (final var block : wire.content()) {
                if (block.text() != null) {
                    text.append(block.text());
                } else if ("tool_use".equals(block.type()) && block.name() != null) {
                    toolCalls.add(new LlmToolCall(
                            block.id(),
                            "function",
                            new LlmToolCall.FunctionCall(block.name(), toJson(block.input())),
                            0));
                }
            }
        }
        final var index = new AtomicInteger();
        final LlmResponse.LlmChoice choice;
        if (!toolCalls.isEmpty()) {
            final var neutralCalls = new ArrayList<LlmToolCall>(toolCalls.size());
            for (final var call : toolCalls) {
                neutralCalls.add(new LlmToolCall(call.id(), call.type(), call.function(), index.getAndIncrement()));
            }
            choice = new LlmResponse.LlmChoice(
                    0, new LlmMessage("assistant", null, null, neutralCalls, null, null), "tool_calls");
        } else {
            choice = new LlmResponse.LlmChoice(
                    0,
                    new LlmMessage("assistant", text.toString(), null, null, null, null),
                    finishReason(wire.stopReason()));
        }
        final var usage = wire.usage() == null ? Usage.EMPTY : toUsage(wire.usage());
        return new LlmResponse(wire.id(), wire.model(), null, List.of(choice), usage, null);
    }

    private String toJson(final Object input) {
        try {
            return mapper.toString(input);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String finishReason(final String stopReason) {
        if (stopReason == null) {
            return "stop";
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }

    private List<LlmStreamEvent> mapEvent(final SseParser.Event event) {
        try {
            if (event.data() == null) {
                return List.of();
            }
            final var wire = parse(AnthropicStreamEvent.class, event.data());
            final var result = new ArrayList<LlmStreamEvent>();
            switch (wire.type()) {
                case "content_block_delta" -> {
                    if (wire.delta() != null) {
                        final var text = wire.delta().text();
                        if (text != null && !text.isEmpty()) {
                            result.add(new LlmStreamEvent.TextDelta(text));
                        }
                        final var thinking = wire.delta().thinking();
                        if (thinking != null && !thinking.isEmpty()) {
                            result.add(new LlmStreamEvent.ReasoningDelta(thinking));
                        }
                        if ("input_json_delta".equals(wire.delta().type())
                                && wire.delta().partialJson() != null) {
                            final var index = wire.index() == null ? 0 : wire.index();
                            result.add(new LlmStreamEvent.ToolCallDelta(
                                    index, null, null, wire.delta().partialJson()));
                        }
                    }
                }
                case "content_block_start" -> {
                    if (wire.contentBlock() != null
                            && "tool_use".equals(wire.contentBlock().type())) {
                        result.add(new LlmStreamEvent.ToolCallDelta(
                                wire.index() == null ? 0 : wire.index(),
                                wire.contentBlock().id(),
                                wire.contentBlock().name(),
                                ""));
                    }
                }
                case "message_delta" -> {
                    if (wire.delta() != null && wire.delta().stopReason() != null) {
                        result.add(
                                new LlmStreamEvent.End(finishReason(wire.delta().stopReason()), toUsage(wire.usage())));
                    }
                }
                default -> {
                    // message_start / content_block_stop / message_stop / ping ignored
                }
            }
            return result;
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static Usage toUsage(final AnthropicMessagesResponse.AnthropicUsage usage) {
        if (usage == null) {
            return Usage.EMPTY;
        }
        final var in = usage.inputTokens() == null ? 0 : usage.inputTokens();
        final var out = usage.outputTokens() == null ? 0 : usage.outputTokens();
        return new Usage(in, out, in + out, usage.cacheReadInputTokens(), usage.cacheCreationInputTokens());
    }
}
