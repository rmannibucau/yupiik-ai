package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.ia.agent.core.optimize.Optimizers;

import java.util.Collection;
import java.util.List;

public record OptimizeConfiguration(
        @Property(defaultValue = "true", documentation = "Enable the rtk style tool output optimization.") boolean enabled,
        @Property(defaultValue = "400", documentation = "Maximum number of lines kept by the generic fallback processor.") int genericMaxLines,
        @Property(defaultValue = "20", documentation = "Maximum number of file/test entries listed by the processors before truncation.") int maxEntries,
        @Property(defaultValue = "java.util.List.of()", documentation = "Processor or tool names to disable. Processors: mvn, mvnd, mvnw, gradle, gradlew, git, gh, npm, pnpm, prettier, generic. Matched loosely: './' and path prefixes are dropped, only the file name is kept, known extensions (.cmd, .exe, .sh) are ignored and comparison is case insensitive.") List<String> disabled) {

    public boolean isDisabled(final Collection<String> names) {
        if (disabled == null || disabled.isEmpty()) {
            return false;
        }
        for (final var value : disabled) {
            if (Optimizers.matchesAlias(value, names)) {
                return true;
            }
        }
        return false;
    }
}
