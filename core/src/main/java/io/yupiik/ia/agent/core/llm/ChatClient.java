package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@ApplicationScoped
public class ChatClient implements AutoCloseable {
    private static final List<String> FLAVOR_MISSING_CODES = List.of("(404)", "(405)", "(501)");

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final ProviderManager providerManager;
    private final ExtendedHttpClient client;
    private final Semaphore permits;
    private final Deque<Long> requestTimes = new ArrayDeque<>();
    private volatile ApiFlavor resolved = ApiFlavor.AUTO;
    private volatile String modelOverride;
    private volatile ProviderConfiguration targetProvider;

    public ChatClient(final CodingAgentConfiguration configuration, final JsonMapper mapper,
                      final ProviderManager providerManager) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                .setDelegate(java.net.http.HttpClient.newHttpClient()));
        this.permits = new Semaphore(Math.max(1, configuration.llm().maxConcurrent()));
    }

    protected ChatClient() {
        this.configuration = null;
        this.mapper = null;
        this.providerManager = null;
        this.client = null;
        this.permits = null;
    }

    public ApiFlavor flavor() {
        final var current = providerManager.current();
        return resolved == ApiFlavor.AUTO ? (current == null ? ApiFlavor.AUTO : current.api()) : resolved;
    }

    public String model() {
        final var override = modelOverride;
        if (override != null && !override.isBlank()) {
            return override;
        }
        final var current = providerManager.current();
        return current == null || current.model() == null ? "" : current.model();
    }

    public void setModel(final String model) {
        this.modelOverride = model == null || model.isBlank() ? null : model;
    }

    public void setProvider(final String id) {
        providerManager.setProvider(id);
        this.modelOverride = null;
        this.resolved = ApiFlavor.AUTO;
    }

    public String providerId() {
        return providerManager.currentProviderId();
    }

    /** The provider currently targeted by the in-flight request (the current provider unless a $provider ref routed it). */
    private ProviderConfiguration target() {
        final var t = targetProvider;
        return t == null ? providerManager.current() : t;
    }

    private String resolveModel() throws IOException {
        final var override = modelOverride;
        final var ref = override != null && !override.isBlank() ? override : providerManager.current().model();
        final var resolvedModel = providerManager.resolve(ref);
        if (resolvedModel.provider() != null) {
            this.targetProvider = resolvedModel.provider();
        }
        final var model = resolvedModel.model();
        if (model == null || model.isBlank()) {
            throw new IOException(
                    "no model configured: set yca.provider.model (--yca-provider-model, configuration.json or YCA_PROVIDER_MODEL)");
        }
        return model;
    }

    /** Merge the resolved provider-specific options into the request extra payload, provider JSON winning. */
    private Map<String, Object> mergeOptions(final Map<String, Object> base) {
        final var options = providerManager.providerOptions(target());
        if (options.isEmpty()) {
            return base;
        }
        final var merged = new LinkedHashMap<String, Object>();
        if (base != null) {
            merged.putAll(base);
        }
        merged.putAll(options);
        return merged;
    }

    private ChatCompletionRequest useModel(final ChatCompletionRequest request) throws IOException {
        final var model = resolveModel();
        final var others = mergeOptions(request.others());
        if (model.equals(request.model()) && others == request.others()) {
            return request;
        }
        return new ChatCompletionRequest(model, request.messages(), request.temperature(), request.stream(),
                request.tools(), request.toolChoice(), request.maxTokens(), request.reasoningEffort(), others);
    }

    public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return chat(request, () -> false);
    }

    public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var effective = useModel(request);
        permits.acquire();
        try {
            final var flavor = flavor();
            if (flavor == ApiFlavor.RESPONSES) {
                return withRetries(() -> sendBlockingResponses(effective), cancelled);
            }
            if (flavor == ApiFlavor.COMPLETIONS) {
                return toResult(withRetries(() -> sendBlockingCompletions(effective), cancelled));
            }
            try {
                return withRetries(() -> sendBlockingResponses(effective), cancelled);
            } catch (final FlavorMissingException e) {
                resolved = ApiFlavor.COMPLETIONS;
                return toResult(withRetries(() -> sendBlockingCompletions(effective), cancelled));
            }
        } finally {
            permits.release();
        }
    }

    public Message stream(final ChatCompletionRequest request, final Consumer<StreamDelta> onDelta)
            throws IOException, InterruptedException {
        return stream(request, onDelta, () -> false);
    }

    public Message stream(final ChatCompletionRequest request, final Consumer<StreamDelta> onDelta,
                          final BooleanSupplier cancelled) throws IOException, InterruptedException {
        final var model = resolveModel();
        permits.acquire();
        try {
            final var streaming = new ChatCompletionRequest(model, request.messages(), request.temperature(),
                    true, request.tools(), request.toolChoice(), request.maxTokens(), request.reasoningEffort(), mergeOptions(request.others()));
            var attempt = 0;
            IOException last;
            do {
                throttle();
                try {
                    final var flavor = flavor();
                    if (flavor == ApiFlavor.RESPONSES) {
                        return doStreamResponses(streaming, onDelta, cancelled);
                    }
                    if (flavor == ApiFlavor.COMPLETIONS) {
                        return doStreamCompletions(streaming, onDelta, cancelled);
                    }
                    try {
                        return doStreamResponses(streaming, onDelta, cancelled);
                    } catch (final FlavorMissingException e) {
                        resolved = ApiFlavor.COMPLETIONS;
                        return doStreamCompletions(streaming, onDelta, cancelled);
                    }
                } catch (final IOException e) {
                    if (e instanceof FlavorMissingException || e instanceof ContextWindowExceededException) {
                        throw e;
                    }
                    last = e;
                    attempt++;
                }
                if (attempt >= configuration.llm().retriesMaxAttempts() || cancelled.getAsBoolean()) {
                    throw new IOException("LLM request failed: " + last.getMessage(), last);
                }
                sleepBackoff(attempt, null);
            } while (true);
        } finally {
            permits.release();
        }
    }

    public ModelsResponse models() throws IOException, InterruptedException {
        permits.acquire();
        try {
            final var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(target().baseUrl() + "/models"))
                    .timeout(Duration.ofSeconds(target().timeoutSeconds()))
                    .header("authorization", "Bearer " + target().resolveApiKey())
                    .header("accept", "application/json")
                    .GET()
                    .build();
            final HttpResponse<String> response;
            try {
                response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (response.statusCode() == 404 || response.statusCode() == 405 || response.statusCode() == 501) {
                return new ModelsResponse(List.of());
            }
            if (response.statusCode() >= 400) {
                throw new IOException("Models request failed (" + response.statusCode() + "): " + response.body());
            }
            return mapper.fromString(ModelsResponse.class, response.body());
        } finally {
            permits.release();
        }
    }

    //
    // completions flavor
    //

    private ChatCompletionResponse sendBlockingCompletions(final ChatCompletionRequest request) throws IOException {
        final var payload = mapper.toBytes(request);
        final var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(target().baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(target().timeoutSeconds()))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + target().resolveApiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        final HttpResponse<String> response = send(httpRequest);
        if (response.statusCode() >= 400) {
            if (ContextWindowExceededException.isCause(response.statusCode(), response.body())) {
                throw new ContextWindowExceededException();
            }
            throw new IOException("LLM request failed (" + response.statusCode() + "): " + response.body() + retryAfter(response));
        }
        return mapper.fromString(ChatCompletionResponse.class, response.body());
    }

    private Message doStreamCompletions(final ChatCompletionRequest request, final Consumer<StreamDelta> onDelta,
                                        final BooleanSupplier cancelled) throws IOException, InterruptedException {
        final var payload = mapper.toBytes(request);
        final var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(target().baseUrl() + "/chat/completions"))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + target().resolveApiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try (BufferedReader reader = openStream(httpRequest, cancelled)) {
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
                            final var reason = reasoningText(delta);
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

    //
    // responses flavor
    //

    private ChatResult sendBlockingResponses(final ChatCompletionRequest request) throws IOException {
        final var responsesRequest = toResponsesRequest(request, false);
        final var payload = mapper.toBytes(responsesRequest);
        final var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(target().baseUrl() + "/responses"))
                .timeout(Duration.ofSeconds(target().timeoutSeconds()))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + target().resolveApiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        final HttpResponse<String> response = send(httpRequest);
        if (response.statusCode() >= 400) {
            if (isFlavorMissing(response.statusCode())) {
                throw new FlavorMissingException();
            }
            if (ContextWindowExceededException.isCause(response.statusCode(), response.body())) {
                throw new ContextWindowExceededException();
            }
            throw new IOException("LLM request failed (" + response.statusCode() + "): " + response.body() + retryAfter(response));
        }
        return parseResponsesResponse(parse(response.body()));
    }

    private Message doStreamResponses(final ChatCompletionRequest request, final Consumer<StreamDelta> onDelta,
                                      final BooleanSupplier cancelled) throws IOException, InterruptedException {
        final var payload = mapper.toBytes(toResponsesRequest(request, true));
        final var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(target().baseUrl() + "/responses"))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + target().resolveApiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try (BufferedReader reader = openStream(httpRequest, cancelled)) {
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
                    final var type = event.event() != null && !event.event().isBlank() ? event.event() : str(data.get("type"));
                    switch (type == null ? "" : type) {
                        case "response.output_text.delta" -> {
                            final var d = str(data.get("delta"));
                            if (d != null && !d.isEmpty()) {
                                content.append(d);
                                onDelta.accept(StreamDelta.text(d));
                            }
                        }
                        case "response.reasoning_text.delta", "response.reasoning_summary_text.delta", "response.reasoning_summary_part.added" -> {
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
                                    calls.putIfAbsent(callId, new ToolCall(callId, "function",
                                            new ToolCall.FunctionCall(str(m.get("name")), str(m.get("arguments")) == null ? "" : str(m.get("arguments"))), null));
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
                                calls.put(callId, new ToolCall(call.id(), call.type(),
                                        new ToolCall.FunctionCall(fn.name(), (fn.arguments() == null ? "" : fn.arguments()) + d), null));
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
                            final var msg = data.get("response") instanceof Map<?, ?> r ? str(r.get("status_error")) : str(data.get("message"));
                            throw new IOException("LLM responses stream failed: " + (msg == null ? event.data() : msg));
                        }
                        default -> {
                        }
                    }
                }
            }
            if (done[0] != null) {
                return done[0];
            }
            return new Message("assistant", content.toString(), null,
                    calls.isEmpty() ? null : List.copyOf(calls.values()), null, null);
        }
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
                    case "function_call" -> calls.add(new ToolCall(
                            str(item.get("call_id")) != null ? str(item.get("call_id")) : str(item.get("id")),
                            "function",
                            new ToolCall.FunctionCall(str(item.get("name")), str(item.get("arguments")) == null ? "" : str(item.get("arguments"))),
                            null));
                    default -> {
                    }
                }
            }
        }
        return new ChatResult(
                new Message("assistant", content.toString(), null, calls.isEmpty() ? null : List.copyOf(calls), null, null),
                parseUsage(map.get("usage")));
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
                                input.add(ResponsesInput.functionCall(tc.id(), tc.function().name(), tc.function().arguments()));
                            }
                        }
                    }
                }
                case "tool" -> input.add(ResponsesInput.functionCallOutput(m.toolCallId(), text));
                default -> input.add(ResponsesInput.message(m.role(), text));
            }
        }
        final var tools = request.tools() == null ? null : request.tools().stream()
                .filter(t -> t.function() != null)
                .map(t -> new ResponsesTool("function", t.function().name(), t.function().description(), t.function().parameters()))
                .toList();
        final var reasoning = request.reasoningEffort() == null ? null : Map.<String, Object>of("effort", request.reasoningEffort());
        return new ResponsesRequest(request.model(), input, instructions, stream, false, tools,
                request.toolChoice(), reasoning, request.others());
    }

    //
    // shared plumbing
    //

    private BufferedReader openStream(final HttpRequest httpRequest, final BooleanSupplier cancelled) throws IOException, InterruptedException {
        final var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            final String body;
            try (var is = response.body()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (isFlavorMissing(response.statusCode())) {
                throw new FlavorMissingException();
            }
            if (ContextWindowExceededException.isCause(response.statusCode(), body)) {
                throw new ContextWindowExceededException();
            }
            throw new IOException("LLM request failed (" + response.statusCode() + "): " + body);
        }
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(final HttpRequest httpRequest) throws IOException {
        final HttpResponse<String> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return response;
    }

    private Object parse(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.fromString(Object.class, json);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private <A> A tryParse(final Class<A> type, final String json) {
        try {
            return mapper.fromString(type, json);
        } catch (final RuntimeException e) {
            return null;
        }
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

    private static int number(final Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(o.toString());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static String str(final Object o) {
        return o == null ? null : o.toString();
    }

    private static String textOf(final Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            final var sb = new StringBuilder();
            for (final var p : parts) {
                if (p instanceof Map<?, ?> m && m.get("text") != null) {
                    sb.append(m.get("text"));
                }
            }
            return sb.toString();
        }
        return content == null ? "" : content.toString();
    }

    private String reasoningText(final ChatChunk.ChunkDelta delta) {
        return switch (target().reasoningField()) {
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

    private ChatResult toResult(final ChatCompletionResponse response) throws IOException {
        return new ChatResult(response.firstMessage(), response.usage() == null ? Usage.EMPTY : response.usage());
    }

    private boolean isFlavorMissing(final int statusCode) {
        return FLAVOR_MISSING_CODES.contains("(" + statusCode + ")");
    }

    private static String retryAfter(final HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").map(v -> " retry-after=" + v).orElse("");
    }

    private <A> A withRetries(final IoCall<A> call, final BooleanSupplier cancelled) throws IOException, InterruptedException {
        var attempt = 0;
        String retryAfter = null;
        IOException last = null;
        do {
            try {
                return call.get();
            } catch (final IOException e) {
                if (e instanceof FlavorMissingException || e instanceof ContextWindowExceededException) {
                    throw e;
                }
                last = e;
                if (!retryable(e)) {
                    throw e;
                }
                retryAfter = extractRetryAfter(e);
                attempt++;
            }
            if (attempt >= configuration.llm().retriesMaxAttempts() || cancelled.getAsBoolean()) {
                throw new IOException("LLM request failed: " + last.getMessage(), last);
            }
            sleepBackoff(attempt, retryAfter);
        } while (true);
    }

    private boolean retryable(final IOException e) {
        final var m = e.getMessage();
        if (m == null) {
            return true;
        }
        if (m.startsWith("LLM request failed (")) {
            for (final var code : List.of(408, 429)) {
                if (m.startsWith("LLM request failed (" + code + ")")) {
                    return true;
                }
            }
            for (var i = 500; i <= 599; i++) {
                if (m.startsWith("LLM request failed (" + i + ")")) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static String extractRetryAfter(final IOException e) {
        final var m = e.getMessage();
        if (m == null) {
            return null;
        }
        final var i = m.indexOf("retry-after=");
        return i >= 0 ? m.substring(i + "retry-after=".length()).trim() : null;
    }

    private void sleepBackoff(final int attempt, final String retryAfter) throws InterruptedException {
        long delay = configuration.llm().retriesBaseDelayMs() * (1L << Math.min(attempt - 1, 10));
        delay += ThreadLocalRandom.current().nextLong(Math.max(1, delay / 2));
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                delay = Math.max(delay, Long.parseLong(retryAfter.strip()) * 1000);
            } catch (final NumberFormatException nfe) {
                // date form, ignore
            }
        }
        Thread.sleep(Math.min(delay, 60_000));
    }

    private void throttle() throws InterruptedException {
        final var rpm = configuration.llm().rateLimitRequestsPerMinute();
        if (rpm <= 0) {
            return;
        }
        do {
            final long now = System.currentTimeMillis();
            final Long waitMs;
            synchronized (requestTimes) {
                while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= 60_000) {
                    requestTimes.pollFirst();
                }
                if (requestTimes.size() < rpm) {
                    requestTimes.addLast(now);
                    return;
                }
                waitMs = 60_000 - (now - requestTimes.peekFirst()) + 50;
            }
            Thread.sleep(waitMs);
        } while (true);
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class FlavorMissingException extends IOException {
        private FlavorMissingException() {
            super("responses flavor not supported by the provider");
        }
    }

    @FunctionalInterface
    private interface IoCall<A> {
        A get() throws IOException;
    }
}
