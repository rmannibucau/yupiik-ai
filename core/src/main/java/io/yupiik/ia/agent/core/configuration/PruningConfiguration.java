package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record PruningConfiguration(
        @Property(defaultValue = "true", documentation = "Automatically prune the context (cheaper than compaction, applied first).") boolean enabled,
        @Property(defaultValue = "65", documentation = "Context window usage percentage triggering the pruning.") int thresholdPercent,
        @Property(defaultValue = "6", documentation = "Number of most recent messages never touched by the pruning.") int keepRecent,
        @Property(defaultValue = "80", documentation = "Maximum characters kept for a pruned tool output (head and tail).") int toolOutputMaxChars) {
}
