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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.DialectLlmClient;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.LlmClientResilienceWrapper;
import io.yupiik.llm.client.LlmResilienceConfig;
import io.yupiik.llm.client.RoutingLlmClient;
import io.yupiik.llm.client.anthropic.AnthropicDialect;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.openai.OpenAICompletionsDialect;
import io.yupiik.llm.client.openai.OpenAIResponsesDialect;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Blocking facade over the shared {@code llm-client} library. Requests are dispatched through a
 * {@link RoutingLlmClient} whose selector resolves, per request, the resilient
 * {@link DialectLlmClient} matching the current provider and {@link ApiFlavor}; concurrency,
 * throttling, retries and {@code retry-after} handling are delegated to the library resilience layer
 * ({@link LlmClientResilienceWrapper}).
 *
 * <p>Keeps the historical public API ({@link #chat}, {@link #stream}, {@link #models}, {@link #model},
 * {@link #setModel}, {@link #setProvider}, {@link #flavor}, {@link #providerId}) and owns the concerns
 * the library must not know about: model/provider resolution, provider-specific option merging, the
 * per-request output budget and the AUTO flavor detection and responses-to-completions fallback.</p>
 */
@ApplicationScoped
public class ChatClient implements AutoCloseable {

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final ContextWindowCache contextWindowCache;
    private final ExtendedHttpClient httpClient;
    private final Map<ClientKey, DialectLlmClient> dialectClients = new HashMap<>();
    private final LlmClientResilienceWrapper resilience;
    private final RoutingLlmClient routing;
    private volatile ApiFlavor resolved = ApiFlavor.AUTO;
    private volatile String modelOverride;
    private volatile ProviderConfiguration targetProvider;
    private volatile boolean structuredOutputUnsupported = false;
    private volatile Consumer<PauseEvent> pauses = event -> {};

    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.clock = clock;
        this.contextWindowCache = new ContextWindowCache(configuration, mapper, clock);
        final var httpClient =
                new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(HttpClient.newHttpClient()));
        this.httpClient = httpClient;
        // context overflow is handled by the agent loop (context compaction), retries after are
        // honored by the library resilience layer
        this.resilience = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(
                        configuration.llm().retriesMaxAttempts(),
                        configuration.llm().retriesBaseDelayMs(),
                        configuration.llm().rateLimitRequestsPerMinute(),
                        configuration.llm().maxConcurrent(),
                        false,
                        true),
                this::onLibraryPause);
        this.routing = new RoutingLlmClient(request -> dialectClient(flavorForRouting(), target()));
    }

    protected ChatClient() {
        this.configuration = null;
        this.mapper = null;
        this.providerManager = null;
        this.clock = null;
        this.contextWindowCache = null;
        this.httpClient = null;
        this.resilience = null;
        this.routing = null;
    }

    /** Cache key: a resilient dialect client is built per (flavor, provider) pair. */
    private record ClientKey(ApiFlavor flavor, ProviderConfiguration provider) {}

    public ApiFlavor flavor() {
        return effectiveFlavor();
    }

    /**
     * The flavor used to select a dialect: an explicitly resolved flavor wins, then the configured
     * provider flavor, then a flavor deduced from the base URL route; {@link ApiFlavor#AUTO} when it
     * cannot be pinned (in which case the responses route is probed first and falls back).
     */
    private ApiFlavor effectiveFlavor() {
        if (resolved != ApiFlavor.AUTO) {
            return resolved;
        }
        final var current = providerManager == null ? null : providerManager.current();
        if (current == null) {
            return ApiFlavor.AUTO;
        }
        final var configured = current.api();
        if (configured != null && configured != ApiFlavor.AUTO) {
            return configured;
        }
        final var detected = ChatRoutes.detectFlavor(current.baseUrl());
        return detected == null ? ApiFlavor.AUTO : detected;
    }

    /** The concrete flavor handed to the router: AUTO resolves to probing the responses route first. */
    private ApiFlavor flavorForRouting() {
        final var flavor = effectiveFlavor();
        return flavor == ApiFlavor.AUTO ? ApiFlavor.RESPONSES : flavor;
    }

    /** The resilient dialect client for a (flavor, provider) pair, built on first use and cached. */
    private DialectLlmClient dialectClient(final ApiFlavor flavor, final ProviderConfiguration provider) {
        if (flavor == null || flavor == ApiFlavor.AUTO || provider == null) {
            throw new IllegalStateException("resolved flavor and provider must be concrete");
        }
        return dialectClients.computeIfAbsent(new ClientKey(flavor, provider), key -> {
            final var spec = new ProviderSpec(
                    ChatRoutes.chatEndpoint(provider.baseUrl(), flavor),
                    provider.resolveApiKey(),
                    Duration.ofSeconds(provider.timeoutSeconds()),
                    provider.headers(),
                    null);
            return new DialectLlmClient(dialect(flavor, spec), resilience);
        });
    }

    private Dialect dialect(final ApiFlavor flavor, final ProviderSpec spec) {
        return switch (flavor) {
            case COMPLETIONS -> new OpenAICompletionsDialect(mapper, httpClient, spec);
            case RESPONSES -> new OpenAIResponsesDialect(mapper, httpClient, spec);
            case ANTHROPIC -> new AnthropicDialect(mapper, httpClient, spec);
            case AUTO -> throw new IllegalStateException("resolved flavor must not be AUTO");
        };
    }

    private void onLibraryPause(final io.yupiik.llm.client.PauseEvent event) {
        try {
            if (event.attempt() <= 0) {
                pauses.accept(PauseEvent.throttle(
                        "Rate limit reached, waiting ~" + (event.waitMs() / 1000) + "s", event.waitMs()));
            } else {
                pauses.accept(PauseEvent.retry(
                        "LLM call failed, retrying in ~" + (event.waitMs() / 1000) + "s (attempt "
                                + (event.attempt() + 1) + ")",
                        event.waitMs()));
            }
        } catch (final RuntimeException e) {
            // listener failures must not break the request
        }
    }

    public String model() {
        final var override = modelOverride;
        if (override != null && !override.isBlank()) {
            return override;
        }
        final var current = providerManager == null ? null : providerManager.current();
        return current == null || current.model() == null ? "" : current.model();
    }

    public void setModel(final String model) {
        this.modelOverride = model == null || model.isBlank() ? null : model;
    }

    public void setProvider(final String id) {
        providerManager.setProvider(id);
        this.modelOverride = null;
        this.resolved = ApiFlavor.AUTO;
        this.structuredOutputUnsupported = false;
        this.targetProvider = null;
        this.dialectClients.clear();
    }

    /** Registers a listener notified whenever the resilience layer delays a request (throttle or retry backoff). */
    public void setPauseListener(final Consumer<PauseEvent> listener) {
        this.pauses = listener == null ? event -> {} : listener;
    }

    public String providerId() {
        return providerManager.currentProviderId();
    }

    /**
     * The resolved context window (tokens) for the current provider + model: an explicit per-provider
     * {@code contextWindow} wins, then the value detected from {@code /v1/models}/cached; {@code 0} when
     * unknown. Used by callers that must stay under the model input budget (e.g. batch sizing).
     */
    public int contextWindow() {
        final var provider = target();
        if (provider == null) {
            return 0;
        }
        if (provider.contextWindow() > 0) {
            return provider.contextWindow();
        }
        return contextWindowCache == null
                ? 0
                : contextWindowCache
                        .get(providerManager.currentProviderId(), model())
                        .orElse(0);
    }

    /**
     * The resolved output token budget for the current provider + model, using the same rules as
     * {@link #applyOutputBudget(ChatCompletionRequest, ApiFlavor)} but with no explicit request cap
     * (per-model override, per-provider default, /v1/models-detected, then {@code 32000}).
     */
    public int outputTokens() {
        final var provider = target();
        if (provider == null) {
            return OutputBudgetResolver.DEFAULT_MAX_TOKEN;
        }
        final var model = model();
        final var detectedMax = contextWindowCache == null
                ? null
                : contextWindowCache
                        .getMaxTokens(providerManager.currentProviderId(), model)
                        .orElse(null);
        final Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        final var resolvedWindow = contextWindow != null
                ? contextWindow
                : (contextWindowCache == null
                        ? null
                        : contextWindowCache
                                .get(providerManager.currentProviderId(), model)
                                .orElse(null));
        return OutputBudgetResolver.resolve(
                null,
                providerManager.outputTokensOverride(provider, model),
                provider.outputTokens(),
                detectedMax,
                resolvedWindow);
    }

    /** The provider currently targeted by the in-flight request (the current provider unless a $provider ref routed it). */
    private ProviderConfiguration target() {
        final var t = targetProvider;
        return t == null ? providerManager.current() : t;
    }

    private String resolveModel() throws IOException {
        final var override = modelOverride;
        final var ref = override != null && !override.isBlank()
                ? override
                : providerManager.current().model();
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
        return new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                request.stream(),
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                others);
    }

    /**
     * Resolve the effective output budget and, when it differs from the request, return a copy carrying
     * it. Providers that require {@code max_tokens} (Anthropic) are guaranteed a value; OpenAI flavors
     * are left untouched to preserve their existing behaviour.
     */
    private ChatCompletionRequest applyOutputBudget(final ChatCompletionRequest request, final ApiFlavor flavor) {
        if (flavor != ApiFlavor.ANTHROPIC) {
            return request;
        }
        final var provider = target();
        final var model = request.model();
        final var detectedMaxTokens = contextWindowCache
                .getMaxTokens(providerManager.currentProviderId(), model)
                .orElse(null);
        Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        if (contextWindow == null) {
            contextWindow = contextWindowCache
                    .get(providerManager.currentProviderId(), model)
                    .orElse(null);
        }
        final var budget = OutputBudgetResolver.resolve(
                request.maxTokens(),
                providerManager.outputTokensOverride(provider, model),
                provider.outputTokens(),
                detectedMaxTokens,
                contextWindow);
        final var maxTokens = request.maxTokens();
        if (maxTokens != null && budget == maxTokens) {
            return request;
        }
        return new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                request.stream(),
                request.tools(),
                request.toolChoice(),
                budget,
                request.reasoningEffort(),
                request.others());
    }

    public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return chat(request, () -> false);
    }

    public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return doWithFallback(useModel(request), cancelled, false, null);
    }

    /**
     * Result of a structured-output call. {@code structured} is true when the provider honoured the
     * JSON Schema and the response is expected to be a conformant JSON object; false when the schema
     * was unsupported (or rejected twice) and the caller must fall back to its own parsing.
     */
    public record StructuredResult(ChatResult result, boolean structured) {}

    /**
     * Run a non-streaming completion asking the provider for structured output matching the given JSON
     * Schema. The flavor-specific request field is composed here (response_format for completions,
     * text.format for the responses API, output_config for Anthropic) and carried to the wire through
     * the request extras. If the provider rejects the schema with a 400 it is retried once without it;
     * a second 400 marks the provider as not supporting structured output for the rest of the session,
     * so subsequent calls go straight to the plain path.
     */
    public StructuredResult chatStructured(
            final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
            throws IOException, InterruptedException {
        final var model = resolveModel();
        final var flavor = effectiveFlavor();
        final var base = new LinkedHashMap<String, Object>();
        if (request.others() != null) {
            base.putAll(request.others());
        }
        final var otherOptions = mergeOptions(null);
        if (otherOptions != null) {
            base.putAll(otherOptions);
        }
        final var plainReq = new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                false,
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                base);
        final var structuredReq = new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                false,
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                withStructuredField(base, flavor, schemaName, schema));
        if (structuredOutputUnsupported) {
            return new StructuredResult(chatInternal(plainReq), false);
        }
        try {
            return new StructuredResult(chatInternal(structuredReq), true);
        } catch (final IOException first) {
            if (!isStatus400(first)) {
                throw first;
            }
            try {
                return new StructuredResult(chatInternal(plainReq), false);
            } catch (final IOException second) {
                if (!isStatus400(second)) {
                    throw second;
                }
                structuredOutputUnsupported = true;
                return new StructuredResult(chatInternal(plainReq), false);
            }
        }
    }

    private static boolean isStatus400(final IOException e) {
        final var m = e.getMessage();
        return m != null && m.startsWith("LLM request failed (400)");
    }

    /** Compose the flavor-specific JSON Schema field into a copy of {@code others}. */
    private static Map<String, Object> withStructuredField(
            final Map<String, Object> others,
            final ApiFlavor flavor,
            final String schemaName,
            final Map<String, Object> schema) {
        final var out = new LinkedHashMap<String, Object>(others);
        switch (flavor) {
            case ANTHROPIC ->
                out.put("output_config", Map.of("format", Map.of("type", "json_schema", "schema", schema)));
            case RESPONSES ->
                out.put(
                        "text",
                        Map.of(
                                "format",
                                Map.of("type", "json_schema", "name", schemaName, "schema", schema, "strict", true)));
            case COMPLETIONS ->
                out.put(
                        "response_format",
                        Map.of(
                                "type",
                                "json_schema",
                                "json_schema",
                                Map.of("name", schemaName, "schema", schema, "strict", true)));
            default -> {
                // AUTO already resolved to a concrete flavor by the caller; treat as completions fallback
                out.put(
                        "response_format",
                        Map.of(
                                "type",
                                "json_schema",
                                "json_schema",
                                Map.of("name", schemaName, "schema", schema, "strict", true)));
            }
        }
        return out;
    }

    /** Route a request through the current flavor, reusing the shared dispatch logic. */
    private ChatResult chatInternal(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return doWithFallback(request, () -> false, false, null);
    }

    public Message stream(final ChatCompletionRequest request, final Consumer<LlmStreamEvent> onDelta)
            throws IOException, InterruptedException {
        return stream(request, onDelta, () -> false);
    }

    public Message stream(
            final ChatCompletionRequest request,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var model = resolveModel();
        final var effective = new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                true,
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                mergeOptions(request.others()));
        return doWithFallback(effective, cancelled, true, onDelta);
    }

    @SuppressWarnings("unchecked")
    private <A> A doWithFallback(
            final ChatCompletionRequest request,
            final BooleanSupplier cancelled,
            final boolean stream,
            final Consumer<LlmStreamEvent> onDelta)
            throws IOException, InterruptedException {
        final Consumer<LlmStreamEvent> onDeltaSafe = onDelta == null ? event -> {} : onDelta;
        final var effective =
                effectiveFlavor() == ApiFlavor.ANTHROPIC ? applyOutputBudget(request, ApiFlavor.ANTHROPIC) : request;
        try {
            if (stream) {
                final var publisher = LlmWire.join(routing.stream(LlmWire.toLlmRequest(effective), cancelled));
                return (A) LlmWire.pump(publisher, onDeltaSafe, cancelled);
            }
            return (A) LlmWire.toChatResult(LlmWire.join(routing.chat(LlmWire.toLlmRequest(effective), cancelled)));
        } catch (final DialectNotSupportedException e) {
            if (resolved != ApiFlavor.AUTO) {
                throw e; // explicitly configured flavor: surface the incompatibility
            }
            resolved = ApiFlavor.COMPLETIONS; // AUTO probed the responses route first: fall back
            return doWithFallback(request, cancelled, stream, onDelta);
        }
    }

    public ModelsResponse models() throws IOException, InterruptedException {
        final var flavor = effectiveFlavor();
        try {
            return LlmWire.toModelsResponse(
                    LlmWire.join(dialectClient(flavor == ApiFlavor.AUTO ? ApiFlavor.COMPLETIONS : flavor, target())
                            .models()));
        } catch (final DialectNotSupportedException e) {
            return new ModelsResponse(List.of());
        }
    }

    @Override
    public void close() {
        if (dialectClients != null) {
            dialectClients.values().forEach(DialectLlmClient::close);
        }
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (final RuntimeException e) {
                // ignore close failures on shutdown
            }
        }
    }
}
