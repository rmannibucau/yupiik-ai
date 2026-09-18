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
    record TextDelta(String delta) implements AgentEvent {}

    /**
     * The streaming buffers must be discarded: the interrupted stream is retried from scratch and
     * the fragments received before the failure would otherwise duplicate the retried reply prefix.
     */
    record TextReset() implements AgentEvent {}

    record ThinkingDelta(String delta) implements AgentEvent {}

    record ToolStarted(String name, String args) implements AgentEvent {}

    record ToolFinished(String name, String output, boolean success) implements AgentEvent {}

    record ToolStream(String name, String fragment) implements AgentEvent {}

    record UsageUpdated(Usage usage, int estimatedContextTokens, double contextPercent) implements AgentEvent {}

    record Compacted(String kind, String mode) implements AgentEvent {

        public Compacted(String kind) {
            this(kind, "SUMMARY");
        }
    }

    record Status(String message) implements AgentEvent {}

    /**
     * A durable, user-visible non-conversation notice. Unlike {@link Status},
     * this is rendered as a distinct transcript line (not only the transient
     * status bar) so feedback survives and is understandable in all cases,
     * including failures that would otherwise be silent.
     */
    record Notice(Level level, String message, String finishReason) implements AgentEvent {

        public enum Level {
            INFO,
            WARN,
            ERROR
        }

        public Notice(final Level level, final String message) {
            this(level, message, null);
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

    record TurnDone(String content) implements AgentEvent {}

    record Error(String message) implements AgentEvent {}
}
