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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class SubagentRegistry {
    private final CodingAgentConfiguration configuration;
    private final ProviderManager providerManager;
    private final Map<String, SubagentState> subagents = new ConcurrentHashMap<>();
    private final Map<String, ConversationTranscript> subagentTranscripts = new ConcurrentHashMap<>();
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private volatile Semaphore semaphore;
    private volatile int maxConcurrent;

    public SubagentRegistry(final CodingAgentConfiguration configuration, final ProviderManager providerManager) {
        this.configuration = configuration;
        this.providerManager = providerManager;
        updateSemaphore();
    }

    protected SubagentRegistry() {
        this.configuration = null;
        this.providerManager = null;
    }

    public void updateSemaphore() {
        final var provider = providerManager != null ? providerManager.current() : null;
        final var configMax = configuration != null && configuration.subagents() != null
                ? configuration.subagents().maxConcurrent()
                : 4;
        final var modelMax = provider != null && provider.maxConcurrent() >= 0
                ? provider.maxConcurrent()
                : (configuration != null && configuration.llm() != null
                        ? configuration.llm().maxConcurrent()
                        : -1);

        int effectiveModelMax = modelMax > 0 ? modelMax : Integer.MAX_VALUE;
        int actualMax = Math.min(effectiveModelMax - 1, configMax);
        this.maxConcurrent = Math.max(0, actualMax);
        this.semaphore = this.maxConcurrent > 0 ? new Semaphore(this.maxConcurrent) : null;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public boolean enabled() {
        return configuration != null
                && configuration.subagents() != null
                && configuration.subagents().enabled()
                && maxConcurrent > 0;
    }

    public Optional<SubagentState> start(final String parentSessionId, final String task) {
        return start(parentSessionId, task, null);
    }

    public Optional<SubagentState> start(final String parentSessionId, final String task, final String name) {
        if (!enabled()) {
            return Optional.empty();
        }
        if (semaphore == null || !semaphore.tryAcquire()) {
            return Optional.empty();
        }
        activeCount.incrementAndGet();
        final var id = UUID.randomUUID().toString();
        final var state = new SubagentState(id, parentSessionId, task, name, System.currentTimeMillis());
        subagents.put(id, state);
        return Optional.of(state);
    }

    public void complete(final String subagentId, final SubagentResult result) {
        final var state = subagents.get(subagentId);
        if (state != null) {
            state.complete(result);
            activeCount.decrementAndGet();
            if (semaphore != null) {
                semaphore.release();
            }
        }
        cleanup();
    }

    public void cancel(final String subagentId) {
        final var state = subagents.remove(subagentId);
        if (state != null) {
            state.cancel();
            activeCount.decrementAndGet();
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }

    /**
     * The per-subagent transcript capture for the given id, lazily created. This
     * instance folds the subagent-tagged events (they are dropped from the parent
     * transcript) so the TUI can render the child conversation through
     * {@code /subagents <id>} without mixing two model voices in one pane.
     */
    public ConversationTranscript transcriptOf(final String subagentId) {
        return subagentTranscripts.computeIfAbsent(subagentId, _ -> new ConversationTranscript(true));
    }

    /** Drops the transient subagent captures that already completed; bounded by the LRU cleanup. */
    public void dropTranscript(final String subagentId) {
        subagentTranscripts.remove(subagentId);
    }

    public Optional<SubagentResult> collect(final String subagentId) {
        final var state = subagents.remove(subagentId);
        if (state != null && state.isDone()) {
            return Optional.ofNullable(state.result());
        }
        return Optional.empty();
    }

    public Optional<SubagentState> get(final String subagentId) {
        cleanup();
        return Optional.ofNullable(subagents.get(subagentId));
    }

    public List<SubagentState> listActive() {
        cleanup();
        return subagents.values().stream().filter(s -> !s.isDone()).toList();
    }

    /** Every tracked subagent of a parent session (running and recently completed). */
    public List<SubagentState> listAll(final String parentSessionId) {
        cleanup();
        return subagents.values().stream()
                .filter(s -> parentSessionId == null || parentSessionId.equals(s.parentSessionId()))
                .sorted((a, b) -> Long.compare(a.startedAt(), b.startedAt()))
                .toList();
    }

    public int activeCount() {
        return activeCount.get();
    }

    /**
     * Safety guard: maximum completed states retained before LRU eviction.
     * Scales with concurrency limit to bound memory.
     */
    private int maxCompletedStates() {
        return Math.max(3, maxConcurrent * 3);
    }

    private void cleanup() {
        // Evict oldest completed states when exceeding maxCompletedStates()
        final var completed = subagents.values().stream()
                .filter(SubagentState::isDone)
                .sorted((a, b) -> Long.compare(a.completedAt(), b.completedAt()))
                .toList();
        final var toRemove = completed.size() - maxCompletedStates();
        if (toRemove > 0) {
            for (int i = 0; i < toRemove; i++) {
                subagents.remove(completed.get(i).id());
            }
        }
    }

    public static final class SubagentState {
        private final String id;
        private final String parentSessionId;
        private final String task;
        private final String name;
        private final long startedAt;
        private volatile SubagentResult result;
        private volatile boolean cancelled;
        private volatile boolean done;
        private volatile long completedAt;

        public SubagentState(final String id, final String parentSessionId, final String task, final long startedAt) {
            this(id, parentSessionId, task, null, startedAt);
        }

        public SubagentState(
                final String id,
                final String parentSessionId,
                final String task,
                final String name,
                final long startedAt) {
            this.id = id;
            this.parentSessionId = parentSessionId;
            this.task = task;
            this.name = name;
            this.startedAt = startedAt;
        }

        public String id() {
            return id;
        }

        public String parentSessionId() {
            return parentSessionId;
        }

        public String task() {
            return task;
        }

        public String name() {
            return name;
        }

        public long startedAt() {
            return startedAt;
        }

        public long completedAt() {
            return completedAt;
        }

        public synchronized void complete(final SubagentResult result) {
            this.result = result;
            this.done = true;
            this.completedAt = System.currentTimeMillis();
            notifyAll();
        }

        public synchronized void cancel() {
            this.cancelled = true;
            this.done = true;
            this.completedAt = System.currentTimeMillis();
            notifyAll();
        }

        public synchronized SubagentResult awaitResult() throws InterruptedException {
            while (!done) {
                wait();
            }
            return result;
        }

        public boolean isDone() {
            return done;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public SubagentResult result() {
            return result;
        }
    }

    public static final class SubagentResult {
        private final String summary;
        private final String fullConversation;
        private final List<String> artifacts;
        private final boolean success;
        private final String error;

        private SubagentResult(
                final String summary,
                final String fullConversation,
                final List<String> artifacts,
                final boolean success,
                final String error) {
            this.summary = summary;
            this.fullConversation = fullConversation;
            this.artifacts = artifacts;
            this.success = success;
            this.error = error;
        }

        public static SubagentResult success(
                final String summary, final String fullConversation, final List<String> artifacts) {
            return new SubagentResult(summary, fullConversation, artifacts, true, null);
        }

        public static SubagentResult error(final String error, final String fullConversation) {
            return new SubagentResult(null, fullConversation, List.of(), false, error);
        }

        public String summary() {
            return summary;
        }

        public String fullConversation() {
            return fullConversation;
        }

        public List<String> artifacts() {
            return artifacts;
        }

        public boolean success() {
            return success;
        }

        public String error() {
            return error;
        }
    }
}
