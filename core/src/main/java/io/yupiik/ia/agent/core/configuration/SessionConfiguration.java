package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record SessionConfiguration(
        @Property(defaultValue = "30", documentation = "Sessions inactive longer than this many days are dropped at startup (0 disables the cleanup).") int retentionDays,
        @Property(defaultValue = "15", documentation = "Sessions inactive longer than this many days are gzip compressed at startup (0 disables compression).") int compressDays) {
}