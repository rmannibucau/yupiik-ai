package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.IOException;
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

    public UsageTracker(final ProviderManager providerManager, final ChatClient chatClient,
                        final ContextWindowCache contextWindowCache) {
        this.providerManager = providerManager;
        this.chatClient = chatClient;
        this.contextWindowCache = contextWindowCache;
    }

    protected UsageTracker() {
        this.providerManager = null;
        this.chatClient = null;
        this.contextWindowCache = null;
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
        var total = 0;
        for (final var m : messages) {
            total += 8;
            total += TokenEstimator.estimate(m.role());
            total += TokenEstimator.estimate(m.content() instanceof String s ? s : String.valueOf(m.content()));
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
     * provider and model, else {@link ModelsResponse#DEFAULT_CONTEXT_WINDOW}. Detection failures
     * are ignored (logged at finest level).
     */
    public int contextWindow() {
        final var current = providerManager == null ? null : providerManager.current();
        if (current == null) {
            return ModelsResponse.DEFAULT_CONTEXT_WINDOW;
        }
        if (current.contextWindow() > 0) {
            return current.contextWindow();
        }
        if (chatClient == null || contextWindowCache == null) {
            return ModelsResponse.DEFAULT_CONTEXT_WINDOW;
        }
        final var model = current.model();
        if (model == null || model.isBlank()) {
            return ModelsResponse.DEFAULT_CONTEXT_WINDOW;
        }
        final var providerId = current.id() == null || current.id().isBlank() ? "default" : current.id();
        final var cached = contextWindowCache.get(providerId, model);
        if (cached.isPresent()) {
            return cached.get();
        }
        return detect(providerId, model).orElse(ModelsResponse.DEFAULT_CONTEXT_WINDOW);
    }

    public double contextPercent(final int estimatedTokens) {
        final var window = Math.max(1, contextWindow());
        return 100.0 * estimatedTokens / window;
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
                        contextWindowCache.put(providerId, model, window, System.currentTimeMillis());
                        return Optional.of(window);
                    }
                    break;
                }
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.log(Level.FINEST, e,
                    () -> "Ignoring /v1/models context window detection failure for " + providerId + "/" + model);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
