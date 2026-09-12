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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** OpenAI /responses wire protocol client. */
final class OpenAIResponsesClient extends AbstractLlmClient {
    OpenAIResponsesClient(final JsonMapper mapper) {
        super(mapper);
    }

    @Override
    public ApiFlavor flavor() {
        return ApiFlavor.RESPONSES;
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
        final var payload = mapper.toBytes(toResponsesRequest(request, false));
        final var response = send(provider, http(provider, payload));
        if (response.statusCode() >= 400) {
            if (isFlavorMissing(response.statusCode())) {
                throw new FlavorMissingException("responses");
            }
            if (ContextWindowExceededException.isCause(response.statusCode(), response.body())) {
                throw new ContextWindowExceededException();
            }
            throw new IOException(
                    "LLM request failed (" + response.statusCode() + "): " + response.body() + retryAfter(response));
        }
        return parseResponsesResponse(parse(response.body()));
    }

    @Override
    public Message stream(
            final ProviderConfiguration provider,
            final ChatCompletionRequest request,
            final Consumer<StreamDelta> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var payload = mapper.toBytes(toResponsesRequest(request, true));
        final var httpRequest = http(provider, payload);
        try (final var reader = openStream(provider, httpRequest, cancelled)) {
            final var content = new StringBuilder();
            final var calls = new LinkedHashMap<String, ToolCall>();
            final var argsIndex = new LinkedHashMap<String, String>();
            final var parser = new SseParser();
            final Message[] done = {null};
            final Usage[] usage = {Usage.EMPTY};
            String line;
            while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                for (final var event : parser.feed(line)) {
                    final var json = parse(event.data());
                    if (!(json instanceof Map<?, ?> data)) {
                        continue;
                    }
                    final var type =
                            event.event() != null && !event.event().isBlank() ? event.event() : str(data.get("type"));
                    switch (type == null ? "" : type) {
                        case "response.output_text.delta" -> {
                            final var d = str(data.get("delta"));
                            if (d != null && !d.isEmpty()) {
                                content.append(d);
                                onDelta.accept(StreamDelta.text(d));
                            }
                        }
                        case "response.reasoning_text.delta",
                                "response.reasoning_summary_text.delta",
                                "response.reasoning_summary_part.added" -> {
                            final var d = str(data.get("delta"));
                            if (d != null && !d.isEmpty()) {
                                onDelta.accept(StreamDelta.reasoning(d));
                            }
                        }
                        case "response.output_item.added" -> {
                            final var item = data.get("item");
                            if (item instanceof Map<?, ?> m && "function_call".equals(str(m.get("type")))) {
                                final var callId = str(m.get("call_id"));
                                final var itemId = str(m.get("id"));
                                if (itemId != null && callId != null) {
                                    argsIndex.put(itemId, callId);
                                }
                                if (callId != null) {
                                    calls.putIfAbsent(
                                            callId,
                                            new ToolCall(
                                                    callId,
                                                    "function",
                                                    new ToolCall.FunctionCall(
                                                            str(m.get("name")),
                                                            str(m.get("arguments")) == null
                                                                    ? ""
                                                                    : str(m.get("arguments"))),
                                                    null));
                                    onDelta.accept(StreamDelta.toolCall(calls.get(callId)));
                                }
                            }
                        }
                        case "response.function_call_arguments.delta" -> {
                            final var callId = argsIndex.get(str(data.get("item_id")));
                            final var call = callId == null ? null : calls.get(callId);
                            final var d = str(data.get("delta"));
                            if (call != null && d != null) {
                                final var fn = call.function();
                                calls.put(
                                        callId,
                                        new ToolCall(
                                                call.id(),
                                                call.type(),
                                                new ToolCall.FunctionCall(
                                                        fn.name(), (fn.arguments() == null ? "" : fn.arguments()) + d),
                                                null));
                                onDelta.accept(StreamDelta.toolCall(calls.get(callId)));
                            }
                        }
                        case "response.completed", "response.incomplete" -> {
                            final var resp = data.get("response");
                            if (resp instanceof Map<?, ?> r) {
                                final var parsed = parseResponsesResponse(r);
                                done[0] = parsed.message();
                                usage[0] = parsed.usage();
                                onDelta.accept(StreamDelta.usage(parsed.usage()));
                            }
                        }
                        case "response.failed", "response.error", "error" -> {
                            final var msg = data.get("response") instanceof Map<?, ?> r
                                    ? str(r.get("status_error"))
                                    : str(data.get("message"));
                            throw new IOException("LLM responses stream failed: " + (msg == null ? event.data() : msg));
                        }
                        default -> {}
                    }
                }
            }
            if (done[0] != null) {
                return done[0];
            }
            return new Message(
                    "assistant",
                    content.toString(),
                    null,
                    calls.isEmpty() ? null : List.copyOf(calls.values()),
                    null,
                    null);
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

    private ChatResult parseResponsesResponse(final Object json) throws IOException {
        if (!(json instanceof Map<?, ?> map)) {
            throw new IOException("Unexpected responses payload: " + json);
        }
        final var err = map.get("error");
        if (err instanceof Map<?, ?> e && e.get("message") != null) {
            throw new IOException("LLM request failed: " + e.get("message"));
        }
        final var status = str(map.get("status"));
        if ("failed".equals(status)) {
            throw new IOException("LLM responses request failed: " + map.get("error"));
        }
        final var content = new StringBuilder();
        final var calls = new ArrayList<ToolCall>();
        if (map.get("output") instanceof List<?> output) {
            for (final var o : output) {
                if (!(o instanceof Map<?, ?> item)) {
                    continue;
                }
                switch (str(item.get("type")) == null ? "" : str(item.get("type"))) {
                    case "message" -> {
                        if (item.get("content") instanceof List<?> parts) {
                            for (final var p : parts) {
                                if (p instanceof Map<?, ?> pm && "output_text".equals(str(pm.get("type")))) {
                                    final var t = str(pm.get("text"));
                                    if (t != null) {
                                        content.append(t);
                                    }
                                }
                            }
                        } else if (item.get("content") instanceof String s) {
                            content.append(s);
                        }
                    }
                    case "function_call" ->
                        calls.add(new ToolCall(
                                str(item.get("call_id")) != null ? str(item.get("call_id")) : str(item.get("id")),
                                "function",
                                new ToolCall.FunctionCall(
                                        str(item.get("name")),
                                        str(item.get("arguments")) == null ? "" : str(item.get("arguments"))),
                                null));
                    default -> {}
                }
            }
        }
        return new ChatResult(
                new Message(
                        "assistant", content.toString(), null, calls.isEmpty() ? null : List.copyOf(calls), null, null),
                parseUsage(map.get("usage")));
    }

    private Usage parseUsage(final Object usage) {
        if (!(usage instanceof Map<?, ?> u)) {
            return Usage.EMPTY;
        }
        final var in = number(u.get("input_tokens") != null ? u.get("input_tokens") : u.get("prompt_tokens"));
        final var out = number(u.get("output_tokens") != null ? u.get("output_tokens") : u.get("completion_tokens"));
        final var total = number(u.get("total_tokens"));
        return new Usage(in, out, total > 0 ? total : in + out);
    }

    private ResponsesRequest toResponsesRequest(final ChatCompletionRequest request, final boolean stream) {
        String instructions = null;
        final var input = new ArrayList<ResponsesInput>();
        for (final var m : request.messages()) {
            final var text = textOf(m.content());
            switch (m.role() == null ? "" : m.role()) {
                case "system", "developer" -> instructions = instructions == null ? text : instructions + "\n\n" + text;
                case "user" -> input.add(ResponsesInput.message("user", text));
                case "assistant" -> {
                    if (text != null && !text.isEmpty()) {
                        input.add(ResponsesInput.message("assistant", text));
                    }
                    if (m.toolCalls() != null) {
                        for (final var tc : m.toolCalls()) {
                            if (tc.function() != null) {
                                input.add(ResponsesInput.functionCall(
                                        tc.id(),
                                        tc.function().name(),
                                        tc.function().arguments()));
                            }
                        }
                    }
                }
                case "tool" -> input.add(ResponsesInput.functionCallOutput(m.toolCallId(), text));
                default -> input.add(ResponsesInput.message(m.role(), text));
            }
        }
        final var tools = request.tools() == null
                ? null
                : request.tools().stream()
                        .filter(t -> t.function() != null)
                        .map(t -> new ResponsesTool(
                                "function",
                                t.function().name(),
                                t.function().description(),
                                t.function().parameters()))
                        .toList();
        final var reasoning =
                request.reasoningEffort() == null ? null : Map.<String, Object>of("effort", request.reasoningEffort());
        return new ResponsesRequest(
                request.model(),
                input,
                instructions,
                stream,
                false,
                tools,
                request.toolChoice(),
                reasoning,
                request.others());
    }
}
