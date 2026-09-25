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

import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UsageTracker {
    private static final Logger LOGGER = Logger.getLogger(UsageTracker.class.getName());

    /**
     * Output headroom reserved when converting the provider context window into a usable input
     * budget: the completion the model is about to produce also lives in the window, so triggers
     * computed on the usable budget fire before the request actually overflows.
     */
    private static final int OUTPUT_RESERVE = 20_000;

    private final ProviderManager providerManager;
    private final ChatClient chatClient;
    private final ContextWindowCache contextWindowCache;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong auxiliaryPromptTokens = new AtomicLong();
    private final AtomicLong auxiliaryCompletionTokens = new AtomicLong();
    private final AtomicLong auxiliaryRequests = new AtomicLong();
    protected final Clock clock;
    private final AtomicLong contextWindowResolutions = new AtomicLong();
    private final AtomicLong autoContextWindowResolutions = new AtomicLong();
    protected volatile Anchor anchor;

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
        cacheReadTokens.addAndGet(usage.cacheReadTokens());
        requests.incrementAndGet();
        auxiliaryPromptTokens.addAndGet(usage.promptTokens());
        auxiliaryCompletionTokens.addAndGet(usage.completionTokens());
        auxiliaryRequests.incrementAndGet();
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
     * The context window minus the reserved output headroom: the budget the input may actually
     * grow into before the request risks overflowing. Thresholds compare against this, not the
     * raw window.
     */
    public int usableWindow() {
        final var window = contextWindow();
        final var reserve = Math.min(OUTPUT_RESERVE, window / 4);
        return Math.max(1_024, window - reserve);
    }

    public long promptTokens() {
        return promptTokens.get();
    }

    public long completionTokens() {
        return completionTokens.get();
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

    public long auxiliaryRequests() {
        return auxiliaryRequests.get();
    }

    public void reset() {
        promptTokens.set(0);
        completionTokens.set(0);
        requests.set(0);
        auxiliaryPromptTokens.set(0);
        auxiliaryCompletionTokens.set(0);
        auxiliaryRequests.set(0);
        anchor = null;
    }

    public int estimateContext(final List<Message> messages) {
        return estimateContext(messages, 0);
    }

    /**
     * {@link #estimateContext(List)} plus an explicit contribution for the injected tool schema
     * (which is never part of the message list but IS part of every request). Callers building a
     * {@code ChatCompletionRequest} should pass an estimate of their wire tools to avoid the
     * thresholds firing late because tool definitions were uncounted.
     */
    public int estimateContext(final List<Message> messages, final int extraToolTokens) {
        var total = Math.max(0, extraToolTokens);
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
        return total;
    }

    /**
     * The effective context window in tokens: the provider configured value when positive, else a
     * value detected from the provider /v1/models vendor extensions and cached for a day per
     * provider and model, else {@link ModelsResponse#AUTO_DETECTED_BASELINE}. Detection failures
     * are logged at warning level. Auto-detection fallbacks are tracked so callers can surface an
     * honest once-per-session notice to the user.
     */
    public int contextWindow() {
        final var current = providerManager == null ? null : providerManager.current();
        if (current == null) {
            return rememberAuto(ModelsResponse.AUTO_DETECTED_BASELINE);
        }
        if (current.contextWindow() > 0) {
            return rememberExplicit(current.contextWindow());
        }
        if (chatClient == null || contextWindowCache == null) {
            return rememberAuto(ModelsResponse.AUTO_DETECTED_BASELINE);
        }
        final var model = current.model();
        if (model == null || model.isBlank()) {
            return rememberAuto(ModelsResponse.AUTO_DETECTED_BASELINE);
        }
        final var providerId = current.id() == null || current.id().isBlank() ? "default" : current.id();
        final var cached = contextWindowCache.get(providerId, model);
        if (cached.isPresent()) {
            return rememberExplicit(cached.get());
        }
        return detect(providerId, model).orElseGet(() -> rememberAuto(ModelsResponse.AUTO_DETECTED_BASELINE));
    }

    /**
     * Whether the last {@link #contextWindow()} resolution (if any) fell back to the auto-detected
     * baseline rather than an explicit/explicitly detected value. Useful to avoid spamming a
     * 'context window auto-detected' notice on every turn.
     */
    public boolean lastResolutionWasAuto() {
        return autoContextWindowResolutions.get() > 0
                && autoContextWindowResolutions.get() >= contextWindowResolutions.get();
    }

    private int rememberExplicit(final int window) {
        contextWindowResolutions.incrementAndGet();
        return window;
    }

    private int rememberAuto(final int window) {
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
            return contextWindow();
        }
        final var current = providerManager == null ? null : providerManager.current();
        if (current == null) {
            return contextWindow();
        }
        final var model = current.model();
        if (model == null || model.isBlank()) {
            return contextWindow();
        }
        final var providerId = current.id() == null || current.id().isBlank() ? "default" : current.id();
        final var halved = Math.max(4_096, contextWindow() / 2);
        if (lastResolutionWasAuto()) {
            try {
                contextWindowCache.put(providerId, model, halved, clock.millis());
            } catch (final RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "Could not persist reduced context window for " + providerId + "/" + model);
            }
        }
        return halved;
    }

    private Optional<Integer> detect(final String providerId, final String model) {
        try {
            final var response = chatClient.models();
            if (response == null || response.data() == null) {
                return Optional.empty();
            }
            for (final var entry : response.data()) {
                if (model.equals(entry.id())) {
                    final var window = entry.contextWindow();
                    if (window != null && window > 0) {
                        contextWindowCache.put(providerId, model, window, entry.maxTokens(), clock.millis());
                        return Optional.of(window);
                    }
                    break;
                }
            }
        } catch (final IOException | RuntimeException e) {
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
}
