package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record AgentConfiguration(
        @Property(required = true, documentation = "Unique name used to select this agent (/agent <name>, --agent <name>, or a @<name> prompt prefix).") String name,
        @Property(required = true, documentation = "Agent instructions: inline text, or a file path whose content is loaded (eg. ~/.yupiik/coding-agent/agents/reviewer.md). A value is treated as a file path when it expands to an existing regular file, otherwise it is used as inline text.") String prompt) {
}