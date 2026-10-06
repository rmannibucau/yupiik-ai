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
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.impl.CurlLoggerListener;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.runtime.Cancellations;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Blocking facade over the shared {@code llm-client} library. Requests are dispatched through a
 * {@link RoutingLlmClient} whose selector resolves, per request, the resilient
 * {@link DialectLlmClient} matching the current provider and {@link ApiFlavor}; concurrency,
 * throttling, retries and {@code retry-after} handling are delegated to the library resilience layer
 * ({@link LlmClientResilienceWrapper}).
 *
 * <p>Keeps the historical public API ({@link #chat}, {@link #stream}, {@link #models}, {@link #model},
 * {@link ChatSettings}, {@link #flavor} and {@link #providerId}) and owns the concerns
 * the library must not know about: model/provider resolution, provider-specific option merging, the
 * per-request output budget and the AUTO flavor detection and responses-to-completions fallback.</p>
 */
@ApplicationScoped
public class ChatClient implements AutoCloseable {

    private static final java.util.logging.Logger LOGGER = Logger.getLogger(ChatClient.class.getName());

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final ToolCallArgumentsRepairer toolCallArgumentsRepairer;
    private final ContextWindowCache contextWindowCache;
    private final ExtendedHttpClient httpClient;
    private final boolean ownsHttpClient;
    /** Shared application scheduler parking the resilient retry waits; {@code null} falls back to the JDK delayed executor. */
    private final ScheduledExecutorService scheduler;

    private final Map<ClientKey, DialectLlmClient> dialectClients = new HashMap<>();
    private final Map<ClientKey, LlmClientResilienceWrapper> resiliences = new HashMap<>();
    private final Set<String> budgetLogged = ConcurrentHashMap.newKeySet();
    // per-provider caches: the flavor AUTO fallback, structured-output support and the model list are
    // provider-scoped facts. With per-session providers (one per tab) a single process-wide slot would
    // leak a tab A provider observation (AUTO fell back to completions, structured unsupported) to tab B
    private final ConcurrentHashMap<String, ApiFlavor> resolvedFlavors = new ConcurrentHashMap<>();
    private final Set<String> structuredOutputUnsupported = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ModelsResponse> cachedModelsByProvider = new ConcurrentHashMap<>();
    private volatile ProviderConfiguration targetProvider;

    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient,
            final ToolCallArgumentsRepairer toolCallArgumentsRepairer,
            final ScheduledExecutorService scheduler) {
        this(configuration, mapper, providerManager, clock, httpClient, toolCallArgumentsRepairer, false, scheduler);
    }

    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient,
            final ToolCallArgumentsRepairer toolCallArgumentsRepairer) {
        this(configuration, mapper, providerManager, clock, httpClient, toolCallArgumentsRepairer, false, null);
    }

    /**
     * Convenience constructor creating and owning a default {@link ExtendedHttpClient} delegating to a plain
     * {@link HttpClient#newHttpClient()}. Reserved for tests and programmatic usage; the managed application
     * path injects the shared client through the constructor above.
     */
    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient) {
        this(
                configuration,
                mapper,
                providerManager,
                clock,
                httpClient,
                new ToolCallArgumentsRepairer(mapper),
                false,
                null);
    }

    /**
     * Convenience constructor creating and owning a default {@link ExtendedHttpClient} delegating to a plain
     * {@link HttpClient#newHttpClient()}. Reserved for tests and programmatic usage; the managed application
     * path injects the shared client through the constructor above.
     */
    public ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock) {
        this(
                configuration,
                mapper,
                providerManager,
                clock,
                defaultClient(configuration),
                new ToolCallArgumentsRepairer(mapper),
                true,
                null);
    }

    private ChatClient(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final ExtendedHttpClient httpClient,
            final ToolCallArgumentsRepairer toolCallArgumentsRepairer,
            final boolean ownsHttpClient,
            final ScheduledExecutorService scheduler) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.clock = clock;
        this.toolCallArgumentsRepairer = toolCallArgumentsRepairer;
        this.contextWindowCache = new ContextWindowCache(configuration, mapper, clock);
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
        this.scheduler = scheduler;
        // context overflow is handled by the agent loop (context compaction), retries after are
        // honored by the library resilience layer; per-provider clients get their own resilience
        // wrapper so the concurrency budget is scoped to one provider base URL
    }

    private static ExtendedHttpClient defaultClient(final CodingAgentConfiguration configuration) {
        final var clientConfig = new ExtendedHttpClientConfiguration()
                .setDelegate(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build());
        if (configuration.httpClient().debug()) {
            clientConfig.setRequestListeners(List.of(new CurlLoggerListener(
                    new CurlLoggerListener.Configuration(java.util.logging.Logger.getLogger("yca.http"))
                            .setLogPayload(true))));
        }
        return new ExtendedHttpClient(clientConfig);
    }

    private LlmClientResilienceWrapper resilience(final int providerBounds) {
        return new LlmClientResilienceWrapper(
                new LlmResilienceConfig(
                        configuration.llm().retriesMaxAttempts(),
                        configuration.llm().retriesBaseDelayMs(),
                        configuration.llm().rateLimitRequestsPerMinute(),
                        providerBounds,
                        false,
                        true,
                        configuration.llm().retryAfterMaxWaitMs(),
                        1.0d,
                        configuration.llm().concurrencyAdaptationMs()),
                null,
                // the shared scheduler makes the retry waits cancellable (interrupt during a
                // 429/retry-after backoff ends the wait early); null keeps the JDK fallback
                scheduler);
    }

    protected ChatClient() {
        this.configuration = null;
        this.mapper = null;
        this.providerManager = null;
        this.clock = null;
        this.toolCallArgumentsRepairer = null;
        this.contextWindowCache = null;
        this.httpClient = null;
        this.ownsHttpClient = false;
        this.scheduler = null;
    }

    /** Cache key: a resilient dialect client is built per (flavor, provider) pair. */
    private record ClientKey(ApiFlavor flavor, ProviderConfiguration provider) {}

    public ApiFlavor flavor() {
        // report the flavor actually used for routing (AUTO resolves to a concrete route for bare /v1)
        return flavorForRouting();
    }

    /**
     * The flavor used to select a dialect: an explicitly resolved flavor wins, then the configured
     * provider flavor, then a flavor deduced from the base URL route; {@link ApiFlavor#AUTO} when it
     * cannot be pinned (in which case the responses route is probed first and falls back).
     * The effective provider is the session's (per-tab) one when a settings override exists.
     */
    private ApiFlavor effectiveFlavor() {
        return effectiveFlavor(null);
    }

    private ApiFlavor effectiveFlavor(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return ApiFlavor.AUTO;
        }
        final var configured = provider.api();
        if (configured != null && configured != ApiFlavor.AUTO) {
            return configured;
        }
        final var detected = ChatRoutes.detectFlavor(provider.baseUrl());
        return detected == null ? ApiFlavor.AUTO : detected;
    }

    /** The concrete flavor handed to the router: AUTO resolves to probing the responses route first. */
    private ApiFlavor flavorForRouting() {
        return flavorForRouting(null);
    }

    private ApiFlavor flavorForRouting(final ChatSettings settings) {
        final var flavor = effectiveFlavor(settings);
        if (flavor != ApiFlavor.AUTO) {
            return flavor;
        }
        // a provider that already fell back once (AUTO probed /responses, got DialectNotSupported)
        // is pinned to chat completions: re-probing each retry would loop on the same failure
        final var provider = provider(settings);
        if (provider != null) {
            final var pinned = resolvedFlavors.get(providerIdOf(provider));
            if (pinned != null && pinned != ApiFlavor.AUTO) {
                return pinned;
            }
        }
        // A bare base URL (no recognizable /responses, /chat/completions or /messages route) cannot be
        // pinned reliably; default to chat completions, the common OpenAI-compatible layout opencode
        // uses, so reasoning_content flows without requiring an explicit reasoning_effort.
        if (provider != null && ChatRoutes.detectFlavor(provider.baseUrl()) == null) {
            return ApiFlavor.COMPLETIONS;
        }
        return ApiFlavor.RESPONSES;
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
            final var bounds = provider.maxConcurrent() >= 0
                    ? provider.maxConcurrent()
                    : configuration.llm().maxConcurrent();
            return new DialectLlmClient(
                    dialect(flavor, spec), resiliences.computeIfAbsent(key, _ -> resilience(bounds)));
        });
    }

    private Dialect dialect(final ApiFlavor flavor, final ProviderSpec spec) {
        final var debugLogger =
                configuration.httpClient().debug() ? java.util.logging.Logger.getLogger("yca.http") : null;
        return switch (flavor) {
            case COMPLETIONS -> {
                final var d = new OpenAICompletionsDialect(mapper, httpClient, spec);
                if (debugLogger != null) {
                    d.setOnRawLine(debugLogger::fine);
                }
                yield d;
            }
            case RESPONSES -> {
                final var d = new OpenAIResponsesDialect(mapper, httpClient, spec);
                if (debugLogger != null) {
                    d.setOnRawLine(debugLogger::fine);
                }
                yield d;
            }
            case ANTHROPIC -> {
                final var d = new AnthropicDialect(mapper, httpClient, spec);
                if (debugLogger != null) {
                    d.setOnRawLine(debugLogger::fine);
                }
                yield d;
            }
            case AUTO -> throw new IllegalStateException("resolved flavor must not be AUTO");
        };
    }

    /**
     * Translate a library pause into the core {@link PauseEvent} and hand it to the per-call listener
     * (see {@link #chat(ChatCompletionRequest, ChatSettings, Consumer, BooleanSupplier)}). The listener
     * travels with the call so concurrent sessions each get their own notices instead of fighting over
     * a single process-wide slot.
     */
    private void onLibraryPause(final io.yupiik.llm.client.PauseEvent event, final Consumer<PauseEvent> target) {
        if (target == null) {
            return;
        }
        try {
            if (event.reason() != null && event.reason().startsWith("concurrency limit")) {
                target.accept(PauseEvent.retry(event.reason(), 0));
            } else if (event.attempt() <= 0) {
                target.accept(PauseEvent.throttle(
                        "Rate limit reached, waiting ~" + (event.waitMs() / 1000) + "s", event.waitMs()));
            } else {
                final var max = configuration.llm().retriesMaxAttempts();
                final var summary = failureSummary(event.reason());
                final var detail = summary.isEmpty() ? "" : " (" + summary + ")";
                target.accept(PauseEvent.retry(
                        "LLM call failed" + detail + ", retrying in ~" + (event.waitMs() / 1000) + "s (attempt "
                                + (event.attempt() + 1) + "/" + max + ")",
                        event.waitMs()));
            }
        } catch (final RuntimeException e) {
            // listener failures must not break the request
        }
    }

    /** Compact "status + provider message" for the retry notice; empty when not parseable. */
    private static String failureSummary(final String reason) {
        if (reason == null || !reason.startsWith("LLM request failed (")) {
            return "";
        }
        final var end = reason.indexOf(')');
        if (end < 0) {
            return "";
        }
        final var status = reason.substring("LLM request failed (".length(), end);
        final var body = reason.substring(end + 2).trim();
        final var cut = bodyName(body);
        return status + (cut.isEmpty() ? "" : " " + cut);
    }

    private static String bodyName(final String body) {
        // keep the provider "message" field when present, else a short head of the payload
        final var marker = "\"message\":\"";
        final var i = body.indexOf(marker);
        if (i >= 0) {
            final var start = i + marker.length();
            final var j = body.indexOf('"', start);
            return j >= 0 ? clamp(body.substring(start, j)) : clamp(body.substring(start));
        }
        return clamp(body);
    }

    private static String clamp(final String value) {
        return value == null || value.isBlank()
                ? ""
                : (value.length() <= 120 ? value : value.substring(0, 117) + "...");
    }

    /** The configured model of the active provider (no per-session override applied). */
    public String model() {
        return model(null);
    }

    /**
     * The effective model for a session: its explicit override when set, else the active
     * provider's model. {@code settings} is the per-session {@link ChatSettings}, may be null.
     */
    public String model(final ChatSettings settings) {
        final var override = settings == null ? null : settings.model();
        if (override != null && !override.isBlank()) {
            return override;
        }
        final var provider = provider(settings);
        return provider == null || provider.model() == null ? "" : provider.model();
    }

    /** The currently selected variant name, or null when none is forced (implicit family defaults apply). */
    public String variant() {
        return null;
    }

    /** The variant selected by a session, or null when none is forced. */
    public String variant(final ChatSettings settings) {
        return settings == null ? null : settings.variant();
    }

    /**
     * The in-memory sampling overrides for the current model (session only, never persisted), or
     * null when none are set.
     */
    public SamplingOverride sampling() {
        return null;
    }

    /** The sampling overrides of a session, or null when none are set. */
    public SamplingOverride sampling(final ChatSettings settings) {
        return settings == null ? null : settings.sampling();
    }

    /** Variants available for the current model, provider-level or per-model, else the implicit tuner presets. */
    public List<ModelVariantConfiguration> variants() {
        return variants((ChatSettings) null);
    }

    /** Variants available for the session's current model. */
    public List<ModelVariantConfiguration> variants(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return List.of();
        }
        var model = settings == null ? null : settings.model();
        if (model == null || model.isBlank()) {
            model = provider.model();
        }
        return effectiveVariants(provider, model);
    }

    /** Variants available for the specific model name against the session's provider, without changing the
     * active model. Used to resolve the variants of a model being typed or picked in {@code /model}
     * before it becomes the active one. Blank model falls back to the current model.
     */
    public List<ModelVariantConfiguration> variants(final String model) {
        return variants((ChatSettings) null, model);
    }

    /** Variants for {@code model} against the session's provider ({@code settings}), blank model falls back to the current one. */
    public List<ModelVariantConfiguration> variants(final ChatSettings settings, final String model) {
        final var provider = provider(settings);
        if (provider == null) {
            return List.of();
        }
        final var effective = model == null || model.isBlank() ? model(settings) : model.trim();
        return effectiveVariants(provider, effective);
    }

    /**
     * The effective implicit "default" variant for the current model, i.e. the sampling values the
     * tuner would apply when no named variant and no override is set, or null when the model has no
     * implicit defaults. The trio respects a current in-memory override: a width-omitted field (none)
     * surfaces as null so callers know it would not be sent; a forced value surfaces as that value.
     */
    public ModelVariantConfiguration implicitVariant() {
        return implicitVariant((ChatSettings) null);
    }

    /**
     * The effective implicit "default" variant for the session's current model, i.e. the sampling values the
     * tuner would apply when no named variant and no override is set, or null when the model has no
     * implicit defaults. The trio respects the session's override: a width-omitted field (none)
     * surfaces as null so callers know it would not be sent; a forced value surfaces as that value.
     */
    public ModelVariantConfiguration implicitVariant(final ChatSettings settings) {
        final var provider = provider(settings);
        final var providerId = provider == null ? null : provider.id();
        var model = settings == null ? null : settings.model();
        if (model == null || model.isBlank()) {
            model = provider == null ? null : provider.model();
        }
        if (model == null) {
            return null;
        }
        Double temperature = DefaultModelTuner.temperature(model, providerId);
        Double topP = DefaultModelTuner.topP(model, providerId);
        Double topK = DefaultModelTuner.topK(model, providerId);
        final var sampling = sampling(settings);
        if (sampling != null) {
            final var t = sampling.get(SamplingField.TEMPERATURE);
            if (t != null) {
                temperature = t.orElse(null);
            }
            final var p = sampling.get(SamplingField.TOP_P);
            if (p != null) {
                topP = p.orElse(null);
            }
            final var k = sampling.get(SamplingField.TOP_K);
            if (k != null) {
                topK = k.orElse(null);
            }
        }
        if (temperature == null && topP == null && topK == null) {
            return null;
        }
        return new ModelVariantConfiguration("default", temperature, topP, topK, null, null, null);
    }

    /**
     * The implicit {@code default} variant for a specific model name against the current provider,
     * without changing the active model. A blank model falls back to the current model. The current
     * in-memory sampling override is not applied here since the model is not yet the active one.
     */
    public ModelVariantConfiguration implicitVariant(final String model) {
        final var provider = provider(null);
        final var providerId = provider == null ? null : provider.id();
        if (model == null || model.isBlank()) {
            return implicitVariant();
        }
        final var m = model.trim();
        Double temperature = DefaultModelTuner.temperature(m, providerId);
        Double topP = DefaultModelTuner.topP(m, providerId);
        Double topK = DefaultModelTuner.topK(m, providerId);
        if (temperature == null && topP == null && topK == null) {
            return null;
        }
        return new ModelVariantConfiguration("default", temperature, topP, topK, null, null, null);
    }

    public void setProvider(final String id) {
        providerManager.setProvider(id);
        final var providerId = providerManager.currentProviderId();
        this.targetProvider = null;
        this.dialectClients.clear();
        // the new provider never observed the response route or structured output yet
        this.resolvedFlavors.remove(providerId);
        this.structuredOutputUnsupported.remove(providerId);
        this.cachedModelsByProvider.remove(providerId);
    }

    public String providerId() {
        return providerId(null);
    }

    /** The effective provider id for a session: the per-tab override when set, else the process default. */
    public String providerId(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            return providerManager == null ? "" : providerManager.currentProviderId();
        }
        return provider.id();
    }

    /** The currently active provider configuration (may be null when nothing is configured). */
    public ProviderConfiguration currentProvider() {
        return provider(null);
    }

    public ProviderConfiguration currentProvider(final ChatSettings settings) {
        return provider(settings);
    }

    /**
     * The effective provider for a session: its explicit override when set, else the process-wide
     * current provider. {@code settings} is the per-session {@link ChatSettings}, may be null.
     */
    public ProviderConfiguration provider(final ChatSettings settings) {
        final var override = settings == null ? null : settings.providerId();
        if (override != null && !override.isBlank()) {
            return providerManager.provider(override);
        }
        return providerManager == null ? null : providerManager.current();
    }

    /** Position of a provider for styling: 0 for the flat-scalar default, {@code 1 + index} within {@code providers}. */
    public int providerPosition(final ProviderConfiguration provider) {
        return providerManager == null ? 0 : providerManager.providerPosition(provider);
    }

    /**
     * The resolved context window (tokens) for the current provider + model: an explicit per-provider
     * {@code contextWindow} wins, then the value detected from {@code /v1/models}/cached; {@code 0} when
     * unknown. Used by callers that must stay under the model input budget (e.g. batch sizing).
     */
    public int contextWindow() {
        return contextWindow(null);
    }

    /** The session's context window when the settings carry a provider override, else the process default's. */
    public int contextWindow(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return 0;
        }
        if (provider.contextWindow() > 0) {
            return provider.contextWindow();
        }
        final var id = providerIdOf(provider);
        return contextWindowCache == null
                ? 0
                : contextWindowCache.get(id, model(settings)).orElse(0);
    }

    /**
     * The resolved output token budget for the current provider + model, using the same rules as
     * {@link #applyOutputBudget(ChatCompletionRequest, ApiFlavor)} but with no explicit request cap
     * (per-model override, per-provider default, /v1/models-detected, then {@code 32000}).
     */
    public int outputTokens() {
        return outputTokens(null);
    }

    /** The session's output budget when the settings carry a provider override, else the process default's. */
    public int outputTokens(final ChatSettings settings) {
        final var provider = provider(settings);
        if (provider == null) {
            return OutputBudgetResolver.DEFAULT_MAX_TOKEN;
        }
        final var model = model(settings);
        final var id = providerIdOf(provider);
        final var detectedMax = contextWindowCache == null
                ? null
                : contextWindowCache.getMaxTokens(id, model).orElse(null);
        final Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        final var resolvedWindow = contextWindow != null
                ? contextWindow
                : (contextWindowCache == null
                        ? null
                        : contextWindowCache.get(id, model).orElse(null));
        return OutputBudgetResolver.resolve(
                null,
                providerManager.outputTokensOverride(provider, model),
                provider.outputTokens(),
                detectedMax,
                resolvedWindow);
    }

    /** The request's session selection, never null (defaults to {@link ChatSettings#EMPTY}). */
    private static ChatSettings settings(final ChatCompletionRequest request) {
        return request == null || request.settings() == null ? ChatSettings.EMPTY : request.settings();
    }

    /** The provider currently targeted by the in-flight request: the session's effective provider
     * (per-tab override, then process current) unless a {@code $provider} ref routed it. */
    private ProviderConfiguration target(final ChatCompletionRequest request) {
        final var t = targetProvider;
        return t == null ? provider(settings(request)) : t;
    }

    /**
     * Resolve the concrete model for a request: an explicit request model wins (plain name or
     * {@code $provider/model}), then the request's session selection ({@link ChatSettings#model()}),
     * then the effective provider's configured model. A {@code $provider/model} reference
     * additionally routes the request to that provider.
     */
    private String resolveModel(final ChatCompletionRequest request) throws IOException {
        final var settings = settings(request);
        final var provider = provider(settings);
        final var explicit = request.model();
        final var override = settings.model();
        final var ref = explicit != null && !explicit.isBlank()
                ? explicit
                : (override != null && !override.isBlank() ? override : (provider == null ? null : provider.model()));
        final var resolvedModel = providerManager.resolve(ref);
        if (ref != null && ref.startsWith("$")) {
            // an explicit $provider/model reference routes this request to that provider; a plain
            // reference must never capture the process provider into the shared routing slot (it would
            // leak to every other tab running on its own provider)
            this.targetProvider = resolvedModel.provider();
        } else {
            this.targetProvider = null;
        }
        final var model = resolvedModel.model();
        if (model == null || model.isBlank()) {
            throw new IOException(
                    "no model configured: set yca.provider.model (--yca-provider-model, configuration.json or YCA_PROVIDER_MODEL)");
        }
        return model.trim();
    }

    private ChatCompletionRequest useModel(final ChatCompletionRequest request) throws IOException {
        return withPromotedExtras(applyModelDefaults(request));
    }

    /**
     * Enrich a request with the resolved model plus its effective tuning defaults (provider-level and
     * per-model {@code providerSpecificOptions}), an optional selected named variant, and the implicit
     * per-family sampling defaults. Every facet follows a put-if-absent semantic: an explicit request
     * value wins, then the selected variant, then the implicit family default; nothing is forced.
     * The provider routing is resolved here so the variant and defaults are those of the target model.
     */
    private ChatCompletionRequest applyModelDefaults(final ChatCompletionRequest request) throws IOException {
        final var model = resolveModel(request);
        final var provider = target(request);
        final var providerId = provider == null ? null : provider.id();
        final var others = new LinkedHashMap<String, Object>();
        if (request.others() != null) {
            others.putAll(request.others());
        }
        final var providerOptions = providerManager.providerOptions(provider);
        if (!providerOptions.isEmpty()) {
            others.putAll(providerOptions);
        }
        final var tuning = providerManager.tuning(provider, model);
        final var tuningOptions = providerManager.tuningOptions(tuning);
        deepMergePutIfAbsent(others, tuningOptions);
        final var variant = selectedVariant(provider, model, request);
        if (variant != null && variant.extra() != null && variant.extra().startsWith("{")) {
            deepMergePutIfAbsent(others, parseRaw(variant.extra()));
        }

        final var temperature = resolveSampling(
                SamplingField.TEMPERATURE,
                request.temperature(),
                "temperature",
                false,
                others,
                variant == null ? null : variant::temperature,
                DefaultModelTuner::temperature,
                model,
                providerId,
                request);
        final var topP = resolveSampling(
                SamplingField.TOP_P,
                request.topP(),
                "top_p",
                false,
                others,
                variant == null ? null : variant::topP,
                DefaultModelTuner::topP,
                model,
                providerId,
                request);
        final var topK = resolveSampling(
                SamplingField.TOP_K,
                null,
                "top_k",
                true,
                others,
                variant == null ? null : variant::topK,
                DefaultModelTuner::topK,
                model,
                providerId,
                request);
        if (topK != null) {
            others.put("top_k", topK);
        }
        Integer maxTokens = request.maxTokens();
        if (maxTokens == null && variant != null && variant.maxTokens() != null && variant.maxTokens() > 0) {
            final var flavor = effectiveFlavor();
            if (flavor == ApiFlavor.ANTHROPIC || flavor == ApiFlavor.AUTO) {
                maxTokens = variant.maxTokens();
            } else {
                others.putIfAbsent("max_completion_tokens", variant.maxTokens());
            }
        }
        final var reasoningEffort = resolveReasoningEffort(
                request.reasoningEffort(),
                others,
                model,
                variant == null ? null : variant.reasoningEffort(),
                tuning == null ? null : tuning.reasoningEffort(),
                providerId);
        if (isClaude(model) && reasoningEffort != null) {
            applyClaudeThinking(others, reasoningEffort);
        }
        return new ChatCompletionRequest(
                model,
                applyCaching(request.messages(), providerId),
                temperature,
                topP,
                request.stream(),
                request.tools(),
                request.toolChoice(),
                maxTokens,
                reasoningEffort,
                others,
                request.settings());
    }

    /**
     * Resolves the effective value of one sampling field for the request, folding in any in-memory
     * override plus the selected variant and the implicit per-family tuner default. Precedence within a
     * field: an in-memory override of {@code none} (force-omit) beats everything; then the explicit
     * request value; then, when a raw value was already supplied through {@code others} and the caller
     * opts in via {@code respectOthersGuard}, that raw value is kept; then the override value; then the
     * variant value; finally the {@link DefaultModelTuner} value. When the override is {@code none} any
     * previously injected raw value for {@code wireKey} is dropped so the payload omits the field and the
     * provider default applies.
     */
    private Double resolveSampling(
            final SamplingField field,
            final Double requestValue,
            final String wireKey,
            final boolean respectOthersGuard,
            final Map<String, Object> others,
            final Supplier<Double> variantValue,
            final BiFunction<String, String, Double> tuner,
            final String model,
            final String providerId,
            final ChatCompletionRequest request) {
        final var sampling = sampling(request.settings());
        final var overridden = sampling == null ? null : sampling.get(field);
        if (overridden != null && overridden.isEmpty()) {
            // none: force-omit, so the server default applies; drop any request/raw value
            if (wireKey != null) {
                others.remove(wireKey);
            }
            return null;
        }
        if (requestValue != null) {
            return requestValue;
        }
        if (respectOthersGuard && wireKey != null && others.containsKey(wireKey)) {
            return null;
        }
        if (overridden != null && overridden.isPresent()) {
            return overridden.get();
        }
        if (variantValue != null) {
            return variantValue.get();
        }
        return tuner.apply(model, providerId);
    }

    /**
     * Message-level prompt caching, highly inspired from opencode's applyCaching: the first two system
     * messages and the last two non-system messages carry a provider-specific cache breakpoint so the
     * stable prefix (system prompt, tools) and the tail (turning context) become cache hits across turns.
     * The cache key depends on the provider family. Array-content messages are cached at each block level,
     * plain-text messages at the message level. Disabled for providers without a caching story.
     */
    private static List<Message> applyCaching(final List<Message> messages, final String providerId) {
        if (messages == null || messages.size() <= 1 || providerId == null) {
            return messages;
        }
        final var cache = cacheMarker(providerId);
        if (cache == null) {
            return messages;
        }
        final var systems = new ArrayList<Message>();
        final var others = new ArrayList<Message>();
        for (final var m : messages) {
            if ("system".equals(m.role())) {
                systems.add(m);
            } else {
                others.add(m);
            }
        }
        if (systems.isEmpty()) {
            return messages;
        }
        final var marked = new HashSet<Integer>();
        for (int i = 0; i < systems.size() && i < 2; i++) {
            marked.add(i);
        }
        final int lastIdx = messages.size() - 1;
        if (others.size() > 1) {
            // within the flat list, mark the two non-system tail messages
            for (int i = 0; i < others.size() && i < 2; i++) {
                marked.add(lastIdx - i);
            }
        }
        if (marked.isEmpty()) {
            return messages;
        }
        var changed = false;
        final var out = new Message[messages.size()];
        for (final var idx : marked) {
            final var m = messages.get(idx);
            final var updated = cacheMessage(m, cache);
            out[idx] = updated;
            changed |= updated != m;
        }
        if (!changed) {
            return messages;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (out[i] == null) {
                out[i] = messages.get(i);
            }
        }
        return List.of(out);
    }

    /**
     * The provider-specific cache breakpoint marker, or null when this provider does not expose message
     * caching. The key mirrors the target dialect's wire field: <ul>
     * <li>Anthropic-like and most OpenAI-compatible proxies use {@code cache_control} with
     * {@code {"type":"ephemeral"}};</li>
     * <li>Bedrock providers use a {@code cachePoint} flag.</li></ul>
     */
    private static Map<String, Object> cacheMarker(final String providerId) {
        final var id = providerId.toLowerCase(Locale.ROOT);
        if (id.contains("bedrock")) {
            return Map.of("cachePoint", true, "mapping", "cachePoint");
        }
        if (id.contains("anthropic") || id.contains("openrouter") || id.contains("alibaba")) {
            return Map.of("cache_control", Map.of("type", "ephemeral"), "mapping", "cache_control");
        }
        return null;
    }

    private static Message cacheMessage(final Message message, final Map<String, Object> cache) {
        final var key = String.valueOf(cache.get("mapping"));
        final Object marker = cache.get(key);
        final var content = message.content();
        if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            final Object last = blocks.get(blocks.size() - 1);
            if (last instanceof Map<?, ?> lastBlock) {
                final var outBlocks = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < blocks.size() - 1; i++) {
                    @SuppressWarnings("unchecked")
                    final var m = (Map<String, Object>) blocks.get(i);
                    outBlocks.add(m);
                }
                final var block = new LinkedHashMap<String, Object>();
                lastBlock.forEach((k, v) -> block.put(String.valueOf(k), v));
                block.put(key, marker);
                outBlocks.add(block);
                return new Message(
                        message.role(),
                        outBlocks,
                        message.name(),
                        message.toolCalls(),
                        message.toolCallId(),
                        message.others());
            }
        }
        final var others = message.others();
        if (others != null && others.containsKey(key)) {
            return message;
        }
        final java.util.Map<String, Object> merged = new LinkedHashMap<>();
        if (others != null) {
            merged.putAll(others);
        }
        merged.put(key, marker);
        return new Message(
                message.role(), message.content(), message.name(), message.toolCalls(), message.toolCallId(), merged);
    }

    /** The named variant requested via {@code /model}, validated against the configured variants, else null. */
    private ModelVariantConfiguration selectedVariant(
            final ProviderConfiguration provider, final String model, final ChatCompletionRequest request) {
        final var selected =
                request.settings() == null ? null : request.settings().variant();
        if (selected == null || selected.isBlank()) {
            return null;
        }
        for (final var variant : effectiveVariants(provider, model)) {
            if (selected.equals(variant.name())) {
                return variant;
            }
        }
        return null;
    }

    /**
     * The variants to consider for a model: the configured ones (per-model or provider-level), or,
     * when none are configured, the implicit reasoning-effort presets derived from the model family
     * and the API flavor of the target provider.
     */
    private List<ModelVariantConfiguration> effectiveVariants(
            final ProviderConfiguration provider, final String model) {
        final var configured = providerManager.variants(provider, model);
        if (!configured.isEmpty()) {
            return configured;
        }
        final var flavor = flavorOf(provider);
        final var releaseDate = provider == null ? null : provider.releaseDate();
        final var budget = outputTokens(provider, model);
        return DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                model, provider == null ? null : provider.id(), flavor, budget, releaseDate));
    }

    /**
     * Resolve the request flavor from a provider: an explicitly resolved flavor wins, then the configured
     * provider flavor, then the flavor deduced from the provider base URL route, defaulting to the plain
     * OpenAI-compatible chat wire (chat completions) when it cannot be pinned.
     */
    private static ApiFlavor flavorOf(final ProviderConfiguration provider) {
        if (provider == null) {
            return ApiFlavor.COMPLETIONS;
        }
        final var configured = provider.api();
        if (configured != null && configured != ApiFlavor.AUTO) {
            return configured;
        }
        final var detected = ChatRoutes.detectFlavor(provider.baseUrl());
        return detected == null ? ApiFlavor.COMPLETIONS : detected;
    }

    /** The resolved output budget for an explicit provider + model, like {@link #outputTokens()} but unbound to the active model. */
    private int outputTokens(final ProviderConfiguration provider, final String model) {
        if (provider == null) {
            return OutputBudgetResolver.DEFAULT_MAX_TOKEN;
        }
        final var detectedMax = contextWindowCache == null
                ? null
                : contextWindowCache.getMaxTokens(provider.id(), model).orElse(null);
        final Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        final var resolvedWindow = contextWindow != null
                ? contextWindow
                : (contextWindowCache == null
                        ? null
                        : contextWindowCache.get(provider.id(), model).orElse(null));
        return OutputBudgetResolver.resolve(
                null,
                providerManager.outputTokensOverride(provider, model),
                provider.outputTokens(),
                detectedMax,
                resolvedWindow);
    }

    /**
     * Resolve the effective {@code reasoning_effort} with put-if-absent: an explicit request value wins,
     * then a value already merged in the extra payload is left untouched, then the selected variant default,
     * then the per-model/provider tuning default, then the implicit per-family default. When the payload
     * already carries one, null keeps the dedicated field unset so the provider option flows to the wire
     * through the extras.
     */
    private static String resolveReasoningEffort(
            final String requestEffort,
            final Map<String, Object> merged,
            final String model,
            final String variantEffort,
            final String configEffort,
            final String providerId) {
        if (requestEffort != null && !requestEffort.isBlank()) {
            return requestEffort;
        }
        if (merged != null && merged.containsKey("reasoning_effort")) {
            return null;
        }
        if (variantEffort != null && !variantEffort.isBlank()) {
            return variantEffort;
        }
        if (configEffort != null && !configEffort.isBlank()) {
            return configEffort;
        }
        return DefaultModelTuner.reasoningEffort(model, providerId);
    }

    /** Anthropic claude models map a reasoning effort hint to an explicit thinking budget. */
    private static boolean isClaude(final String model) {
        return model != null && model.startsWith("claude");
    }

    private static void applyClaudeThinking(final Map<String, Object> others, final String effort) {
        final int budget =
                switch (effort) {
                    case "low" -> 1024;
                    case "high", "xhigh", "max" -> 8192;
                    default -> 2048;
                };
        others.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
    }

    /**
     * Parse a raw JSON object string into a map, empty when not parseable as an object.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRaw(final String json) {
        if (json == null || json.isBlank() || !json.startsWith("{")) {
            return Map.of();
        }
        final var parsed = mapper.fromString(Object.class, json);
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    }

    /** Deep-merge {@code overrides} into {@code target}: nested maps are merged recursively, leaves are put-if-absent. */
    @SuppressWarnings("unchecked")
    private static void deepMergePutIfAbsent(final Map<String, Object> target, final Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        for (final var entry : overrides.entrySet()) {
            final var existing = target.get(entry.getKey());
            if (existing instanceof Map && entry.getValue() instanceof Map) {
                deepMergePutIfAbsent((Map<String, Object>) existing, (Map<String, Object>) entry.getValue());
            } else if (!target.containsKey(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Promote the well-known generic options carried in the request extras ({@code temperature} and
     * {@code max_tokens}/{@code max_output_tokens}, eg. from {@code providerSpecificOptions}) into the
     * typed request fields so every dialect honours them - notably the output-budget resolution which
     * only reads the typed field - and strip them from the extras so the wire payload never carries
     * duplicate keys. A typed field wins when both are set.
     */
    private static ChatCompletionRequest withPromotedExtras(final ChatCompletionRequest request) {
        final var extras = request.others();
        if (extras == null
                || extras.isEmpty()
                || (!extras.containsKey("temperature")
                        && !extras.containsKey("max_tokens")
                        && !extras.containsKey("max_output_tokens")
                        && !extras.containsKey("top_p"))) {
            return request;
        }
        Double temperature = request.temperature();
        if (temperature == null && extras.get("temperature") instanceof Number n) {
            temperature = n.doubleValue();
        }
        Integer maxTokens = request.maxTokens();
        if (maxTokens == null) {
            maxTokens = extrasNumber(extras, "max_tokens", "max_output_tokens");
        }
        Double topP = request.topP();
        if (topP == null && extras.get("top_p") instanceof Number n) {
            topP = n.doubleValue();
        }
        final var stripped = new LinkedHashMap<String, Object>(extras.size());
        extras.forEach((k, v) -> {
            if (!"temperature".equals(k)
                    && !"max_tokens".equals(k)
                    && !"max_output_tokens".equals(k)
                    && !"top_p".equals(k)) {
                stripped.put(k, v);
            }
        });
        return new ChatCompletionRequest(
                request.model(),
                request.messages(),
                temperature,
                topP,
                request.stream(),
                request.tools(),
                request.toolChoice(),
                maxTokens,
                request.reasoningEffort(),
                stripped,
                request.settings());
    }

    private static Integer extrasNumber(final Map<String, Object> extras, final String... keys) {
        for (final var key : keys) {
            if (extras.get(key) instanceof Number n) {
                final var value = n.intValue();
                return value > 0 ? value : null;
            }
        }
        return null;
    }

    /**
     * Resolve the effective output budget and, when it differs from the request, return a copy carrying
     * it. Anthropic always requires a {@code max_tokens}, resolved from the request, the configuration,
     * the provider metadata or the 32000 fallback. OpenAI flavors are only given a value when one is
     * explicitly set (request, per-model override or provider default): a detected or fallback value is
     * deliberately not force-sent there so proxies governing their own server-side default keep working.
     */
    private ChatCompletionRequest applyOutputBudget(final ChatCompletionRequest request, final ApiFlavor flavor) {
        if (flavor == null || flavor == ApiFlavor.AUTO) {
            return request;
        }
        final var provider = target(request);
        final var model = request.model();
        final var perModel = providerManager.outputTokensOverride(provider, model);
        final var perProvider = provider.outputTokens();
        final var hasRequestBudget = request.maxTokens() != null && request.maxTokens() > 0;
        if (flavor != ApiFlavor.ANTHROPIC && perModel <= 0 && perProvider <= 0 && !hasRequestBudget) {
            return request;
        }
        final var providerId = providerIdOf(provider);
        Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        if (contextWindow == null) {
            contextWindow = contextWindowCache.get(providerId, model).orElse(null);
        }
        final var detectedMaxTokens = flavor == ApiFlavor.ANTHROPIC
                ? contextWindowCache.getMaxTokens(providerId, model).orElse(null)
                : null;
        final var decision = OutputBudgetResolver.resolveTracked(
                request.maxTokens(), perModel, perProvider, detectedMaxTokens, contextWindow);
        logBudgetOnce(flavor, providerId, model, decision);
        final var maxTokens = request.maxTokens();
        if (maxTokens != null && decision.budget() == maxTokens) {
            return request;
        }
        return new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                request.topP(),
                request.stream(),
                request.tools(),
                request.toolChoice(),
                decision.budget(),
                request.reasoningEffort(),
                request.others(),
                request.settings());
    }

    /**
     * One-shot INFO line per (flavor, provider, model) exposing the resolved output budget and where
     * it comes from, so a misconfigured budget can be diagnosed without tracing requests.
     */
    private void logBudgetOnce(
            final ApiFlavor flavor,
            final String providerId,
            final String model,
            final OutputBudgetResolver.Decision decision) {
        if (budgetLogged.add(flavor.name() + '|' + providerId + '|' + model)) {
            LOGGER.info(() -> "Output budget for " + providerId + '/' + model + " ["
                    + flavor.name().toLowerCase(java.util.Locale.ROOT) + "]: " + decision.budget()
                    + " tokens (source: " + decision.source() + ")");
        }
    }

    public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return chat(request, () -> false);
    }

    public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return chat(request, (Consumer<PauseEvent>) null, cancelled);
    }

    public ChatResult chat(
            final ChatCompletionRequest request,
            final Consumer<PauseEvent> pauseListener,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return doWithFallback(useModel(request), pauseListener, cancelled, false, null);
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
        final var model = resolveModel(request);
        final var flavor = effectiveFlavor(request.settings());
        final var base = new LinkedHashMap<String, Object>();
        if (request.others() != null) {
            base.putAll(request.others());
        }
        final var plainReq = new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                request.topP(),
                false,
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                base,
                request.settings());
        final var structuredReq = new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                request.topP(),
                false,
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                withStructuredField(base, flavor, schemaName, schema),
                request.settings());
        final var providerId = providerIdFor(request);
        if (structuredOutputUnsupported.contains(providerId)) {
            return new StructuredResult(chatInternal(withPromotedExtras(applyModelDefaults(plainReq))), false);
        }
        try {
            return new StructuredResult(chatInternal(withPromotedExtras(applyModelDefaults(structuredReq))), true);
        } catch (final IOException first) {
            if (!isStatus400(first)) {
                throw first;
            }
            try {
                return new StructuredResult(chatInternal(withPromotedExtras(applyModelDefaults(plainReq))), false);
            } catch (final IOException second) {
                if (!isStatus400(second)) {
                    throw second;
                }
                structuredOutputUnsupported.add(providerId);
                return new StructuredResult(chatInternal(withPromotedExtras(applyModelDefaults(plainReq))), false);
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

    /** Route a request through the current flavor, reusing the shared dispatch logic (process default settings). */
    /** Route a request through the session's flavor/provider (the request carries the selection). */
    private ChatResult chatInternal(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return doWithFallback(request, null, () -> false, false, null);
    }

    /**
     * Applies the configured tool call arguments repair to an assistant message when the effective
     * provider/model enable it. Never changes the message when nothing was repaired.
     */
    private Message repairToolCalls(
            final Message message, final ChatCompletionRequest effective, final ProviderConfiguration provider) {
        final var toolCalls = message.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return message;
        }
        final var model = effective.model();
        if (provider == null || providerManager == null || !providerManager.repairToolCallArguments(provider, model)) {
            return message;
        }
        var changed = false;
        final var repaired = new ArrayList<ToolCall>(toolCalls.size());
        for (final var call : toolCalls) {
            final var fn = call == null ? null : call.function();
            if (call == null || fn == null) {
                repaired.add(call);
                continue;
            }
            final var arguments = toolCallArgumentsRepairer.repair(fn.arguments(), true, model);
            if (arguments.equals(fn.arguments())) {
                repaired.add(call);
            } else {
                changed = true;
                repaired.add(new ToolCall(
                        call.id(), call.type(), new ToolCall.FunctionCall(fn.name(), arguments), call.index()));
            }
        }
        return changed
                ? new Message(
                        message.role(),
                        message.content(),
                        message.name(),
                        repaired,
                        message.toolCallId(),
                        message.others())
                : message;
    }

    private ChatResult repairToolCalls(
            final ChatResult result, final ChatCompletionRequest effective, final ProviderConfiguration provider) {
        final var message = repairToolCalls(result.message(), effective, provider);
        return message == result.message() ? result : new ChatResult(message, result.usage());
    }

    public Message stream(final ChatCompletionRequest request, final Consumer<LlmStreamEvent> onDelta)
            throws IOException, InterruptedException {
        return stream(request, onDelta, Cancellations.NEVER);
    }

    public Message stream(
            final ChatCompletionRequest request,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return stream(request, (Consumer<PauseEvent>) null, onDelta, cancelled);
    }

    /** Stream a completion for the request's selection ({@link ChatCompletionRequest#settings()}). */
    public Message stream(
            final ChatCompletionRequest request,
            final Consumer<PauseEvent> pauseListener,
            final Consumer<LlmStreamEvent> onDelta,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var model = resolveModel(request);
        final var effective = applyModelDefaults(new ChatCompletionRequest(
                model,
                request.messages(),
                request.temperature(),
                request.topP(),
                true,
                request.tools(),
                request.toolChoice(),
                request.maxTokens(),
                request.reasoningEffort(),
                request.others(),
                request.settings()));
        return doWithFallback(withPromotedExtras(effective), pauseListener, cancelled, true, onDelta);
    }

    @SuppressWarnings("unchecked")
    private <A> A doWithFallback(
            final ChatCompletionRequest request,
            final Consumer<PauseEvent> pauseListener,
            final BooleanSupplier cancelled,
            final boolean stream,
            final Consumer<LlmStreamEvent> onDelta)
            throws IOException, InterruptedException {
        final Consumer<LlmStreamEvent> onDeltaSafe = onDelta == null ? event -> {} : onDelta;
        final var settings = request.settings();
        final var provider = provider(settings);
        final var effective = applyOutputBudget(request, flavorForRouting(request.settings()));
        final Consumer<io.yupiik.llm.client.PauseEvent> libraryListener =
                pauseListener == null ? null : event -> onLibraryPause(event, pauseListener);
        final var dialect = dialectClient(flavorForRouting(settings), provider);
        try {
            if (stream) {
                final var publisher =
                        LlmWire.join(dialect.stream(LlmWire.toLlmRequest(effective), cancelled, libraryListener));
                return (A) repairToolCalls(LlmWire.pump(publisher, onDeltaSafe, cancelled), effective, provider);
            }
            return (A) repairToolCalls(
                    LlmWire.toChatResult(
                            LlmWire.join(dialect.chat(LlmWire.toLlmRequest(effective), cancelled, libraryListener))),
                    effective,
                    provider);
        } catch (final DialectNotSupportedException e) {
            final var providerId = providerIdOf(provider);
            if (resolvedFlavors.getOrDefault(providerId, ApiFlavor.AUTO) != ApiFlavor.AUTO) {
                throw e; // explicitly configured flavor: surface the incompatibility
            }
            // AUTO probed the responses route first: pin the fallback for this provider
            resolvedFlavors.put(providerId, ApiFlavor.COMPLETIONS);
            return doWithFallback(request, pauseListener, cancelled, stream, onDelta);
        }
    }

    /** The provider id used to key the per-provider caches; {@code "default"} when the provider is unresolved. */
    private String providerIdOf(final ProviderConfiguration provider) {
        final var id = provider == null || provider.id() == null ? "" : provider.id();
        return id.isBlank() ? "default" : id;
    }

    /** The provider id for a request's effective provider, for the per-provider caches. */
    private String providerIdFor(final ChatCompletionRequest request) {
        return providerIdOf(provider(request.settings()));
    }

    public ModelsResponse models() throws IOException, InterruptedException {
        return models(null);
    }

    /** The provider's model list; the effective provider is the session's when a settings override exists. */
    public ModelsResponse models(final ChatSettings settings) throws IOException, InterruptedException {
        final var provider = provider(settings);
        final var providerId = providerIdOf(provider);
        final var cached = cachedModelsByProvider.get(providerId);
        if (cached != null) {
            return cached;
        }
        final var flavor = flavorForRouting(settings);
        try {
            final var fetched = LlmWire.toModelsResponse(
                    LlmWire.join(dialectClient(flavor == ApiFlavor.AUTO ? ApiFlavor.COMPLETIONS : flavor, provider)
                            .models()));
            cachedModelsByProvider.put(providerId, fetched);
            return fetched;
        } catch (final DialectNotSupportedException e) {
            return new ModelsResponse(List.of());
        }
    }

    @Override
    @Destroy
    public void close() {
        if (dialectClients != null) {
            dialectClients.values().forEach(DialectLlmClient::close);
        }
        if (ownsHttpClient && httpClient != null) {
            try {
                httpClient.close();
            } catch (final RuntimeException e) {
                // ignore close failures on shutdown
            }
        }
    }

    /**
     * In-memory sampling overrides keyed by field. Semantics per field:
     * <ul>
     * <li>key absent - not overridden; normal request -&gt; variant -&gt; implicit tuner precedence applies;</li>
     * <li>{@link Setting#none()} - force-omit: the field is dropped from the wire payload and the variant and
     * implicit tuner defaults are skipped, letting the server default apply;</li>
     * <li>a concrete {@link Setting#of(double)} - the field is set to that value.</li>
     * </ul>
     */
    public record SamplingOverride(Map<SamplingField, Optional<Double>> values) {

        public static SamplingOverride of(final Map<SamplingField, Optional<Double>> values) {
            return new SamplingOverride(values == null ? Map.of() : values);
        }

        public boolean isEmpty() {
            return values == null || values.isEmpty();
        }

        /** The overridden value for the field: null if unset, empty Optional for force-omit, present for a value. */
        public Optional<Double> get(final SamplingField field) {
            if (values == null) {
                return null;
            }
            return values.get(field);
        }
    }

    /** The sampling fields a user can override in-memory. */
    public enum SamplingField {
        TEMPERATURE,
        TOP_K,
        TOP_P
    }

    /** A setting value: {@link #none()} force-omits the field, {@link #of(double)} sets it. */
    public record Setting(Double value) {

        public static Setting none() {
            return new Setting(null);
        }

        public static Setting of(final double value) {
            return new Setting(value);
        }

        public boolean isNone() {
            return value == null;
        }

        public Optional<Double> asOptional() {
            return value == null ? Optional.empty() : Optional.of(value);
        }
    }
}
