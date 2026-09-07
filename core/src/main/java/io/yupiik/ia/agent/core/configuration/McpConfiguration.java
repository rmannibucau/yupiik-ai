package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.List;

public record McpConfiguration(
        @Property(defaultValue = "true", documentation = "Enable MCP servers loading.") boolean enabled,
        @Property(defaultValue = "java.util.List.of()", documentation = "MCP servers to connect to.") List<McpServerConfiguration> servers,
        @Property(defaultValue = "60", documentation = "Timeout in seconds for a tools/call request.") int callTimeoutSeconds) {
}
