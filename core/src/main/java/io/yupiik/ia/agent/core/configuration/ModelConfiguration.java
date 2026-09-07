package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record ModelConfiguration(
        @Property(defaultValue = "\"\"", documentation = "Model used to compact the context. Defaults to the provider model when empty.") String compact,
        @Property(defaultValue = "\"\"", documentation = "Model used to prune the context. Defaults to the provider model when empty.") String prune) {
}
