package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** OpenAI /chat/completions wire protocol client. */
final class OpenAICompletionClient extends AbstractLlmClient {
    OpenAICompletionClient(final JsonMapper mapper) {
        super(mapper);
    }

    @Override
    public ApiFlavor flavor() {
        return ApiFlavor.COMPLETIONS;
    }

    @Override
    public ChatResult chat(final ProviderConfiguration provider, final ChatCompletionRequest request)
            throws IOException, InterruptedException {
        return chat(provider, request, () -> false);
    }

    @Override
    public ChatResult chat(final ProviderConfiguration provider, final ChatCompletionRequest request,
                           final BooleanSupplier cancelled) throws IOException, InterruptedException {
        return toResult(sendCompletions(provider, request));
    }

    @Override
    public Message stream(final ProviderConfiguration provider, final ChatCompletionRequest request,
                          final Consumer<StreamDelta> onDelta, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var payload = mapper.toBytes(request);
        final var httpRequest = http(provider, payload);
        try (final var reader = openStream(provider, httpRequest, cancelled)) {
            final var content = new StringBuilder();
            final var toolCalls = new LinkedHashMap<Integer, ToolCall>();
            final var parser = new SseParser();
            String line;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                for (final var event : parser.feed(line)) {
                    if ("[DONE]".equals(event.data())) {
                        continue;
                    }
                    final ChatChunk chunk = tryParse(ChatChunk.class, event.data());
                    if (chunk == null) {
                        continue;
                    }
                    if (chunk.usage() != null) {
                        onDelta.accept(StreamDelta.usage(chunk.usage()));
                    }
                    if (chunk.choices() == null) {
                        continue;
                    }
                    for (final var choice : chunk.choices()) {
                        final var delta = choice.delta();
                        if (delta != null) {
                            final var reason = reasoningText(provider, delta);
                            if (reason != null && !reason.isEmpty()) {
                                onDelta.accept(StreamDelta.reasoning(reason));
                            }
                            if (delta.content() instanceof String s && !s.isEmpty()) {
                                content.append(s);
                                onDelta.accept(StreamDelta.text(s));
                            }
                            if (delta.toolCalls() != null) {
                                for (final var call : delta.toolCalls()) {
                                    merge(toolCalls, call);
                                    onDelta.accept(StreamDelta.toolCall(call));
                                }
                            }
                        }
                        if (choice.finishReason() != null) {
                            onDelta.accept(StreamDelta.finish(choice.finishReason()));
                        }
                    }
                }
            }
            return new Message("assistant", content.toString(), null,
                    toolCalls.isEmpty() ? null : List.copyOf(toolCalls.values()), null, null);
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
        return parse(ModelsResponse.class, response.body());
    }

    private ChatCompletionResponse sendCompletions(final ProviderConfiguration provider,
                                                   final ChatCompletionRequest request) throws IOException {
        final var payload = mapper.toBytes(request);
        final var response = send(provider, http(provider, payload));
        if (response.statusCode() >= 400) {
            if (isFlavorMissing(response.statusCode())) {
                throw new FlavorMissingException("completions");
            }
            if (ContextWindowExceededException.isCause(response.statusCode(), response.body())) {
                throw new ContextWindowExceededException();
            }
            throw new IOException("LLM request failed (" + response.statusCode() + "): " + response.body()
                    + retryAfter(response));
        }
        return parse(ChatCompletionResponse.class, response.body());
    }

    private ChatResult toResult(final ChatCompletionResponse response) throws IOException {
        return new ChatResult(response.firstMessage(), response.usage() == null ? Usage.EMPTY : response.usage());
    }

    private String reasoningText(final ProviderConfiguration provider, final ChatChunk.ChunkDelta delta) {
        return switch (provider.reasoningField() == null ? io.yupiik.ia.agent.core.configuration.ReasoningField.AUTO : provider.reasoningField()) {
            case REASONING -> delta.reasoning();
            case REASONING_CONTENT -> delta.reasoningContent();
            case NONE -> null;
            case AUTO -> delta.reasoningContent() != null ? delta.reasoningContent() : delta.reasoning();
        };
    }

    private void merge(final Map<Integer, ToolCall> toolCalls, final ToolCall call) {
        final var key = call.index() != null ? call.index() : toolCalls.size();
        final var existing = toolCalls.get(key);
        if (existing == null) {
            toolCalls.put(key, call);
            return;
        }
        final var fn = call.function();
        final var mergedFn = fn == null ? existing.function() : new ToolCall.FunctionCall(
                fn.name() != null ? fn.name() : (existing.function() != null ? existing.function().name() : null),
                (existing.function() != null && existing.function().arguments() != null ? existing.function().arguments() : "")
                        + (fn.arguments() != null ? fn.arguments() : ""));
        toolCalls.put(key, new ToolCall(
                call.id() != null ? call.id() : existing.id(),
                call.type() != null ? call.type() : existing.type(),
                mergedFn,
                call.index()));
    }

    @SuppressWarnings("unchecked")
    private <A> A tryParse(final Class<A> type, final String json) {
        try {
            return mapper.fromString(type, json);
        } catch (final RuntimeException e) {
            return null;
        }
    }
}