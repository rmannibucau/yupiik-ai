package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record MemoryConfiguration(
        @Property(defaultValue = "true", documentation = "Automatically extract durable knowledge from conversations into memory files.") boolean enabled,
        @Property(defaultValue = "8000", documentation = "Maximum number of characters of memory injected in the system prompt.") int maxChars) {
}
