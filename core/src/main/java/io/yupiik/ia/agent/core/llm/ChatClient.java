package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Facade and dispatcher over the protocol-specific {@link LlmClient} implementations
 * ({@link OpenAICompletionClient}, {@link OpenAIResponsesClient} and {@link AnthropicMessagesClient}).
 *
 * <p>Keeps the historical public API ({@link #chat}, {@link #stream}, {@link #models}, {@link #model},
 * {@link #setModel}, {@link #setProvider}, {@link #flavor}, {@link #providerId}) and owns the concerns
 * shared across every provider: model/provider resolution, provider-specific option merging, the
 * per-request output budget, the AUTO flavor detection and fallback, and the concurrency / throttling /
 * retries orchestration. The wire protocol and authentication are delegated to the selected client.</p>
 */
@ApplicationScoped
public class ChatClient implements AutoCloseable {

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final ContextWindowCache contextWindowCache;
    private final Map<ApiFlavor, LlmClient> clients;
    private final Semaphore permits;
    private final Deque<Long> requestTimes = new ArrayDeque<>();
    private volatile ApiFlavor resolved = ApiFlavor.AUTO;
    private volatile String modelOverride;
    private volatile ProviderConfiguration targetProvider;
    private volatile Consumer<PauseEvent> pauses = event -> {
    };

    public ChatClient(final CodingAgentConfiguration configuration, final JsonMapper mapper,
                      final ProviderManager providerManager, final Clock clock) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.clock = clock;
        this.contextWindowCache = new ContextWindowCache(configuration, mapper, clock);
        this.permits = new Semaphore(Math.max(1, configuration.llm().maxConcurrent()));
        this.clients = new EnumMap<>(ApiFlavor.class);
        this.clients.put(ApiFlavor.COMPLETIONS, new OpenAICompletionClient(mapper));
        this.clients.put(ApiFlavor.RESPONSES, new OpenAIResponsesClient(mapper));
        this.clients.put(ApiFlavor.ANTHROPIC, new AnthropicMessagesClient(mapper));
    }

    protected ChatClient() {
        this.configuration = null;
        this.mapper = null;
        this.providerManager = null;
        this.clock = null;
        this.contextWindowCache = null;
        this.clients = null;
        this.permits = null;
    }

    public ApiFlavor flavor() {
        return effectiveFlavor();
    }

    /**
     * The flavor used to select a client: an explicitly resolved flavor wins, then the configured
     * provider flavor, then a flavor deduced from the base URL route; {@link ApiFlavor#AUTO} when it
     * cannot be pinned (in which case the responses flavour is probed first and falls back).
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
        final var detected = LlmEndpoints.detectFlavor(current.baseUrl());
        return detected == null ? ApiFlavor.AUTO : detected;
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
    }

    /** Registers a listener notified whenever the resilience layer delays a request (throttle or retry backoff). */
    public void setPauseListener(final Consumer<PauseEvent> listener) {
        this.pauses = listener == null ? event -> {
        } : listener;
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
        final var detectedMaxTokens = contextWindowCache.getMaxTokens(providerManager.currentProviderId(), model).orElse(null);
        Integer contextWindow = provider.contextWindow() > 0 ? provider.contextWindow() : null;
        if (contextWindow == null) {
            contextWindow = contextWindowCache.get(providerManager.currentProviderId(), model).orElse(null);
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
        return new ChatCompletionRequest(model, request.messages(), request.temperature(), request.stream(),
                request.tools(), request.toolChoice(), budget, request.reasoningEffort(), request.others());
    }

    public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
        return chat(request, () -> false);
    }

    public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var effective = useModel(request);
        throttle();
        permits.acquire();
        try {
            return doWithFallback(effective, cancelled, false, null);
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
        final var effective = new ChatCompletionRequest(model, request.messages(), request.temperature(),
                true, request.tools(), request.toolChoice(), request.maxTokens(), request.reasoningEffort(), mergeOptions(request.others()));
        throttle();
        permits.acquire();
        try {
            return doWithFallback(effective, cancelled, true, onDelta);
        } finally {
            permits.release();
        }
    }

    @SuppressWarnings("unchecked")
    private <A> A doWithFallback(final ChatCompletionRequest request, final BooleanSupplier cancelled,
                                 final boolean stream, final Consumer<StreamDelta> onDelta)
            throws IOException, InterruptedException {
        @SuppressWarnings("unchecked")
        final var onDeltaSafe = (Consumer<StreamDelta>) (onDelta == null ? (Consumer<StreamDelta>) d -> {
        } : onDelta);
        final var flavor = effectiveFlavor();
        if (flavor == ApiFlavor.ANTHROPIC) {
            final var effective = applyOutputBudget(request, flavor);
            final var client = clients.get(ApiFlavor.ANTHROPIC);
            return withRetries(stream ? (IoCall<A>) () -> (A) client.stream(target(), effective, onDeltaSafe, cancelled)
                    : (IoCall<A>) () -> (A) client.chat(target(), effective, cancelled), cancelled);
        }
        if (flavor == ApiFlavor.RESPONSES) {
            final var client = clients.get(ApiFlavor.RESPONSES);
            return withRetries(stream ? (IoCall<A>) () -> (A) client.stream(target(), request, onDeltaSafe, cancelled)
                    : (IoCall<A>) () -> (A) client.chat(target(), request, cancelled), cancelled);
        }
        if (flavor == ApiFlavor.COMPLETIONS) {
            final var client = clients.get(ApiFlavor.COMPLETIONS);
            return withRetries(stream ? (IoCall<A>) () -> (A) client.stream(target(), request, onDeltaSafe, cancelled)
                    : (IoCall<A>) () -> (A) client.chat(target(), request, cancelled), cancelled);
        }
        try {
            final var responses = clients.get(ApiFlavor.RESPONSES);
            return withRetries(stream ? (IoCall<A>) () -> (A) responses.stream(target(), request, onDeltaSafe, cancelled)
                    : (IoCall<A>) () -> (A) responses.chat(target(), request, cancelled), cancelled);
        } catch (final FlavorMissingException e) {
            resolved = ApiFlavor.COMPLETIONS;
            final var completions = clients.get(ApiFlavor.COMPLETIONS);
            return withRetries(stream ? (IoCall<A>) () -> (A) completions.stream(target(), request, onDeltaSafe, cancelled)
                    : (IoCall<A>) () -> (A) completions.chat(target(), request, cancelled), cancelled);
        }
    }

    @SuppressWarnings("unchecked")
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

    public ModelsResponse models() throws IOException, InterruptedException {
        permits.acquire();
        try {
            final var flavor = effectiveFlavor();
            final var client = clients.get(flavor == ApiFlavor.RESPONSES ? ApiFlavor.RESPONSES : ApiFlavor.COMPLETIONS);
            return client.models(target());
        } finally {
            permits.release();
        }
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
        final var capped = Math.min(delay, 60_000);
        final var msg = retryAfter != null && !retryAfter.isBlank()
                ? "Rate limited, waiting ~" + (capped / 1000) + "s before retrying"
                : "LLM call failed, retrying in ~" + (capped / 1000) + "s (attempt " + (attempt + 1) + ")";
        try {
            pauses.accept(PauseEvent.retry(msg, capped));
        } catch (final RuntimeException re) {
            // listener failures must not break the request
        }
        Thread.sleep(capped);
    }

    private void throttle() throws InterruptedException {
        final var rpm = configuration.llm().rateLimitRequestsPerMinute();
        if (rpm <= 0) {
            return;
        }
        do {
            final long now = clock.millis();
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
            try {
                pauses.accept(PauseEvent.throttle("Rate limit reached, waiting ~" + (waitMs / 1000) + "s", waitMs));
            } catch (final RuntimeException re) {
                // listener failures must not break the request
            }
            Thread.sleep(waitMs);
        } while (true);
    }

    @Override
    public void close() {
        if (clients != null) {
            clients.values().forEach(LlmClient::close);
        }
    }

    @FunctionalInterface
    private interface IoCall<A> {
        A get() throws IOException, InterruptedException;
    }
}