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
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmTool;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.openai.model.ChatChunk;
import io.yupiik.llm.client.openai.model.ChatCompletionRequest;
import io.yupiik.llm.client.openai.model.ChatCompletionResponse;
import io.yupiik.llm.client.openai.model.CompletionsTool;
import io.yupiik.llm.client.openai.model.WireMessage;
import io.yupiik.llm.client.openai.model.WireTool;
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

/** OpenAI {@code /chat/completions} reactive dialect. */
public class OpenAICompletionsDialect extends AbstractDialect {

    /**
     * Creates the dialect.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates (base URL, authentication, options).
     */
    public OpenAICompletionsDialect(
            final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
        super(mapper, client, provider);
    }

    /**
     * Exposes the routing identifier of this dialect.
     *
     * @return the routing identifier of this dialect, {@code openai-completions}.
     */
    @Override
    public String name() {
        return "openai-completions";
    }

    /**
     * Exposes the chat route appended to the provider base URL.
     *
     * @return the chat route suffix appended to the provider base URL.
     */
    @Override
    protected String chatRouteSuffix() {
        return "/chat/completions";
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
                    .thenApply(response -> toLlmResponse(parse(ChatCompletionResponse.class, response.body())));
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

    private ChatCompletionRequest toWire(final LlmRequest request, final boolean stream) {
        final var messages = request.messages() == null
                ? List.<WireMessage>of()
                : request.messages().stream().map(WireMessage::from).toList();
        final var tools = request.tools() == null
                ? null
                : request.tools().stream()
                        .map(OpenAICompletionsDialect::toWireTool)
                        .toList();
        return new ChatCompletionRequest(
                request.model(),
                messages,
                request.temperature(),
                request.topP(),
                null,
                stream ? Boolean.TRUE : null,
                tools,
                request.toolChoice(),
                request.maxTokens(),
                null,
                request.stop(),
                null,
                null,
                null,
                null,
                request.reasoningEffort(),
                request.responseFormat(),
                request.extensions() == null ? Map.of() : request.extensions());
    }

    private static CompletionsTool toWireTool(final LlmTool tool) {
        return new CompletionsTool(
                "function",
                new WireTool.WireToolFunction(tool.name(), tool.description(), tool.parameters(), tool.strict()));
    }

    private LlmResponse toLlmResponse(final ChatCompletionResponse response) {
        final var index = new AtomicInteger();
        final var choices = response.choices() == null
                ? List.<LlmResponse.LlmChoice>of()
                : response.choices().stream()
                        .map(c -> new LlmResponse.LlmChoice(
                                index.getAndIncrement(), WireMessage.toNeutral(c.message()), c.finishReason()))
                        .toList();
        final Usage usage =
                response.usage() == null ? Usage.EMPTY : response.usage().toNeutral();
        return new LlmResponse(response.id(), response.model(), null, choices, usage, null);
    }

    private List<LlmStreamEvent> mapEvent(final SseParser.Event event) {
        final var data = event.data();
        if (data == null || "[DONE]".equals(data)) {
            return List.of();
        }
        final ChatChunk chunk;
        try {
            chunk = mapper.fromString(ChatChunk.class, data);
        } catch (final RuntimeException e) {
            return List.of();
        }
        final var out = new ArrayList<LlmStreamEvent>();
        if (chunk.usage() != null) {
            out.add(new LlmStreamEvent.End(null, chunk.usage().toNeutral()));
        }
        if (chunk.choices() != null) {
            for (final var choice : chunk.choices()) {
                final var delta = choice.delta();
                if (delta != null) {
                    final var reasoning =
                            delta.reasoningContent() != null ? delta.reasoningContent() : delta.reasoning();
                    if (reasoning != null && !reasoning.isEmpty()) {
                        out.add(new LlmStreamEvent.ReasoningDelta(reasoning));
                    }
                    if (delta.content() instanceof String s && !s.isEmpty()) {
                        out.add(new LlmStreamEvent.TextDelta(s));
                    }
                    if (delta.toolCalls() != null) {
                        for (final var call : delta.toolCalls()) {
                            final var fn = call.function();
                            out.add(new LlmStreamEvent.ToolCallDelta(
                                    call.index() == null ? 0 : call.index(),
                                    call.id(),
                                    fn == null ? null : fn.name(),
                                    fn == null ? null : fn.arguments()));
                        }
                    }
                }
                if (choice.finishReason() != null) {
                    out.add(new LlmStreamEvent.End(choice.finishReason(), null));
                }
            }
        }
        return out;
    }
}
