package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record BuiltinToolsConfiguration(
        @Property(defaultValue = "true", documentation = "Register the embedded local tool server (bash, read_file, write_file, edit_file, glob, grep, list_dir, todo_write, ask_user, memory_write, webfetch, websearch, apply_patch, skill, ...). Set to false to expose tools only through configured MCP servers.") boolean enabled) {

    public static BuiltinToolsConfiguration defaultConfig() {
        return new BuiltinToolsConfiguration(true);
    }
}