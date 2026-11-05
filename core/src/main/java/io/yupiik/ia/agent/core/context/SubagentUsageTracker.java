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

import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-subagent usage tracker that delegates to parent for context window info
 * but tracks its own token counts and anchor. Merges to parent on completion.
 */
public class SubagentUsageTracker extends UsageTracker {
    private final UsageTracker parent;
    private final String subagentId;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong reasoningTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong optimizedOutputs = new AtomicLong();
    private final AtomicLong optimizedCharsBefore = new AtomicLong();
    private final AtomicLong optimizedCharsAfter = new AtomicLong();
    private volatile Anchor anchor;

    public SubagentUsageTracker(final UsageTracker parent, final String subagentId) {
        super(null, null, null, parent.clock);
        this.parent = parent;
        this.subagentId = subagentId;
        // Copy parent's anchor for calibration
        this.anchor = parent.anchor;
    }

    @Override
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

    @Override
    public void recordAuxiliary(final Usage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.promptTokens());
        completionTokens.addAndGet(usage.completionTokens());
        reasoningTokens.addAndGet(usage.reasoningTokens());
        cacheReadTokens.addAndGet(usage.cacheReadTokens());
        requests.incrementAndGet();
    }

    @Override
    public void recordOptimization(final long beforeChars, final long afterChars) {
        optimizedOutputs.incrementAndGet();
        optimizedCharsBefore.addAndGet(beforeChars);
        optimizedCharsAfter.addAndGet(afterChars);
    }

    @Override
    public void anchor(final int realPromptTokens, final int estimateAtAnchor) {
        this.anchor =
                realPromptTokens > 0 && estimateAtAnchor > 0 ? new Anchor(realPromptTokens, estimateAtAnchor) : null;
    }

    @Override
    public void clearAnchor() {
        this.anchor = null;
    }

    @Override
    public int lastRealPromptTokens() {
        return anchor == null ? -1 : anchor.realPromptTokens();
    }

    @Override
    public double calibrationRatio() {
        return anchor == null
                ? -1
                : Math.clamp((double) anchor.realPromptTokens() / anchor.estimateAtAnchor(), 0.5, 2.0);
    }

    @Override
    public int effectiveContext(final List<Message> messages, final int extraToolTokens) {
        final var current = anchor;
        final var estimate = estimateContext(messages, extraToolTokens);
        if (current == null || estimate <= current.estimateAtAnchor()) {
            return estimate;
        }
        final var ratio = Math.clamp((double) current.realPromptTokens() / current.estimateAtAnchor(), 0.5, 2.0);
        return (int) Math.round(current.realPromptTokens() + ratio * (estimate - current.estimateAtAnchor()));
    }

    @Override
    public int usableWindow() {
        return parent.usableWindow();
    }

    @Override
    public long promptTokens() {
        return promptTokens.get();
    }

    @Override
    public long completionTokens() {
        return completionTokens.get();
    }

    @Override
    public long reasoningTokens() {
        return reasoningTokens.get();
    }

    @Override
    public long cacheReadTokens() {
        return cacheReadTokens.get();
    }

    @Override
    public long requests() {
        return requests.get();
    }

    @Override
    public long auxiliaryPromptTokens() {
        return 0; // Subagent doesn't track auxiliary separately
    }

    @Override
    public long auxiliaryCompletionTokens() {
        return 0;
    }

    @Override
    public long auxiliaryCacheReadTokens() {
        return 0;
    }

    @Override
    public long auxiliaryRequests() {
        return 0;
    }

    @Override
    public long optimizedOutputs() {
        return optimizedOutputs.get();
    }

    @Override
    public long optimizedCharsBefore() {
        return optimizedCharsBefore.get();
    }

    @Override
    public long optimizedCharsAfter() {
        return optimizedCharsAfter.get();
    }

    @Override
    public long optimizedCharsSaved() {
        return Math.max(0, optimizedCharsBefore.get() - optimizedCharsAfter.get());
    }

    public void reset() {
        promptTokens.set(0);
        completionTokens.set(0);
        reasoningTokens.set(0);
        cacheReadTokens.set(0);
        requests.set(0);
        optimizedOutputs.set(0);
        optimizedCharsBefore.set(0);
        optimizedCharsAfter.set(0);
        anchor = null;
    }

    @Override
    public int estimateContext(final List<Message> messages, final int extraToolTokens) {
        return parent.estimateContext(messages, extraToolTokens);
    }

    @Override
    public ModelsResponse.TokenCount contextWindowTokenCount() {
        return parent.contextWindowTokenCount();
    }

    @Override
    public String windowOrigin() {
        return parent.windowOrigin();
    }

    @Override
    public boolean lastResolutionWasAuto() {
        return parent.lastResolutionWasAuto();
    }

    @Override
    public int rememberShrinkOnOverflow() {
        return parent.rememberShrinkOnOverflow();
    }

    @Override
    public double contextPercent(final int estimatedTokens) {
        return parent.contextPercent(estimatedTokens);
    }

    @Override
    public ModelsResponse.TokenCount declaredOutputTokensTokenCount() {
        return parent.declaredOutputTokensTokenCount();
    }

    /**
     * Merges this subagent's usage into parent's auxiliary counters.
     */
    public void mergeToParent() {
        if (parent != null) {
            parent.recordAuxiliary(new Usage(
                    (int) promptTokens.get(),
                    (int) completionTokens.get(),
                    (int) (promptTokens.get() + completionTokens.get()),
                    (int) cacheReadTokens.get(),
                    0,
                    (int) reasoningTokens.get(),
                    0));
            if (optimizedOutputs.get() > 0) {
                parent.mergeOptimizationCounters(
                        optimizedOutputs.get(), optimizedCharsBefore.get(), optimizedCharsAfter.get());
            }
        }
    }

    public String subagentId() {
        return subagentId;
    }
}
