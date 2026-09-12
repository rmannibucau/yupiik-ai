package io.yupiik.ia.agent.core.agent;

import io.yupiik.ia.agent.core.llm.Usage;

public sealed interface AgentEvent {
    record TextDelta(String delta) implements AgentEvent {
    }

    record ThinkingDelta(String delta) implements AgentEvent {
    }

    record ToolStarted(String name, String args) implements AgentEvent {
    }

    record ToolFinished(String name, String output, boolean success) implements AgentEvent {
    }

    record ToolStream(String name, String fragment) implements AgentEvent {
    }

    record UsageUpdated(Usage usage, int estimatedContextTokens, double contextPercent) implements AgentEvent {
    }

    record Compacted(String kind, String mode) implements AgentEvent {

        public Compacted(String kind) {
            this(kind, "SUMMARY");
        }
    }

    record Status(String message) implements AgentEvent {
    }

    /**
     * A durable, user-visible non-conversation notice. Unlike {@link Status},
     * this is rendered as a distinct transcript line (not only the transient
     * status bar) so feedback survives and is understandable in all cases,
     * including failures that would otherwise be silent.
     */
    record Notice(Level level, String message) implements AgentEvent {

        public enum Level {
            INFO, WARN, ERROR
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

    record TurnDone(String content) implements AgentEvent {
    }

    record Error(String message) implements AgentEvent {
    }
}
