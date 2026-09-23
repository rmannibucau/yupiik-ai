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

public sealed interface AgentEvent {
    record TextDelta(String delta, String subagentId, String subagentName) implements AgentEvent {
        public TextDelta(String delta) {
            this(delta, null, null);
        }
    }

    /**
     * The streaming buffers must be discarded: the interrupted stream is retried from scratch and
     * the fragments received before the failure would otherwise duplicate the retried reply prefix.
     */
    record TextReset(String subagentId, String subagentName) implements AgentEvent {
        public TextReset() {
            this(null, null);
        }
    }

    record ThinkingDelta(String delta, String subagentId, String subagentName) implements AgentEvent {
        public ThinkingDelta(String delta) {
            this(delta, null, null);
        }
    }

    record ToolStarted(String name, String args, String subagentId, String subagentName) implements AgentEvent {
        public ToolStarted(String name, String args) {
            this(name, args, null, null);
        }
    }

    record ToolFinished(String name, String output, boolean success, String subagentId, String subagentName)
            implements AgentEvent {
        public ToolFinished(String name, String output, boolean success) {
            this(name, output, success, null, null);
        }
    }

    record ToolStream(String name, String fragment, String subagentId, String subagentName) implements AgentEvent {
        public ToolStream(String name, String fragment) {
            this(name, fragment, null, null);
        }
    }

    record UsageUpdated(
            Usage usage, int estimatedContextTokens, double contextPercent, String subagentId, String subagentName)
            implements AgentEvent {
        public UsageUpdated(Usage usage, int estimatedContextTokens, double contextPercent) {
            this(usage, estimatedContextTokens, contextPercent, null, null);
        }
    }

    record Compacted(String kind, String mode, String subagentId, String subagentName) implements AgentEvent {
        public Compacted(String kind) {
            this(kind, "SUMMARY", null, null);
        }

        public Compacted(String kind, String mode) {
            this(kind, mode, null, null);
        }
    }

    record Status(String message, String subagentId, String subagentName) implements AgentEvent {
        public Status(String message) {
            this(message, null, null);
        }
    }

    /**
     * A durable, user-visible non-conversation notice. Unlike {@link Status},
     * this is rendered as a distinct transcript line (not only the transient
     * status bar) so feedback survives and is understandable in all cases,
     * including failures that would otherwise be silent.
     */
    record Notice(Level level, String message, String finishReason, String subagentId, String subagentName)
            implements AgentEvent {

        public enum Level {
            INFO,
            WARN,
            ERROR
        }

        public Notice(final Level level, final String message) {
            this(level, message, null, null, null);
        }

        public Notice(final Level level, final String message, final String finishReason) {
            this(level, message, finishReason, null, null);
        }

        public static Notice info(final String message) {
            return new Notice(Level.INFO, message);
        }

        public static Notice warn(final String message) {
            return new Notice(Level.WARN, message);
        }

        public static Notice error(final String message) {
            return new Notice(Level.ERROR, message);
        }
    }

    record TurnDone(String content, String subagentId, String subagentName) implements AgentEvent {
        public TurnDone(String content) {
            this(content, null, null);
        }
    }

    record Error(String message, String subagentId, String subagentName) implements AgentEvent {
        public Error(String message) {
            this(message, null, null);
        }
    }

    /**
     * A queued prompt was injected into the running turn before it completed, so
     * the user does not have to wait for the current turn to finish.
     */
    record PromptIntegrated(String text, String subagentId, String subagentName) implements AgentEvent {
        public PromptIntegrated(String text) {
            this(text, null, null);
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
     */
    record TurnMetrics(
            int llmCalls,
            long thinkingDurationMs,
            long toolDurationSumMs,
            long toolDurationWallMs,
            int toolsRun,
            long turnDurationMs,
            String subagentId,
            String subagentName)
            implements AgentEvent {

        public TurnMetrics(
                int llmCalls,
                long thinkingDurationMs,
                long toolDurationSumMs,
                long toolDurationWallMs,
                int toolsRun,
                long turnDurationMs) {
            this(
                    llmCalls,
                    thinkingDurationMs,
                    toolDurationSumMs,
                    toolDurationWallMs,
                    toolsRun,
                    turnDurationMs,
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
            String subagentId,
            String subagentName)
            implements AgentEvent {

        public ToolTimingEvent(String toolName, long durationMs, long wallDurationMs, boolean parallelBatch) {
            this(toolName, durationMs, wallDurationMs, parallelBatch, null, null);
        }
    }
}
