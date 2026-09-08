package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class UsageTracker {
    private final ProviderManager providerManager;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();

    public UsageTracker(final ProviderManager providerManager) {
        this.providerManager = providerManager;
    }

    protected UsageTracker() {
        this.providerManager = null;
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

    public int contextWindow() {
        final var current = providerManager.current();
        return current == null ? 128000 : current.contextWindow();
    }

    public double contextPercent(final int estimatedTokens) {
        final var window = Math.max(1, contextWindow());
        return 100.0 * estimatedTokens / window;
    }
}
