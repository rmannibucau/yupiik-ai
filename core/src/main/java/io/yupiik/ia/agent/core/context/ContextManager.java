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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.List;

/**
 * Triggers for context pruning/compaction and thin facades over the
 * {@link CompactionCoordinator}. The coordinator owns the retry/degrade logic
 * and honest-result reporting so compaction never fails silently.
 */
@ApplicationScoped
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
                && usageTracker.contextPercent(usageTracker.estimateContext(messages, extraToolTokens))
                        >= p.thresholdPercent();
    }

    public boolean compactNeeded(final List<Message> messages) {
        return compactNeeded(messages, 0);
    }

    public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
        final var c = configuration.compaction();
        return c.enabled()
                && usageTracker.contextPercent(usageTracker.estimateContext(messages, extraToolTokens))
                        >= c.thresholdPercent();
    }

    public CompactionResult prune(final List<Message> messages) {
        return coordinator.prune(messages);
    }

    public CompactionResult compact(final List<Message> messages) {
        return coordinator.compact(messages, false);
    }

    public CompactionResult compactForOverflow(final List<Message> messages) {
        return coordinator.compact(messages, true);
    }
}
