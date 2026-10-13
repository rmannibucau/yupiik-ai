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
            long ordinal,
            String sessionId,
            String subagentId,
            String subagentName)
            implements AgentEvent {
        public ToolFinished(String name, String output, boolean success, String sessionId) {
            this(name, output, success, -1, sessionId, null, null);
        }

        public ToolFinished(
                String name, String output, boolean success, String sessionId, String subagentId, String subagentName) {
            this(name, output, success, -1, sessionId, subagentId, subagentName);
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
            String subagentName)
            implements AgentEvent {
        public UsageUpdated(Usage usage, int estimatedContextTokens, double contextPercent, String sessionId) {
            this(usage, estimatedContextTokens, contextPercent, 0, -1, null, sessionId, null, null);
        }
    }

    record Compacted(String kind, String mode, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {
        public Compacted(String kind, String mode, String sessionId) {
            this(kind, mode, sessionId, null, null);
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
            Level level, String message, String finishReason, String sessionId, String subagentId, String subagentName)
            implements AgentEvent {

        public enum Level {
            INFO,
            WARN,
            ERROR
        }

        public Notice(final Level level, final String message, final String sessionId) {
            this(level, message, null, sessionId, null, null);
        }

        public Notice(final Level level, final String message, final String finishReason, final String sessionId) {
            this(level, message, finishReason, sessionId, null, null);
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
     * @param llmCalls              number of LLM requests (UsageUpdated events) in this turn
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
}
