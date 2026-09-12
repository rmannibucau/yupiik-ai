package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
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

@ApplicationScoped
public class UsageTracker {
    private static final Logger LOGGER = Logger.getLogger(UsageTracker.class.getName());

    private final ProviderManager providerManager;
    private final ChatClient chatClient;
    private final ContextWindowCache contextWindowCache;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final Clock clock;
    private final AtomicLong contextWindowResolutions = new AtomicLong();
    private final AtomicLong autoContextWindowResolutions = new AtomicLong();

    public UsageTracker(final ProviderManager providerManager, final ChatClient chatClient,
                        final ContextWindowCache contextWindowCache, final Clock clock) {
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
        requests.incrementAndGet();
    }

    public long promptTokens() {
        return promptTokens.get();
    }

    public long completionTokens() {
        return completionTokens.get();
    }

    public long requests() {
        return requests.get();
    }

    public void reset() {
        promptTokens.set(0);
        completionTokens.set(0);
        requests.set(0);
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
                    total += TokenEstimator.estimate(tc.function() == null ? null : tc.function().name());
                    total += TokenEstimator.estimate(tc.function() == null ? null : tc.function().arguments());
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
        return autoContextWindowResolutions.get() > 0 && autoContextWindowResolutions.get() >= contextWindowResolutions.get();
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

    public double contextPercent(final int estimatedTokens) {
        final var window = Math.max(1, contextWindow());
        return 100.0 * estimatedTokens / window;
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
                LOGGER.log(Level.WARNING, e, () -> "Could not persist reduced context window for " + providerId + "/" + model);
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
            LOGGER.log(Level.WARNING, e,
                    () -> "Context window auto-detection failed for " + providerId + "/" + model
                            + "; falling back to conservative default " + ModelsResponse.AUTO_DETECTED_BASELINE);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
