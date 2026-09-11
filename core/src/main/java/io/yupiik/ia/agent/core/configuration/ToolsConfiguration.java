package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record ToolsConfiguration(
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.BuiltinToolsConfiguration.defaultConfig()", documentation = "Embedded (built-in) tools settings.") BuiltinToolsConfiguration builtin,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.WebSearchConfiguration.defaultConfig()", documentation = "Web search tool settings.") WebSearchConfiguration websearch) {

    public static ToolsConfiguration defaultConfig() {
        return new ToolsConfiguration(BuiltinToolsConfiguration.defaultConfig(), WebSearchConfiguration.defaultConfig());
    }
}