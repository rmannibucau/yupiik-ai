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

import io.yupiik.ia.agent.core.llm.Usage;
import java.util.List;

/**
 * Events emitted on the global bus by the agent loops. Every event is tagged
 * with the {@code sessionId} of the session (tab) that owns the turn plus the
 * subagent coordinates when the turn runs inside a subagent: this is what lets
 * {@code ShellRegistry} route a given event stream to the right shell when
 * several sessions run concurrently in the same process. Producers must always
 * set the session id - there is no implicit fallback - so convenience
 * constructors take it explicitly.
 */
public sealed interface AgentEvent {
    /**
     * @return the session (tab) that owns the turn this event belongs to. Always
     *         set by the producers: the registry routes strictly on it and drops
     *         events without a session.
     */
    String sessionId();

    /**
     * @return the subagent id when the event was produced inside a subagent turn,
     *         {@code null} (the default) for parent-turn events. All record
     *         implementations carry this component and override the default.
     */
    default String subagentId() {
        return null;
    }

    /**
     * @return the subagent display name when the event was produced inside a
     *         subagent turn, {@code null} (the default) for parent-turn events.
     */
    default String subagentName() {
        return null;
    }

    record TextDelta(String delta, String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public TextDelta(String delta, String sessionId) {
            this(delta, sessionId, null, null);
        }
    }

    /**
     * The streaming buffers must be discarded: the interrupted stream is retried from scratch and
     * the fragments received before the failure would otherwise duplicate the retried reply prefix.
     */
    record TextReset(String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public TextReset(String sessionId) {
            this(sessionId, null, null);
        }
    }

    record ThinkingDelta(String delta, String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public ThinkingDelta(String delta, String sessionId) {
            this(delta, sessionId, null, null);
        }
    }

    record ToolStarted(String name, String args, long ordinal, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {
        public ToolStarted(String name, String args, String sessionId) {
            this(name, args, -1, sessionId, null, null);
        }
    }

    record ToolFinished(
            String name,
            String output,
            boolean success,
            boolean pending,
            long ordinal,
            String sessionId,
            String subagentId,
            String subagentName)
            implements AgentEvent {
        public ToolFinished(String name, String output, boolean success, String sessionId) {
            this(name, output, success, false, -1, sessionId, null, null);
        }

        public ToolFinished(
                String name, String output, boolean success, String sessionId, String subagentId, String subagentName) {
            this(name, output, success, false, -1, sessionId, subagentId, subagentName);
        }

        public ToolFinished(
                final String name,
                final String output,
                final boolean success,
                final boolean pending,
                final long ordinal,
                final String sessionId,
                final String subagentId,
                final String subagentName) {
            this.name = name;
            this.output = output;
            this.success = success;
            this.pending = pending;
            this.ordinal = ordinal;
            this.sessionId = sessionId;
            this.subagentId = subagentId;
            this.subagentName = subagentName;
        }
    }

    record ToolStream(
            String name, String fragment, long ordinal, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {
        public ToolStream(String name, String fragment, String sessionId) {
            this(name, fragment, -1, sessionId, null, null);
        }

        public ToolStream(String name, String fragment, String sessionId, String subagentId, String subagentName) {
            this(name, fragment, -1, sessionId, subagentId, subagentName);
        }
    }

    record UsageUpdated(
            Usage usage,
            int estimatedContextTokens,
            double contextPercent,
            int lastRealPromptTokens,
            double calibrationRatio,
            String model,
            String sessionId,
            String subagentId,
            String subagentName,
            Quota quota)
            implements AgentEvent {
        public UsageUpdated(Usage usage, int estimatedContextTokens, double contextPercent, String sessionId) {
            this(usage, estimatedContextTokens, contextPercent, 0, -1, null, sessionId, null, null, Quota.NONE);
        }

        /** The account quota carried by a usage event, {@link #NONE} when the provider does not expose usage. */
        public record Quota(
                boolean enabled,
                boolean supported,
                String planType,
                Double usedPercent,
                long windowSeconds,
                long resetAtEpochSeconds,
                String windowLabel,
                String creditsBalance,
                boolean creditsUnlimited,
                List<Window> windows) {

            public static final Quota NONE = new Quota(false, false, null, null, 0, 0, null, null, false, List.of());

            public Quota(
                    final boolean enabled,
                    final boolean supported,
                    final String planType,
                    final double usedPercent,
                    final long windowSeconds,
                    final long resetAtEpochSeconds,
                    final String windowLabel,
                    final String creditsBalance,
                    final boolean creditsUnlimited) {
                this(
                        enabled,
                        supported,
                        planType,
                        usedPercent,
                        windowSeconds,
                        resetAtEpochSeconds,
                        windowLabel,
                        creditsBalance,
                        creditsUnlimited,
                        List.of(new Window("primary", windowLabel, usedPercent, windowSeconds, resetAtEpochSeconds)));
            }

            public record Window(
                    String kind, String label, Double usedPercent, Long windowSeconds, Long resetAtEpochSeconds) {
                public String displayLabel() {
                    if (windowSeconds == null || windowSeconds <= 0) {
                        return label == null || label.isBlank() ? "quota" : label;
                    }
                    if (windowSeconds == 604800) {
                        return "weekly";
                    }
                    if (windowSeconds % 86400 == 0) {
                        return windowSeconds / 86400 + "d";
                    }
                    if (windowSeconds % 3600 == 0) {
                        return windowSeconds / 3600 + "h";
                    }
                    return windowSeconds % 60 == 0 ? windowSeconds / 60 + "m" : windowSeconds + "s";
                }
            }

            public static Quota of(
                    final boolean enabled,
                    final boolean supported,
                    final io.yupiik.ia.agent.core.usage.CodexUsageSnapshot snapshot) {
                if (snapshot == null) {
                    return new Quota(false, false, null, null, 0, 0, null, null, false, List.of());
                }
                final var primary = snapshot.rateLimits() == null
                        ? null
                        : snapshot.rateLimits().stream()
                                .filter(l -> io.yupiik.ia.agent.core.usage.CodexUsageSnapshot.RateLimit.PRIMARY.equals(
                                        l.kind()))
                                .findFirst()
                                .orElse(null);
                final boolean unlimited = snapshot.credits() != null
                        && Boolean.TRUE.equals(snapshot.credits().unlimited());
                final var windows = snapshot.rateLimits() == null
                        ? List.<Window>of()
                        : snapshot.rateLimits().stream()
                                .map(limit -> new Window(
                                        limit.kind(),
                                        limit.label(),
                                        limit.usedPercent(),
                                        limit.limitWindowSeconds(),
                                        limit.resetAt()))
                                .toList();
                return new Quota(
                        enabled,
                        supported,
                        snapshot.planType(),
                        primary == null ? null : primary.usedPercent(),
                        primary == null || primary.limitWindowSeconds() == null ? 0 : primary.limitWindowSeconds(),
                        primary == null || primary.resetAt() == null ? 0 : primary.resetAt(),
                        primary == null ? null : primary.label(),
                        snapshot.credits() == null ? null : snapshot.credits().balance(),
                        unlimited,
                        windows);
            }

            public boolean visible() {
                return supported && planType != null;
            }
        }
    }

    /**
     * Logical (main) LLM request completion, emitted exactly once per {@code modelRoundProcessor.stream}
     * invocation regardless of how the dialect fragments the wire into {@code End} events (a usage-only
     * {@code End} plus a separate finish-reason {@code End}, or a single combined one). It is the single
     * authoritative count of real model requests per turn (used for {@code llmCalls} and per-request token
     * aggregation) and is intentionally distinct from transport attempts/retries, which are internal to the
     * stream call and never surface here. When the provider reports no usage, {@link Usage#EMPTY} is carried
     * and the request still counts - a real zero-usage request must never be skipped.
     *
     * <p>It is not emitted by the display-only quota/context refresh path ({@code pushUsageQuota} /
     * {@code refreshUsage}), so those synthetic {@link UsageUpdated}s never inflate the request or token
     * aggregates.
     *
     * @param usage the per-request usage as reported by the provider, {@link Usage#EMPTY} when none
     * @param model the resolved model that served the request
     * @param index 1-based ordinal of this logical request within the current turn (successive rounds count
     *        independently, 1, 2, 3, ...)
     */
    record LlmRequestCompleted(
            Usage usage, String model, int index, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {

        public LlmRequestCompleted(final Usage usage, final String model, final int index, final String sessionId) {
            this(usage, model, index, sessionId, null, null);
        }
    }

    record Compacted(List<String> kinds, String mode, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {
        /** Convenience for the single-strategy case (explicit tool request, LLM-only compaction...). */
        public Compacted(String kind, String mode, String sessionId) {
            this(List.of(kind), mode, sessionId, null, null);
        }

        /** Convenience for the merged-strategy case (several reductions of the same checkpoint). */
        public Compacted(List<String> kinds, String mode, String sessionId) {
            this(kinds, mode, sessionId, null, null);
        }
    }

    record Status(String message, String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public Status(String message, final String sessionId) {
            this(message, sessionId, null, null);
        }
    }

    /**
     * A transient provider-imposed delay: rate-limit throttling or a retry backoff
     * surfaced by the chat resilience layer. Unlike {@link Status}, a pause must
     * never replace the leading agent state (it is a side fact about the current
     * call, not the turn's state); frontends render it in their trailing
     * notification slot - the TUI's right-side {@code ⓘ} slot - and the next
     * pause overwrites it. It is not persisted: like {@link Status} it is
     * transient feedback, the caller reports the failure itself if retries
     * eventually exhaust.
     */
    record Pause(Kind kind, String message, long durationMs, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {

        public enum Kind {
            THROTTLE,
            RETRY
        }

        public Pause(final Kind kind, final String message, final long durationMs, final String sessionId) {
            this(kind, message, durationMs, sessionId, null, null);
        }

        public static Pause throttle(final String message, final long durationMs, final String sessionId) {
            return new Pause(Kind.THROTTLE, message, durationMs, sessionId);
        }

        public static Pause retry(final String message, final long durationMs, final String sessionId) {
            return new Pause(Kind.RETRY, message, durationMs, sessionId);
        }
    }

    /**
     * A durable, user-visible non-conversation notice. Unlike {@link Status},
     * this is rendered as a distinct transcript line (not only the transient
     * status bar) so feedback survives and is understandable in all cases,
     * including failures that would otherwise be silent.
     */
    record Notice(
            Level level,
            String message,
            String finishReason,
            String sessionId,
            String subagentId,
            String subagentName,
            Throwable cause)
            implements AgentEvent {

        public enum Level {
            INFO,
            WARN,
            ERROR
        }

        public Notice(final Level level, final String message, final String sessionId) {
            this(level, message, null, sessionId, null, null, null);
        }

        public Notice(final Level level, final String message, final String finishReason, final String sessionId) {
            this(level, message, finishReason, sessionId, null, null, null);
        }

        /** Cause-less construction keeping the pre-stack-trace arity; {@code cause} defaults to {@code null}. */
        public Notice(
                final Level level,
                final String message,
                final String finishReason,
                final String sessionId,
                final String subagentId,
                final String subagentName) {
            this(level, message, finishReason, sessionId, subagentId, subagentName, null);
        }

        public static Notice info(final String message, final String sessionId) {
            return new Notice(Level.INFO, message, sessionId);
        }

        public static Notice warn(final String message, final String sessionId) {
            return new Notice(Level.WARN, message, sessionId);
        }

        public static Notice error(final String message, final String sessionId) {
            return new Notice(Level.ERROR, message, sessionId);
        }
    }

    record TurnDone(String content, String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public TurnDone(String content, final String sessionId) {
            this(content, sessionId, null, null);
        }
    }

    record TurnCancelled(String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public TurnCancelled(final String sessionId) {
            this(sessionId, null, null);
        }
    }

    record Error(String message, String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public Error(String message, final String sessionId) {
            this(message, sessionId, null, null);
        }
    }

    /**
     * A sub-agent was spawned under the parent session: the parent transcript
     * records a marker so the user knows work is progressing off-screen
     * (details are visible through {@code /subagents}).
     */
    record SubagentStarted(String task, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {}

    /**
     * A sub-agent finished (success, failure or cancellation). The parent transcript records a
     * marker; the detailed conversation stays reachable through {@code /subagents}.
     *
     * @param cancelled the sub-agent stopped on a cancel (parent interrupt or an explicit
     *                  {@code cancel_subagent}), not on a failure: reported separately so a
     *                  cancelled child never renders as a red error card
     */
    record SubagentCompleted(
            String summary,
            boolean success,
            boolean cancelled,
            String sessionId,
            String subagentId,
            String subagentName)
            implements AgentEvent {

        public SubagentCompleted(
                final String summary,
                final boolean success,
                final String sessionId,
                final String subagentId,
                final String subagentName) {
            this(summary, success, false, sessionId, subagentId, subagentName);
        }
    }

    /**
     * A finished sub-agent's result was injected into the parent turn's context as a model-visible
     * message. Unlike {@link SubagentCompleted} (a transcript marker), this signals the push delivery
     * so frontends can surface that the parent will now see the child's outcome on its next round.
     */
    record SubagentDelivered(
            String summary,
            boolean success,
            boolean cancelled,
            String sessionId,
            String subagentId,
            String subagentName)
            implements AgentEvent {
        public SubagentDelivered(
                final String summary, final boolean success, final boolean cancelled, final String sessionId) {
            this(summary, success, cancelled, sessionId, null, null);
        }
    }

    /**
     * A queued prompt was injected into the running turn before it completed, so
     * the user does not have to wait for the current turn to finish.
     */
    record PromptIntegrated(String text, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {
        public PromptIntegrated(String text, String sessionId) {
            this(text, sessionId, null, null);
        }
    }

    /**
     * Output throughput of a turn: the tokens it produced versus the time actually spent producing them.
     *
     * @param outputTokens output tokens produced in the turn, as reported by the provider or estimated
     *        when the provider reports no usage
     * @param reasoningTokens output tokens spent on internal reasoning within {@code outputTokens}, 0
     *        when the provider does not report a breakdown
     * @param activeMs time spent inside LLM requests, excluding provider throttles, retry backoffs and
     *        everything outside of the LLM calls (tool executions, compaction, user typing, ...)
     * @param reportedMs generation time as reported by the provider itself, {@code 0} when the provider
     *        does not publish any
     */
    record Throughput(int outputTokens, int reasoningTokens, long activeMs, long reportedMs) {
        /** Zeroed throughput, used when a turn produced nothing. */
        public static final Throughput EMPTY = new Throughput(0, 0, 0, 0);

        public Throughput(final int outputTokens, final long activeMs, final long reportedMs) {
            this(outputTokens, 0, activeMs, reportedMs);
        }

        /**
         * Our own measurement: output tokens over the time actually spent producing them. This is the
         * number to trust to compare models and runtimes, it never includes idle time.
         *
         * @return the average output tokens per second, {@code 0} when it cannot be computed.
         */
        public double tokensPerSecond() {
            return activeMs > 0 && outputTokens > 0 ? outputTokens * 1000.0 / activeMs : 0;
        }

        /**
         * The output tokens that were not spent reasoning: {@code outputTokens} minus
         * {@code reasoningTokens}, never negative.
         *
         * @return the visible output token count.
         */
        public int outputTokensOnly() {
            return Math.max(0, outputTokens - reasoningTokens);
        }

        /**
         * The reasoning throughput: reasoning tokens over the time actually spent producing them.
         *
         * @return the average reasoning tokens per second, {@code 0} when it cannot be computed.
         */
        public double reasoningTokensPerSecond() {
            return activeMs > 0 && reasoningTokens > 0 ? reasoningTokens * 1000.0 / activeMs : 0;
        }

        /**
         * The provider own view, when it publishes a generation time. It is displayed next to
         * {@link #tokensPerSecond()} to expose the client overhead, and is {@code 0} when unknown.
         *
         * @return the average output tokens per second as seen by the server, {@code 0} when unknown.
         */
        public double reportedTokensPerSecond() {
            return reportedMs > 0 && outputTokens > 0 ? outputTokens * 1000.0 / reportedMs : 0;
        }

        /**
         * @return {@code true} when at least one of the two rates can be displayed.
         */
        public boolean measurable() {
            return tokensPerSecond() > 0 || reportedTokensPerSecond() > 0;
        }
    }

    /**
     * Per-turn aggregate metrics emitted at turn completion.
     *
     * @param llmCalls              number of (main) LLM requests in this turn, one per {@link LlmRequestCompleted}
     * @param thinkingDurationMs    sum of wall-clock durations of all thinking sequences in this turn
     * @param toolDurationSumMs     sum of individual tool durations (linear, e.g. 3 tools x 100ms = 300ms)
     * @param toolDurationWallMs    wall-clock batch duration: first tool start to last tool finish in parallel batches
     * @param toolsRun              total number of tool executions in this turn
     * @param turnDurationMs        total turn wall time: turn start to TurnDone
     * @param throughput            output tokens of the turn versus the time spent generating them
     */
    record TurnMetrics(
            int llmCalls,
            long thinkingDurationMs,
            long toolDurationSumMs,
            long toolDurationWallMs,
            int toolsRun,
            long turnDurationMs,
            Throughput throughput,
            String sessionId,
            String subagentId,
            String subagentName)
            implements AgentEvent {

        public TurnMetrics(
                int llmCalls,
                long thinkingDurationMs,
                long toolDurationSumMs,
                long toolDurationWallMs,
                int toolsRun,
                long turnDurationMs,
                String sessionId) {
            this(
                    llmCalls,
                    thinkingDurationMs,
                    toolDurationSumMs,
                    toolDurationWallMs,
                    toolsRun,
                    turnDurationMs,
                    Throughput.EMPTY,
                    sessionId,
                    null,
                    null);
        }

        public TurnMetrics(
                int llmCalls,
                long thinkingDurationMs,
                long toolDurationSumMs,
                long toolDurationWallMs,
                int toolsRun,
                long turnDurationMs,
                Throughput throughput,
                String sessionId) {
            this(
                    llmCalls,
                    thinkingDurationMs,
                    toolDurationSumMs,
                    toolDurationWallMs,
                    toolsRun,
                    turnDurationMs,
                    throughput,
                    sessionId,
                    null,
                    null);
        }
    }

    /**
     * Individual tool timing event emitted from ToolExecutionService.
     *
     * @param toolName         name of the tool that executed
     * @param durationMs       individual tool execution duration
     * @param wallDurationMs   for parallel batches: batch elapsed time (first start to last finish); for sequential: equals durationMs
     * @param parallelBatch    true if this tool was part of a parallel batch
     */
    record ToolTimingEvent(
            String toolName,
            long durationMs,
            long wallDurationMs,
            boolean parallelBatch,
            String sessionId,
            String subagentId,
            String subagentName)
            implements AgentEvent {

        public ToolTimingEvent(
                String toolName, long durationMs, long wallDurationMs, boolean parallelBatch, String sessionId) {
            this(toolName, durationMs, wallDurationMs, parallelBatch, sessionId, null, null);
        }
    }

    /**
     * Emitted at well-defined phase boundaries of the task-solving loop so bench consumers can reconstruct
     * the critical path (model request sent -> stream complete -> tool execution -> next request) without
     * inferring it from wall-deltas. Carries no payload beyond the phase name; the timing is the event's
     * arrival time relative to {@code detail.log} ticks. Benchmark/observability marker: UIs may ignore it.
     *
     * @param name one of {@code request-sent}, {@code stream-complete}, {@code tool-batch-complete},
     *     {@code iteration-complete}.
     */
    record Phase(String name, String sessionId, String subagentId, String subagentName) implements AgentEvent {
        public static final String REQUEST_SENT = "request-sent";
        public static final String STREAM_COMPLETE = "stream-complete";
        public static final String TOOL_BATCH_COMPLETE = "tool-batch-complete";
        public static final String ITERATION_COMPLETE = "iteration-complete";

        // BOUNDED initialization telemetry: paired start/end markers that decompose the wall-clock
        // between process/run entry and the first REQUEST_SENT, so a slow pre-first-request startup
        // (JVM boot, DI bootstrap, runtime creation, model resolution, prompt build, initial-prompt
        // hook, provider hand-off) can be attributed without speculation. REQUEST_SENT itself is the
        // pre-processor marker: it is emitted immediately before the modelRoundProcessor.stream()
        // hand-off, i.e. it measures the request entering the provider layer, not the wire I/O that
        // happens inside stream(). Any time between the run/call entry and RUNTIME_CREATE_START is
        // NOT covered by these paired markers - it must be attributed externally. That pre-runtime
        // interval may include in-command setup (metrics/jump wiring) and is not necessarily JVM
        // boot: the run detail clock starts inside RunCommand, so nothing here claims process start.
        public static final String RUNTIME_CREATE_START = "runtime-create-start";
        public static final String RUNTIME_CREATE_END = "runtime-create-end";
        public static final String MODEL_RESOLUTION_START = "model-resolution-start";
        public static final String MODEL_RESOLUTION_END = "model-resolution-end";
        public static final String PROMPT_BUILD_START = "prompt-build-start";
        public static final String PROMPT_BUILD_END = "prompt-build-end";
        public static final String INITIAL_PROMPT_START = "initial-prompt-start";
        public static final String INITIAL_PROMPT_END = "initial-prompt-end";

        public Phase(final String name, final String sessionId) {
            this(name, sessionId, null, null);
        }
    }
}
