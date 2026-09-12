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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Anthropic /v1/messages wire protocol client. Authentication uses the {@code x-api-key} header
 * plus {@code anthropic-version}. The {@code max_tokens} parameter is mandatory for Anthropic and
 * is always present (the {@link ChatClient} dispatcher guarantees a non-null request
 * {@code max_tokens} via {@link OutputBudgetResolver} before delegating here).
 */
final class AnthropicMessagesClient extends AbstractLlmClient {
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    AnthropicMessagesClient(final JsonMapper mapper) {
        super(mapper);
    }

    @Override
    public ApiFlavor flavor() {
        return ApiFlavor.ANTHROPIC;
    }

    @Override
    protected void authenticate(final ProviderConfiguration provider, final HttpRequest.Builder builder) {
        builder.header("x-api-key", provider.resolveApiKey());
        builder.header("anthropic-version", ANTHROPIC_VERSION);
        addHeaders(provider, builder);
    }

    @Override
    public ChatResult chat(final ProviderConfiguration provider, final ChatCompletionRequest request)
            throws IOException, InterruptedException {
        return chat(provider, request, () -> false);
    }

    @Override
    public ChatResult chat(
            final ProviderConfiguration provider, final ChatCompletionRequest request, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var response = send(provider, http(provider, mapper.toBytes(toWire(request, false))));
        if (response.statusCode() >= 400) {
            if (ContextWindowExceededException.isCause(response.statusCode(), response.body())) {
                throw new ContextWindowExceededException();
            }
            throw new IOException(
                    "LLM request failed (" + response.statusCode() + "): " + response.body() + retryAfter(response));
        }
        return toResult(parse(AnthropicMessagesResponse.class, response.body()));
    }

    @Override
    public Message stream(
            final ProviderConfiguration provider,
            final ChatCompletionRequest request,
            final Consumer<StreamDelta> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var httpRequest = http(provider, mapper.toBytes(toWire(request, true)));
        try (final var reader = openStream(provider, httpRequest, cancelled)) {
            final var text = new StringBuilder();
            final var reasoning = new StringBuilder();
            final var toolCalls = new LinkedHashMap<String, ToolCall>();
            final var partial = new LinkedHashMap<String, String>();
            final var parser = new SseParser();
            final Message[] done = {null};
            Usage usage = Usage.EMPTY;
            String line;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                for (final var event : parser.feed(line)) {
                    final AnthropicStreamEvent e;
                    try {
                        e = mapper.fromString(AnthropicStreamEvent.class, event.data());
                    } catch (final RuntimeException ex) {
                        continue;
                    }
                    switch (e.type() == null ? "" : e.type()) {
                        case "message_start" -> {
                            if (e.message() != null && e.message().usage() != null) {
                                onDelta.accept(
                                        StreamDelta.usage(toUsage(e.message().usage(), 0)));
                            }
                        }
                        case "content_block_start" -> {
                            if (e.contentBlock() != null
                                    && "tool_use".equals(e.contentBlock().type())) {
                                toolCalls.put(
                                        e.contentBlock().id(),
                                        new ToolCall(
                                                e.contentBlock().id(),
                                                "function",
                                                new ToolCall.FunctionCall(
                                                        e.contentBlock().name(), ""),
                                                null));
                                onDelta.accept(StreamDelta.toolCall(
                                        toolCalls.get(e.contentBlock().id())));
                            }
                        }
                        case "content_block_delta" -> {
                            if (e.delta() == null) {
                                break;
                            }
                            final var d = e.delta();
                            if ("text_delta".equals(d.type()) && d.text() != null) {
                                text.append(d.text());
                                onDelta.accept(StreamDelta.text(d.text()));
                            } else if ("thinking_delta".equals(d.type()) && d.thinking() != null) {
                                reasoning.append(d.thinking());
                                onDelta.accept(StreamDelta.reasoning(d.thinking()));
                            } else if ("input_json_delta".equals(d.type()) && d.partialJson() != null) {
                                for (final var call : toolCalls.entrySet()) {
                                    if (call.getValue().function() != null) {
                                        final var accumulated =
                                                partial.merge(call.getKey(), d.partialJson(), String::concat);
                                        final var fn = call.getValue().function();
                                        toolCalls.put(
                                                call.getKey(),
                                                new ToolCall(
                                                        call.getValue().id(),
                                                        call.getValue().type(),
                                                        new ToolCall.FunctionCall(fn.name(), accumulated),
                                                        call.getValue().index()));
                                        onDelta.accept(StreamDelta.toolCall(toolCalls.get(call.getKey())));
                                    }
                                }
                            }
                        }
                        case "message_delta" -> {
                            if (e.delta() != null && e.delta().stopReason() != null) {
                                onDelta.accept(StreamDelta.finish(e.delta().stopReason()));
                            }
                            if (e.usage() != null) {
                                usage = toUsage(e.usage(), 0);
                                onDelta.accept(StreamDelta.usage(usage));
                            }
                        }
                        case "message_stop" -> {
                            if (e.message() != null && e.message().usage() != null) {
                                usage = toUsage(e.message().usage(), 0);
                                onDelta.accept(StreamDelta.usage(usage));
                            }
                        }
                        case "error" -> {
                            final var msg = e.extensions() == null
                                    ? null
                                    : e.extensions().get("message");
                            throw new IOException("LLM messages stream failed: " + (msg == null ? event.data() : msg));
                        }
                        default -> {}
                    }
                }
            }
            if (done[0] != null) {
                return done[0];
            }
            final var fn = toolCalls.values().isEmpty()
                    ? null
                    : toolCalls.values().iterator().next().function();
            final var calls = toolCalls.isEmpty()
                    ? null
                    : List.copyOf(toolCalls.values().stream()
                            .map(c -> {
                                final var f = c.function();
                                return f == null
                                        ? c
                                        : new ToolCall(
                                                c.id(),
                                                c.type(),
                                                new ToolCall.FunctionCall(
                                                        f.name(), f.arguments() == null ? "" : f.arguments()),
                                                c.index());
                            })
                            .toList());
            return new Message("assistant", text.length() == 0 ? null : text.toString(), null, calls, null, null);
        }
    }

    @Override
    public ModelsResponse models(final ProviderConfiguration provider) throws IOException, InterruptedException {
        final var response = send(provider, modelsRequest(provider));
        if (isFlavorMissing(response.statusCode())) {
            return new ModelsResponse(List.of());
        }
        if (response.statusCode() >= 400) {
            throw new IOException("Models request failed (" + response.statusCode() + "): " + response.body());
        }
        final var parsed = parse(AnthropicModels.class, response.body());
        final var data = parsed == null || parsed.data() == null
                ? List.<ModelsResponse.Model>of()
                : parsed.data().stream()
                        .map(m -> new ModelsResponse.Model(m.id(), null, tokens(m)))
                        .toList();
        return new ModelsResponse(data);
    }

    private static Map<String, Object> tokens(final AnthropicModels.Model model) {
        final var others = new LinkedHashMap<String, Object>();
        if (model.maxInputTokens() != null) {
            others.put("max_input_tokens", model.maxInputTokens());
        }
        if (model.maxTokens() != null) {
            others.put("max_tokens", model.maxTokens());
        }
        return others;
    }

    private AnthropicMessagesRequest toWire(final ChatCompletionRequest request, final boolean stream) {
        final var system = new StringBuilder();
        final var messages = new ArrayList<AnthropicMessage>();
        if (request.messages() != null) {
            for (final var message : request.messages()) {
                switch (message.role() == null ? "" : message.role()) {
                    case "system", "developer" -> {
                        if (!system.isEmpty()) {
                            system.append('\n');
                        }
                        system.append(textOf(message.content()));
                    }
                    case "tool" -> {
                        final var block = new LinkedHashMap<String, Object>();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", message.toolCallId());
                        block.put("content", textOf(message.content()));
                        appendBlocks(messages, "user", List.of(block));
                    }
                    default -> {
                        final var role = "assistant".equals(message.role()) ? "assistant" : "user";
                        final var blocks = new ArrayList<Map<String, Object>>(toWireBlocks(message.content()));
                        if (message.toolCalls() != null) {
                            message.toolCalls().forEach(call -> {
                                if (call.function() != null) {
                                    final var block = new LinkedHashMap<String, Object>();
                                    block.put("type", "tool_use");
                                    block.put("id", call.id());
                                    block.put("name", call.function().name());
                                    block.put(
                                            "input",
                                            parseArguments(call.function().arguments()));
                                    blocks.add(block);
                                }
                            });
                        }
                        appendBlocks(messages, role, blocks);
                    }
                }
            }
        }
        return new AnthropicMessagesRequest(
                request.model(),
                requireMaxTokens(request.maxTokens()),
                system.isEmpty() ? null : system.toString(),
                messages,
                request.temperature(),
                null,
                null,
                null,
                stream ? Boolean.TRUE : null,
                request.tools() == null
                        ? null
                        : request.tools().stream()
                                .map(t -> new AnthropicTool(
                                        t.function().name(),
                                        t.function().description(),
                                        t.function().parameters()))
                                .toList(),
                toWireToolChoice(request.toolChoice()),
                null,
                request.others());
    }

    private static Integer requireMaxTokens(final Integer maxTokens) {
        if (maxTokens == null || maxTokens <= 0) {
            throw new IllegalStateException("Anthropic requires a positive max_tokens; the dispatcher must resolve it");
        }
        return maxTokens;
    }

    private Map<String, Object> toWireToolChoice(final String toolChoice) {
        if (toolChoice == null) {
            return null;
        }
        return switch (toolChoice) {
            case "auto" -> Map.of("type", "auto");
            case "none" -> Map.of("type", "none");
            case "required" -> Map.of("type", "any");
            case "any" -> Map.of("type", "any");
            default -> Map.of("type", "auto");
        };
    }

    private Object parseArguments(final String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.fromString(Object.class, arguments);
        } catch (final RuntimeException e) {
            return arguments;
        }
    }

    private ChatResult toResult(final AnthropicMessagesResponse response) throws IOException {
        final var text = new StringBuilder();
        final var calls = new ArrayList<ToolCall>();
        if (response.content() != null) {
            for (final var block : response.content()) {
                switch (block.type() == null ? "" : block.type()) {
                    case "text" -> {
                        if (block.text() != null) {
                            text.append(block.text());
                        }
                    }
                    case "tool_use" ->
                        calls.add(new ToolCall(
                                block.id(),
                                "function",
                                new ToolCall.FunctionCall(
                                        block.name(), block.input() == null ? "{}" : mapper.toString(block.input())),
                                null));
                    default -> {}
                }
            }
        }
        final var usage = response.usage() == null ? Usage.EMPTY : toUsage(response.usage(), 0);
        return new ChatResult(
                new Message(
                        "assistant",
                        text.length() == 0 ? null : text.toString(),
                        null,
                        calls.isEmpty() ? null : List.copyOf(calls),
                        null,
                        null),
                usage);
    }

    private Usage toUsage(final AnthropicMessagesResponse.AnthropicUsage usage, final int ignored) {
        final var in =
                usage.inputTokens() == null ? 0 : (int) usage.inputTokens().longValue();
        final var out =
                usage.outputTokens() == null ? 0 : (int) usage.outputTokens().longValue();
        return new Usage(in, out, in + out);
    }

    // anthropic requires alternating roles so consecutive same-role messages are merged
    private void appendBlocks(
            final List<AnthropicMessage> messages, final String role, final List<Map<String, Object>> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        if (!messages.isEmpty()
                && role.equals(messages.getLast().role())
                && messages.getLast().content() instanceof List<?> existing) {
            final var merged = new ArrayList<Object>(existing);
            merged.addAll(blocks);
            messages.set(messages.size() - 1, new AnthropicMessage(role, merged));
            return;
        }
        messages.add(new AnthropicMessage(role, new ArrayList<>(blocks)));
    }

    private List<Map<String, Object>> toWireBlocks(final Object content) {
        switch (content) {
            case String text -> {
                return List.of(Map.of("type", "text", "text", text));
            }
            case List<?> parts -> {
                final var blocks = new ArrayList<Map<String, Object>>();
                for (final var part : parts) {
                    if (part instanceof Map<?, ?> map) {
                        if ("text".equals(str(map.get("type")))) {
                            blocks.add(Map.of("type", "text", "text", str(map.get("text"))));
                        }
                    }
                }
                return blocks;
            }
            case null, default -> {
                return List.of();
            }
        }
    }
}
