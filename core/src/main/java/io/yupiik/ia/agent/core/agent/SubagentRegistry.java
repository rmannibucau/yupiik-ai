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
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

@ApplicationScoped
public class SubagentRegistry {
    private static final Logger LOGGER = Logger.getLogger(SubagentRegistry.class.getName());

    private final CodingAgentConfiguration configuration;
    private final ProviderManager providerManager;
    private final Map<String, SubagentState> subagents = new ConcurrentHashMap<>();
    private final Map<String, ConversationTranscript> subagentTranscripts = new ConcurrentHashMap<>();
    /**
     * Completed sub-agent results keyed by sub-agent id. Kept separately from {@link #subagents} so
     * {@code collect} can still return a finished result even after the state was LRU-evicted from
     * {@link #subagents} (the {@code get -> collect} race). Bounded (via an insertion-ordered eviction
     * policy) above the state-map bound so a result whose state was just evicted survives a delayed collect.
     */
    private final Map<String, SubagentResult> completedResults =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, SubagentResult> eldest) {
                    return size() > Math.max(maxCompletedStates(), 64);
                }
            });

    private final AtomicInteger activeCount = new AtomicInteger(0);
    private volatile Semaphore semaphore;
    private volatile int maxConcurrent;
    private final SpeakerLabels speakerLabels;

    public SubagentRegistry(final CodingAgentConfiguration configuration, final ProviderManager providerManager) {
        this(configuration, providerManager, null);
    }

    public SubagentRegistry(
            final CodingAgentConfiguration configuration,
            final ProviderManager providerManager,
            final SpeakerLabels speakerLabels) {
        this.configuration = configuration;
        this.providerManager = providerManager;
        this.speakerLabels = speakerLabels;
        updateSemaphore();
    }

    protected SubagentRegistry() {
        this.configuration = null;
        this.providerManager = null;
        this.speakerLabels = null;
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

        // Process-local subagent budget (number of simultaneous Subagent AgentLoops), independent of
        // the provider's in-flight LLM budget enforced by the shared ChatClient: an AgentLoop spends
        // most of its wall-clock in tool execution, so provider.maxConcurrent is no longer "minus one"
        // (the main session can be idle while a child runs) - the shared client queues any LLM overflow.
        int effectiveModelMax = modelMax > 0 ? modelMax : Integer.MAX_VALUE;
        int actualMax = Math.min(effectiveModelMax, configMax);
        this.maxConcurrent = Math.max(0, actualMax);
        this.semaphore = this.maxConcurrent > 0 ? new Semaphore(this.maxConcurrent) : null;
        if (this.maxConcurrent > 0 && modelMax > 0 && this.maxConcurrent > 2L * modelMax) {
            LOGGER.warning(
                    () -> "subagents.maxConcurrent=" + this.maxConcurrent + " exposes more simultaneous AgentLoops "
                            + "than the provider LLM budget can serve (provider.maxConcurrent=" + modelMax
                            + "): most subagents will queue on the shared client until an LLM permit frees.");
        }
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
        return start(parentSessionId, task, name, null);
    }

    /**
     * Starts a sub-agent, optionally with a requested model/reference.
     *
     * @param model the raw model reference from {@code spawn_subagent model=} ($provider/model or bare), or {@code null}
     *     when the caller did not request one (the provider {@code defaultSubagentModel} or parent inheritance applies).
     */
    public Optional<SubagentState> start(
            final String parentSessionId, final String task, final String name, final String model) {
        return start(new AgentRunSpec(parentSessionId, task, name, null, model, null, null, null));
    }

    public Optional<SubagentState> start(final AgentRunSpec spec) {
        if (!enabled()) {
            return Optional.empty();
        }
        if (semaphore == null || !semaphore.tryAcquire()) {
            return Optional.empty();
        }
        activeCount.incrementAndGet();
        final var id = UUID.randomUUID().toString();
        final var state = new SubagentState(id, spec, System.currentTimeMillis());
        subagents.put(id, state);
        return Optional.of(state);
    }

    public void complete(final String subagentId, final SubagentResult result) {
        final var state = subagents.get(subagentId);
        if (state != null && state.complete(result)) {
            activeCount.decrementAndGet();
            if (semaphore != null) {
                semaphore.release();
            }
            completedResults.put(subagentId, result);
        }
        cleanup();
    }

    public void cancel(final String subagentId) {
        final var state = subagents.get(subagentId);
        if (state != null && state.cancel()) {
            subagents.remove(subagentId, state);
            activeCount.decrementAndGet();
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }

    /**
     * Latches the cancel of every sub-agent spawned by a session: called when that session's turn
     * ends on an interrupt. Pushing it here (rather than only letting children poll the parent
     * probe) matters because the UI resets its interrupt flag when the next turn starts, so a child
     * polling afterwards would never see the cancel.
     *
     * @return the number of sub-agents cancelled
     */
    public int cancelByParentSession(final String parentSessionId) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return 0;
        }
        var cancelled = 0;
        for (final var entry : List.copyOf(subagents.entrySet())) {
            if (parentSessionId.equals(entry.getValue().parentSessionId())) {
                cancel(entry.getKey());
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * The per-subagent transcript capture for the given id, lazily created. This
     * instance folds the subagent-tagged events (they are dropped from the parent
     * transcript) so the TUI can render the child conversation through
     * {@code /subagents <id>} without mixing two model voices in one pane.
     */
    public ConversationTranscript transcriptOf(final String subagentId) {
        return subagentTranscripts.computeIfAbsent(subagentId, id -> {
            final var t = new ConversationTranscript(true);
            if (speakerLabels != null) {
                final var current = providerManager == null ? null : providerManager.current();
                final var ctx = new SpeakerLabels.Ctx(
                        current == null || current.model() == null ? null : current.model(),
                        providerManager == null ? null : providerManager.currentProviderId(),
                        id,
                        null,
                        null,
                        null);
                t.setLabels(speakerLabels.userLabel(ctx), speakerLabels.assistantLabel(ctx));
                t.setLabelResolver((identity, user) -> {
                    final var ictx = new SpeakerLabels.Ctx(
                            identity.model(), identity.provider(), identity.agent(), null, null, null);
                    return user ? speakerLabels.userLabel(ictx) : speakerLabels.assistantLabel(ictx);
                });
            }
            return t;
        });
    }

    /** Drops the transient subagent captures that already completed; bounded by the LRU cleanup. */
    public void dropTranscript(final String subagentId) {
        subagentTranscripts.remove(subagentId);
    }

    public Optional<SubagentResult> collect(final String subagentId) {
        final var state = subagents.remove(subagentId);
        if (state != null && state.isDone()) {
            completedResults.remove(subagentId);
            return Optional.ofNullable(state.result());
        }
        // the state may have been LRU-evicted from the (bounded) subagents map by a concurrent
        // complete() of another child in the get->collect window; the result cache still holds the
        // finished outcome so a delayed collect is never a spurious "no result".
        return Optional.ofNullable(completedResults.remove(subagentId));
    }

    public Optional<SubagentState> get(final String subagentId) {
        return Optional.ofNullable(subagents.get(subagentId));
    }

    public List<SubagentState> listActive() {
        return subagents.values().stream().filter(s -> !s.isDone()).toList();
    }

    /** Every tracked subagent of a parent session (running and recently completed). */
    public List<SubagentState> listAll(final String parentSessionId) {
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
        private final AgentRunSpec spec;
        private final long startedAt;
        private volatile SubagentResult result;
        private volatile boolean cancelled;
        private volatile boolean done;
        private volatile long completedAt;

        public SubagentState(final String id, final String parentSessionId, final String task, final long startedAt) {
            this(id, new AgentRunSpec(parentSessionId, task, null, null, null, null, null, null), startedAt);
        }

        public SubagentState(
                final String id,
                final String parentSessionId,
                final String task,
                final String name,
                final long startedAt) {
            this(id, new AgentRunSpec(parentSessionId, task, name, null, null, null, null, null), startedAt);
        }

        public SubagentState(
                final String id,
                final String parentSessionId,
                final String task,
                final String name,
                final long startedAt,
                final String model) {
            this(id, new AgentRunSpec(parentSessionId, task, name, null, model, null, null, null), startedAt);
        }

        public SubagentState(final String id, final AgentRunSpec spec, final long startedAt) {
            this.id = id;
            this.spec = spec;
            this.startedAt = startedAt;
        }

        public String id() {
            return id;
        }

        public String parentSessionId() {
            return spec.parentSessionId();
        }

        public String task() {
            return spec.task();
        }

        public String name() {
            return spec.name();
        }

        /** The raw model reference requested via {@code spawn_subagent model=}, or {@code null} when none was requested. */
        public String model() {
            return spec.model();
        }

        public String profile() {
            return spec.profile();
        }

        public Integer maxTurns() {
            return spec.maxTurns();
        }

        public Set<String> allowedTools() {
            return spec.allowedTools();
        }

        public Set<String> parentAllowedTools() {
            return spec.parentAllowedTools();
        }

        public AgentRunSpec spec() {
            return spec;
        }

        public long startedAt() {
            return startedAt;
        }

        public long completedAt() {
            return completedAt;
        }

        public synchronized boolean complete(final SubagentResult result) {
            if (done) {
                return false;
            }
            this.result = result;
            this.done = true;
            this.completedAt = System.currentTimeMillis();
            notifyAll();
            return true;
        }

        public synchronized boolean cancel() {
            if (done) {
                return false;
            }
            this.cancelled = true;
            this.done = true;
            this.completedAt = System.currentTimeMillis();
            notifyAll();
            return true;
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
        public enum Status {
            SUCCEEDED,
            FAILED,
            CANCELLED,
            BUDGET_EXHAUSTED
        }

        private final String summary;
        private final String fullConversation;
        private final List<String> artifacts;
        private final Status status;
        private final String error;

        private SubagentResult(
                final String summary,
                final String fullConversation,
                final List<String> artifacts,
                final Status status,
                final String error) {
            this.summary = summary;
            this.fullConversation = fullConversation;
            this.artifacts = List.copyOf(artifacts);
            this.status = status;
            this.error = error;
        }

        public static SubagentResult success(
                final String summary, final String fullConversation, final List<String> artifacts) {
            return new SubagentResult(summary, fullConversation, artifacts, Status.SUCCEEDED, null);
        }

        public static SubagentResult error(final String error, final String fullConversation) {
            return new SubagentResult(null, fullConversation, List.of(), Status.FAILED, error);
        }

        /**
         * The sub-agent stopped on a cancel (parent interrupt or an explicit
         * {@code cancel_subagent}) instead of failing: the parent transcript must render it as a
         * cancellation, not as a red "Subagent failed" card.
         */
        public static SubagentResult cancelled(final String fullConversation) {
            return new SubagentResult(null, fullConversation, List.of(), Status.CANCELLED, "Sub-agent cancelled");
        }

        public static SubagentResult budgetExhausted(final String fullConversation, final String message) {
            return new SubagentResult(null, fullConversation, List.of(), Status.BUDGET_EXHAUSTED, message);
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
            return status == Status.SUCCEEDED;
        }

        public boolean cancelled() {
            return status == Status.CANCELLED;
        }

        public Status status() {
            return status;
        }

        public boolean budgetExhausted() {
            return status == Status.BUDGET_EXHAUSTED;
        }

        public String error() {
            return error;
        }
    }
}
