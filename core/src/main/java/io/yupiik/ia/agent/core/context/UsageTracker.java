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
package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UsageTracker {
    private static final Logger LOGGER = Logger.getLogger(UsageTracker.class.getName());

    private final ProviderManager providerManager;
    private final ChatClient chatClient;
    private final ContextWindowCache contextWindowCache;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong reasoningTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong auxiliaryPromptTokens = new AtomicLong();
    private final AtomicLong auxiliaryCompletionTokens = new AtomicLong();
    private final AtomicLong auxiliaryCacheReadTokens = new AtomicLong();
    private final AtomicLong auxiliaryRequests = new AtomicLong();
    // rtk-style output-optimization counters: how many good tool outputs were trimmed and by how much.
    private final AtomicLong optimizedOutputs = new AtomicLong();
    private final AtomicLong optimizedCharsBefore = new AtomicLong();
    private final AtomicLong optimizedCharsAfter = new AtomicLong();
    protected final Clock clock;
    private final AtomicLong contextWindowResolutions = new AtomicLong();
    private final AtomicLong autoContextWindowResolutions = new AtomicLong();
    /**
     * Retention window for a failed auto-detection of the context window, matching
     * {@link ContextWindowCache}'s one-day dedup dimension so a flaky/unreachable {@code /v1/models}
     * does not re-fail (and re-warn) on every resolution but at most once per day.
     */
    private static final long DETECTION_FAILURE_TTL_MILLIS = 24L * 60L * 60L * 1000L;
    /** Last time auto-detection of the context window failed for a (provider id, model) key. */
    private final Map<Key, Long> detectionFailedAt = new ConcurrentHashMap<>();

    /** Stable identity for the (provider id, model) dimension over which auto-detection is coalesced. */
    private record Key(String providerId, String model) {}

    protected volatile Anchor anchor;

    /** The session's per-tab selection (provider/model): when set, context window resolution follows it. */
    protected volatile ChatSettings settings;

    /** Attach the owning session's model/provider selection so this tracker resolves the right window. */
    public void attach(final ChatSettings sessionSettings) {
        this.settings = sessionSettings;
    }

    /**
     * Last real API usage observation: the prompt tokens billed by the provider for the exact
     * message list that was sent, plus the estimate that was computed for that same list. The
     * ratio between both calibrates later estimates so they stop being over-aggressive against
     * the ground truth.
     */
    record Anchor(int realPromptTokens, int estimateAtAnchor) {}

    public UsageTracker(
            final ProviderManager providerManager,
            final ChatClient chatClient,
            final ContextWindowCache contextWindowCache,
            final Clock clock) {
        this.providerManager = providerManager;
        this.chatClient = chatClient;
        this.contextWindowCache = contextWindowCache;
        this.clock = clock;
    }

    protected UsageTracker() {
        this.providerManager = null;
        this.chatClient = null;
        this.contextWindowCache = null;
        this.clock = null;
    }

    public void record(final Usage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.promptTokens());
        completionTokens.addAndGet(usage.completionTokens());
        reasoningTokens.addAndGet(usage.reasoningTokens());
        cacheReadTokens.addAndGet(usage.cacheReadTokens());
        requests.incrementAndGet();
    }

    /**
     * Records spend of background LLM calls (context compaction summarizer, memory extraction)
     * which never appear as conversation turns: the tokens are added to the session totals so
     * the usage line stays honest about real cost, and mirrored into dedicated auxiliary
     * counters so the UI can break background spend out.
     */
    public void recordAuxiliary(final Usage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.promptTokens());
        completionTokens.addAndGet(usage.completionTokens());
        reasoningTokens.addAndGet(usage.reasoningTokens());
        cacheReadTokens.addAndGet(usage.cacheReadTokens());
        requests.incrementAndGet();
        auxiliaryPromptTokens.addAndGet(usage.promptTokens());
        auxiliaryCompletionTokens.addAndGet(usage.completionTokens());
        auxiliaryCacheReadTokens.addAndGet(usage.cacheReadTokens());
        auxiliaryRequests.incrementAndGet();
    }

    /**
     * Records one accepted tool-output optimization (rtk style): a meaningful character reduction
     * applied to a bash/tool result before it reaches the model. Only called for reductions that
     * actually replaced the original output ({@code after} is smaller than {@code before}). Later
     * the chars can be priced as estimated tokens (~4 chars/token) for the session saved-token line.
     *
     * @param beforeChars the original tool output length in characters.
     * @param afterChars  the optimized output length in characters.
     */
    public void recordOptimization(final long beforeChars, final long afterChars) {
        optimizedOutputs.incrementAndGet();
        optimizedCharsBefore.addAndGet(beforeChars);
        optimizedCharsAfter.addAndGet(afterChars);
    }

    /** Number of tool outputs that were actually optimized (accepted reductions). */
    public long optimizedOutputs() {
        return optimizedOutputs.get();
    }

    /** Total characters of the original tool outputs that were optimized. */
    public long optimizedCharsBefore() {
        return optimizedCharsBefore.get();
    }

    /** Total characters of the optimized tool outputs. */
    public long optimizedCharsAfter() {
        return optimizedCharsAfter.get();
    }

    /** Total characters saved by output optimization (before minus after). */
    public long optimizedCharsSaved() {
        return Math.max(0, optimizedCharsBefore.get() - optimizedCharsAfter.get());
    }

    /**
     * Merges aggregated optimization counters as-is (used by {@code SubagentUsageTracker} to fold its
     * pre-aggregated totals into the parent without recounting each output as a single one).
     *
     * @param outputs the number of optimized outputs already counted by the caller.
     * @param before  the caller's total optimized-input characters.
     * @param after   the caller's total optimized-output characters.
     */
    public void mergeOptimizationCounters(final long outputs, final long before, final long after) {
        if (outputs <= 0) {
            return;
        }
        optimizedOutputs.addAndGet(outputs);
        optimizedCharsBefore.addAndGet(before);
        optimizedCharsAfter.addAndGet(after);
    }

    /**
     * Anchors the hybrid usage computation on a real provider observation: {@code realPromptTokens}
     * is what the provider billed for the request built from the message list whose estimate is
     * {@code estimateAtAnchor}. Called after each successful LLM call, before the reply is appended.
     */
    public void anchor(final int realPromptTokens, final int estimateAtAnchor) {
        this.anchor =
                realPromptTokens > 0 && estimateAtAnchor > 0 ? new Anchor(realPromptTokens, estimateAtAnchor) : null;
    }

    /** Drops the current anchor: usage falls back to the raw estimate until the next LLM call. */
    public void clearAnchor() {
        this.anchor = null;
    }

    /**
     * The provider-billed prompt tokens of the last anchored request, the real base the hybrid
     * estimate is built on; {@code -1} when no anchor exists yet (raw heuristic estimate).
     */
    public int lastRealPromptTokens() {
        return anchor == null ? -1 : anchor.realPromptTokens();
    }

    /**
     * The clamped calibration ratio (real/estimate at anchor time, in {@code [0.5, 2.0]}) applied to
     * the estimated tail, or {@code -1} when no anchor exists yet.
     */
    public double calibrationRatio() {
        return anchor == null
                ? -1
                : Math.clamp((double) anchor.realPromptTokens() / anchor.estimateAtAnchor(), 0.5, 2.0);
    }
    /**
     * Best known context usage in tokens: the last real prompt token count when available, plus a
     * calibrated estimate of what was appended since; a raw estimate otherwise (no anchor yet, or
     * the context shrank after a compaction, which invalidates the anchor). The calibration ratio
     * (real/estimate at anchor time) is clamped to [0.5, 2.0] so a single outlier never swings the
     * trigger wildly in either direction.
     */
    public int effectiveContext(final List<Message> messages, final int extraToolTokens) {
        final var current = anchor;
        final var estimate = estimateContext(messages, extraToolTokens);
        if (current == null || estimate <= current.estimateAtAnchor()) {
            return estimate;
        }
        final var ratio = Math.clamp((double) current.realPromptTokens() / current.estimateAtAnchor(), 0.5, 2.0);
        return (int) Math.round(current.realPromptTokens() + ratio * (estimate - current.estimateAtAnchor()));
    }

    /**
     * {@link #estimateContext(List, int)} plus an explicit contribution for the injected tool schema
     * (which is never part of the message list but IS part of every request). Callers building a
     * {@code ChatCompletionRequest} should pass an estimate of their wire tools to avoid the
     * thresholds firing late because tool definitions were uncounted.
     */
    public int estimateContext(final List<Message> messages) {
        return estimateContext(messages, 0);
    }

    /**
     * Heuristic token estimation for a message list using TokenEstimator, plus an explicit contribution
     * for the injected tool schema (which is never part of the message list but IS part of every request).
     * Callers building a {@code ChatCompletionRequest} should pass an estimate of their wire tools to avoid
     * the thresholds firing late because tool definitions were uncounted.
     */
    public int estimateContext(final List<Message> messages, final int extraToolTokens) {
        var total = Math.max(0, extraToolTokens);
        if (messages != null) {
            for (final var m : messages) {
                total += 8;
                total += TokenEstimator.estimate(m.role());
                total += TokenEstimator.estimate(m.content() instanceof String s ? s : String.valueOf(m.content()));
                total += TokenEstimator.estimate(m.name());
                total += TokenEstimator.estimate(m.toolCallId());
                if (m.toolCalls() != null) {
                    for (final var tc : m.toolCalls()) {
                        total += TokenEstimator.estimate(
                                tc.function() == null ? null : tc.function().name());
                        total += TokenEstimator.estimate(
                                tc.function() == null ? null : tc.function().arguments());
                    }
                }
            }
        }
        return total;
    }

    /**
     * The effective context window as a TokenCount (value + absolute flag + source).
     * Falls back to AUTO_DETECTED_BASELINE with absolute=false when nothing is configured/detected.
     */
    public ModelsResponse.TokenCount contextWindowTokenCount() {
        final var current = currentProvider();
        if (current == null) {
            return rememberAuto(new ModelsResponse.TokenCount(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    false,
                    ModelsResponse.SourceType.AUTO_BASELINE,
                    "auto-baseline"));
        }
        // per-model override wins over the provider value: resolve it first when a concrete model is known
        if (chatClient != null && providerManager != null) {
            final var model = chatClient.model(settings);
            if (model != null && !model.isBlank()) {
                final var override = providerManager.contextWindowOverride(current, model);
                if (override > 0) {
                    return rememberExplicit(new ModelsResponse.TokenCount(
                            override, true, ModelsResponse.SourceType.CONFIGURED, "model-config"));
                }
            }
        }
        if (current.contextWindow() > 0) {
            return rememberExplicit(new ModelsResponse.TokenCount(
                    current.contextWindow(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config"));
        }
        if (chatClient == null || contextWindowCache == null) {
            return rememberAuto(new ModelsResponse.TokenCount(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    false,
                    ModelsResponse.SourceType.AUTO_BASELINE,
                    "auto-baseline"));
        }
        final var model = chatClient.model(settings);
        if (model == null || model.isBlank()) {
            return rememberAuto(new ModelsResponse.TokenCount(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    false,
                    ModelsResponse.SourceType.AUTO_BASELINE,
                    "auto-baseline"));
        }
        final var providerId = current.id() == null || current.id().isBlank() ? "default" : current.id();
        final var cached = contextWindowCache.get(providerId, model);
        if (cached.isPresent()) {
            return rememberExplicit(cached.get());
        }
        if (failedAutoDetectionRecently(providerId, model)) {
            return rememberAuto(new ModelsResponse.TokenCount(
                    ModelsResponse.AUTO_DETECTED_BASELINE,
                    false,
                    ModelsResponse.SourceType.AUTO_BASELINE,
                    "auto-baseline"));
        }
        return detectTokenCount(providerId, model)
                .orElseGet(() -> rememberAuto(new ModelsResponse.TokenCount(
                        ModelsResponse.AUTO_DETECTED_BASELINE,
                        false,
                        ModelsResponse.SourceType.AUTO_BASELINE,
                        "auto-baseline")));
    }

    /**
     * The output budget the current model declares, as a TokenCount (value + absolute flag + source),
     * or null when unknown.
     */
    public ModelsResponse.TokenCount declaredOutputTokensTokenCount() {
        final var provider = currentProvider();
        if (provider == null) {
            return null;
        }
        final var model = currentModel(provider);
        final var override = providerManager == null ? 0 : providerManager.outputTokensOverride(provider, model);
        if (override > 0) {
            return new ModelsResponse.TokenCount(override, true, ModelsResponse.SourceType.CONFIGURED, "model-config");
        }
        if (provider.outputTokens() > 0) {
            return new ModelsResponse.TokenCount(
                    provider.outputTokens(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config");
        }
        if (contextWindowCache == null || model == null || model.isBlank()) {
            return null;
        }
        return contextWindowCache.getMaxTokens(providerId(provider), model).orElse(null);
    }

    /** The context window minus the completion headroom: the budget the input may actually grow into
     * before the request risks overflowing. Thresholds compare against this, not the raw window. The
     * headroom is the output cap the model declares (per-model, per-provider or detected from
     * {@code /v1/models}, see {@link #declaredOutputTokensTokenCount()}) so a window of 262144 declaring 32768
     * completion tokens leaves 229376 for the input; without a declared cap the historical
     * {@code min(20000, window / 4)} heuristic applies (see {@link ContextBudget}). */
    public int usableWindow() {
        final var window = contextWindowTokenCount();
        final var output = declaredOutputTokensTokenCount();
        return ContextBudget.usableWindow(window, output);
    }

    /**
     * Resolve a routable background model reference to its (provider, model) pair, or null when the
     * reference is blank/null (no dedicated background model -> the session's conversation model)
     * or names an unknown provider/model.
     */
    private ResolvedRef resolveModelRef(final String modelReference) {
        if (providerManager == null || modelReference == null || modelReference.isBlank()) {
            return null;
        }
        final var resolved = providerManager.resolve(modelReference);
        if (resolved.provider() == null) {
            return null;
        }
        final var model = resolved.model();
        return model == null || model.isBlank() ? null : new ResolvedRef(resolved.provider(), model);
    }

    private record ResolvedRef(ProviderConfiguration provider, String model) {}

    /**
     * The usable window of an explicit background model reference (e.g. the compaction summarizer),
     * mirroring {@link #usableWindow()} but keyed to the model the reference routes to rather than
     * the session's conversation model. So a background summarizer smaller than the conversation
     * model sizes its input chunks against its own window instead of the conversation model's.
     *
     * <p>When the reference is blank/null there is no dedicated background model (the summarizer runs
     * on the conversation model), so the session's usual usable window applies. When the reference
     * cannot be resolved or no window is established, the conservative {@link ContextBudget#MIN_USABLE_WINDOW}
     * is returned so the caller never over-sizes against a phantom large window; the compaction halving
     * retry stays the safety net for an underestimated window.
     */
    public int usableWindowForModel(final String modelReference) {
        // a blank/null reference means no dedicated background model: the summarizer runs on the
        // conversation model, so its window is the session's own
        if (modelReference == null || modelReference.isBlank()) {
            return usableWindow();
        }
        final var ref = resolveModelRef(modelReference);
        if (ref == null) {
            return ContextBudget.MIN_USABLE_WINDOW;
        }
        final var window = resolveContextWindow(ref.provider(), ref.model());
        if (window == null) {
            return ContextBudget.MIN_USABLE_WINDOW;
        }
        final var output = resolveDeclaredOutputTokens(ref.provider(), ref.model());
        return ContextBudget.usableWindow(window, output);
    }

    /**
     * The context window of an explicit (provider, model) pair, sharing the precedence chain of
     * {@link #contextWindowTokenCount()} (per-model override, provider config, cached /v1/models,
     * fresh detection) but without counting the resolution toward the session's auto/explicit gauge.
     * {@code null} when no source establishes a window.
     */
    private ModelsResponse.TokenCount resolveContextWindow(final ProviderConfiguration provider, final String model) {
        if (providerManager != null && model != null && !model.isBlank()) {
            final var override = providerManager.contextWindowOverride(provider, model);
            if (override > 0) {
                return new ModelsResponse.TokenCount(
                        override, true, ModelsResponse.SourceType.CONFIGURED, "model-config");
            }
        }
        if (provider.contextWindow() > 0) {
            return new ModelsResponse.TokenCount(
                    provider.contextWindow(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config");
        }
        if (chatClient == null || contextWindowCache == null || model == null || model.isBlank()) {
            return null;
        }
        final var providerId = providerId(provider);
        final var cached = contextWindowCache.get(providerId, model);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (failedAutoDetectionRecently(providerId, model)) {
            return null;
        }
        return detectTokenCount(providerId, model).orElse(null);
    }

    /**
     * The declared output budget of an explicit (provider, model) pair, mirroring
     * {@link #declaredOutputTokensTokenCount()}; {@code null} when unknown.
     */
    private ModelsResponse.TokenCount resolveDeclaredOutputTokens(
            final ProviderConfiguration provider, final String model) {
        final var override = providerManager == null ? 0 : providerManager.outputTokensOverride(provider, model);
        if (override > 0) {
            return new ModelsResponse.TokenCount(override, true, ModelsResponse.SourceType.CONFIGURED, "model-config");
        }
        if (provider.outputTokens() > 0) {
            return new ModelsResponse.TokenCount(
                    provider.outputTokens(), true, ModelsResponse.SourceType.CONFIGURED, "provider-config");
        }
        if (contextWindowCache == null || model == null || model.isBlank()) {
            return null;
        }
        return contextWindowCache.getMaxTokens(providerId(provider), model).orElse(null);
    }

    public long promptTokens() {
        return promptTokens.get();
    }

    public long completionTokens() {
        return completionTokens.get();
    }

    public long reasoningTokens() {
        return reasoningTokens.get();
    }

    public long cacheReadTokens() {
        return cacheReadTokens.get();
    }

    public long requests() {
        return requests.get();
    }

    public long auxiliaryPromptTokens() {
        return auxiliaryPromptTokens.get();
    }

    public long auxiliaryCompletionTokens() {
        return auxiliaryCompletionTokens.get();
    }

    public long auxiliaryCacheReadTokens() {
        return auxiliaryCacheReadTokens.get();
    }

    public long auxiliaryRequests() {
        return auxiliaryRequests.get();
    }

    public long auxiliaryFreshTokens() {
        return Math.max(0, auxiliaryPromptTokens.get() - auxiliaryCacheReadTokens.get());
    }

    /**
     * Whether the last {@link #contextWindowTokenCount()} resolution (if any) fell back to the auto-detected
     * baseline rather than an explicit/explicitly detected value. Useful to avoid spamming a
     * 'context window auto-detected' notice on every turn.
     */
    public boolean lastResolutionWasAuto() {
        return autoContextWindowResolutions.get() > 0
                && autoContextWindowResolutions.get() >= contextWindowResolutions.get();
    }

    /**
     * Where the last {@link #contextWindowTokenCount()} resolution came from: {@code "explicit"} for a
     * provider-configured or vendor-detected/cached value, {@code "auto"} for the
     * {@link ModelsResponse#AUTO_DETECTED_BASELINE} fallback. Ordered after
     * {@link #lastResolutionWasAuto()} so a pending detection first call still reports the
     * resolution it would pick.
     */
    public String windowOrigin() {
        return lastResolutionWasAuto() ? "auto" : "explicit";
    }

    private ModelsResponse.TokenCount rememberExplicit(final ModelsResponse.TokenCount window) {
        contextWindowResolutions.incrementAndGet();
        return window;
    }

    private ModelsResponse.TokenCount rememberAuto(final ModelsResponse.TokenCount window) {
        contextWindowResolutions.incrementAndGet();
        autoContextWindowResolutions.incrementAndGet();
        return window;
    }

    /**
     * Usage percentage against the {@link #usableWindow()} (context window minus the reserved
     * output headroom), so display and triggers agree on the same denominator.
     */
    public double contextPercent(final int estimatedTokens) {
        return 100.0 * estimatedTokens / usableWindow();
    }

    /**
     * Learns from a {@code ContextWindowExceededException}: the assumed window was too high, so
     * halve it and return the corrected value so the overflow-compaction retry runs against a
     * window the provider can actually satisfy. When the current resolution came from the auto
     * baseline (no explicit provider/config/vendor figure), the corrected value is also persisted so
     * later turns benefit; for explicitly configured/detected windows the felt value is only used
     * for this overflow retry, avoiding a long-lived half-window for a provider that transiently
     * overflowed. No-op when the cache/client is unavailable.
     *
     * @return the new (halved) window, or the current window if it could not be adjusted.
     */
    public int rememberShrinkOnOverflow() {
        if (chatClient == null || contextWindowCache == null) {
            return contextWindowTokenCount().value();
        }
        final var current = providerManager == null ? null : providerManager.current();
        if (current == null) {
            return contextWindowTokenCount().value();
        }
        final var model = current.model();
        if (model == null || model.isBlank()) {
            return contextWindowTokenCount().value();
        }
        final var providerId = current.id() == null || current.id().isBlank() ? "default" : current.id();
        final var currentWindow = contextWindowTokenCount();
        final var halvedValue = Math.max(4_096, currentWindow.value() / 2);
        final var halved = new ModelsResponse.TokenCount(
                halvedValue,
                false,
                ModelsResponse.SourceType.OVERFLOW_ADJUSTED,
                currentWindow.sourcePointer() + "-halved");
        if (!currentWindow.absolute()) {
            try {
                contextWindowCache.put(providerId, model, halved, clock.millis());
            } catch (final RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "Could not persist reduced context window for " + providerId + "/" + model);
            }
        }
        return halvedValue;
    }

    private Optional<ModelsResponse.TokenCount> detectTokenCount(final String providerId, final String model) {
        try {
            final var response = chatClient.models();
            if (response == null || response.data() == null) {
                return Optional.empty();
            }
            for (final var entry : response.data()) {
                if (model.equals(entry.id())) {
                    final var window = entry.contextWindow();
                    final var maxTokens = entry.maxTokens();
                    if (window != null && window.value() > 0) {
                        contextWindowCache.put(providerId, model, window, maxTokens, clock.millis());
                        return Optional.of(window);
                    }
                    break;
                }
            }
        } catch (final IOException | RuntimeException e) {
            detectionFailedAt.put(new Key(providerId, model), clock.millis());
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "Context window auto-detection failed for " + providerId + "/" + model
                            + "; falling back to conservative default " + ModelsResponse.AUTO_DETECTED_BASELINE);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /** Whether auto-detection already failed for the key within the retention window, so we stop re-attempting. */
    private boolean failedAutoDetectionRecently(final String providerId, final String model) {
        if (clock == null) {
            return false;
        }
        final var failedAt = detectionFailedAt.get(new Key(providerId, model));
        return failedAt != null && clock.millis() - failedAt < DETECTION_FAILURE_TTL_MILLIS;
    }

    /** The provider targeted by the session selection, else the process current provider; {@code null} when unknown. */
    private ProviderConfiguration currentProvider() {
        if (providerManager == null) {
            return null;
        }
        if (settings == null || settings.providerId() == null) {
            return providerManager.current();
        }
        return providerManager.provider(settings.providerId());
    }

    /** The effective model name of the session, falling back to the provider default without a client. */
    private String currentModel(final ProviderConfiguration provider) {
        if (chatClient != null) {
            final var model = chatClient.model(settings);
            if (model != null && !model.isBlank()) {
                return model;
            }
        }
        return provider == null ? null : provider.model();
    }

    private static String providerId(final ProviderConfiguration provider) {
        return provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
    }
}
