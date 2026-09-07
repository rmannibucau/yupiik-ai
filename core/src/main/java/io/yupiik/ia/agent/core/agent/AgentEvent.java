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

    record Compacted(String kind) implements AgentEvent {
    }

    record Status(String message) implements AgentEvent {
    }

    record TurnDone(String content) implements AgentEvent {
    }

    record Error(String message) implements AgentEvent {
    }
}
