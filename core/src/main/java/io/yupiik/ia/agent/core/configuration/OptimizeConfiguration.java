package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.List;

public record OptimizeConfiguration(
        @Property(defaultValue = "true", documentation = "Enable the rtk style tool output optimization.") boolean enabled,
        @Property(defaultValue = "400", documentation = "Maximum number of lines kept by the generic fallback processor.") int genericMaxLines,
        @Property(defaultValue = "20", documentation = "Maximum number of file/test entries listed by the processors before truncation.") int maxEntries,
        @Property(documentation = "Processor names to disable (maven, gradle, git, gh, npm, prettier, generic). Comma separated.") List<String> disabled) {

    public boolean isDisabled(final String name) {
        return disabled != null && disabled.stream().anyMatch(d -> d != null && d.equalsIgnoreCase(name));
    }
}
