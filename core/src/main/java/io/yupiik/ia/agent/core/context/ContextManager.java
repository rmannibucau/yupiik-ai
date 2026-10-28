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

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Triggers for context pruning/compaction and thin facades over the
 * {@link CompactionCoordinator}. The coordinator owns the retry/degrade logic
 * and honest-result reporting so compaction never fails silently.
 */
public class ContextManager {
    private final CodingAgentConfiguration configuration;
    private final UsageTracker usageTracker;
    private final CompactionCoordinator coordinator;

    public ContextManager(
            final CodingAgentConfiguration configuration,
            final UsageTracker usageTracker,
            final CompactionCoordinator coordinator) {
        this.configuration = configuration;
        this.usageTracker = usageTracker;
        this.coordinator = coordinator;
    }

    protected ContextManager() {
        this.configuration = null;
        this.usageTracker = null;
        this.coordinator = null;
    }

    public boolean pruneNeeded(final List<Message> messages) {
        return pruneNeeded(messages, 0);
    }

    public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
        final var p = configuration.pruning();
        return p.enabled()
                && usageTracker.contextPercent(usageTracker.effectiveContext(messages, extraToolTokens))
                        >= p.thresholdPercent();
    }

    public boolean compactNeeded(final List<Message> messages) {
        return compactNeeded(messages, 0);
    }

    public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
        final var c = configuration.compaction();
        if (!c.enabled()) {
            return false;
        }
        final var effective = usageTracker.effectiveContext(messages, extraToolTokens);
        if (c.thresholdPercent() > 0) {
            return usageTracker.contextPercent(effective) >= c.thresholdPercent();
        }
        return effective >= compactionTriggerTokens();
    }

    /**
     * The prompt token count that triggers the next automatic compaction: the legacy percentage of the
     * usable window when {@code thresholdPercent > 0}, else the opencode-style buffer trigger. Also used
     * by the anti-thrash notice and the usage status so every surface reports the same wall.
     *
     * @return the trigger in estimated prompt tokens, or {@link Integer#MAX_VALUE} when compaction is disabled.
     */
    public int compactionTriggerTokens() {
        if (configuration == null || usageTracker == null) {
            return Integer.MAX_VALUE;
        }
        final var c = configuration.compaction();
        if (!c.enabled()) {
            return Integer.MAX_VALUE;
        }
        if (c.thresholdPercent() > 0) {
            return (int) Math.ceil(usageTracker.usableWindow() * c.thresholdPercent() / 100.0);
        }
        return ContextBudget.compactionTrigger(
                usageTracker.contextWindowTokenCount().value(), c.bufferPercent(), c.bufferMinTokens());
    }

    /** Hard prompt-input limit, excluding output headroom and including external tool-schema tokens at call sites. */
    public int usableWindowTokens() {
        return usageTracker == null ? Integer.MAX_VALUE : usageTracker.usableWindow();
    }

    public CompactionResult prune(final List<Message> messages) {
        return coordinator.prune(messages);
    }

    public CompactionResult compact(final List<Message> messages) {
        return coordinator.compact(messages, false, null, 0);
    }

    /**
     * Explicit {@code /compact force}: runs compaction even when the context is still within
     * budget, using the tighter (halved tail) floors so short histories compact too.
     */
    public CompactionResult compact(final List<Message> messages, final boolean force) {
        return coordinator.compact(messages, force, null, 0);
    }

    /**
     * Model-requested compaction: an optional focus hint is folded into the summarizer prompt and an
     * optional {@code keepLastOverride} raises the verbatim tail floor.
     */
    public CompactionResult compact(final List<Message> messages, final String focus, final int keepLastOverride) {
        return coordinator.compact(messages, false, focus, keepLastOverride);
    }

    /**
     * Explicit compaction with a {@code force} flag (bypasses the within-budget guard so compaction
     * runs even below the thresholds), an optional focus hint folded into the summarizer prompt and an
     * optional {@code keepLastOverride} raising the verbatim tail floor.
     */
    public CompactionResult compact(
            final List<Message> messages, final boolean force, final String focus, final int keepLastOverride) {
        return coordinator.compact(messages, force, focus, keepLastOverride);
    }

    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final BooleanSupplier cancelled) {
        return coordinator.compact(messages, force, focus, keepLastOverride, cancelled, ChatSettings.EMPTY);
    }

    /** {@link #compact(List, boolean, String, int, BooleanSupplier)} for the session's model selection. */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        return coordinator.compact(messages, force, focus, keepLastOverride, cancelled, settings);
    }

    /** One tighter, bounded retry after automatic compaction leaves usage above its trigger. */
    public CompactionResult compactForRecovery(
            final List<Message> messages, final BooleanSupplier cancelled, final ChatSettings settings) {
        return compact(messages, true, null, 0, cancelled, settings);
    }

    public CompactionResult compactForOverflow(final List<Message> messages) {
        return coordinator.compact(messages, true, null, 0, Cancellations.NEVER, ChatSettings.EMPTY);
    }

    public CompactionResult compactForOverflow(final List<Message> messages, final BooleanSupplier cancelled) {
        return coordinator.compact(messages, true, null, 0, cancelled, ChatSettings.EMPTY);
    }

    /** {@link #compactForOverflow(List, BooleanSupplier)} for the session's model selection. */
    public CompactionResult compactForOverflow(
            final List<Message> messages, final BooleanSupplier cancelled, final ChatSettings settings) {
        return coordinator.compact(messages, true, null, 0, cancelled, settings);
    }
}
