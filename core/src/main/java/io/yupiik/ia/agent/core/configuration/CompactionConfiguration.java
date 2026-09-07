package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record CompactionConfiguration(
        @Property(defaultValue = "true", documentation = "Automatically compact the context when the threshold is reached.") boolean enabled,
        @Property(defaultValue = "80", documentation = "Context window usage percentage triggering the compaction.") int thresholdPercent) {
}
