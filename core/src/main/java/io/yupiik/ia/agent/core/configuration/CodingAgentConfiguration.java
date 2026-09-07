package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.nio.file.Path;

@RootConfiguration("yca")
public record CodingAgentConfiguration(
        @Property(defaultValue = "\"~/.yupiik/coding-agent\"", documentation = "Storage root for sessions, memory, todos and the global config file.") String storageHome,
        @Property(defaultValue = "\".\"", documentation = "Working directory from which project files (AGENTS.md hierarchy, skills) are discovered. Defaults to the current directory.") String workingDir,
        @Property(documentation = "OpenAI compatible provider settings.") ProviderConfiguration provider,
        @Property(documentation = "Model overrides for compaction and pruning. Empty values fall back to the provider model.") ModelConfiguration model,
        @Property(documentation = "LLM client resilience settings: retries, rate limiting and concurrency.") LlmConfiguration llm,
        @Property(documentation = "Context compaction settings.") CompactionConfiguration compaction,
        @Property(documentation = "Context pruning settings (applied before compaction).") PruningConfiguration pruning,
        @Property(documentation = "Auto memory settings.") MemoryConfiguration memory,
        @Property(documentation = "Tool output optimization settings (rtk style token reduction).") OptimizeConfiguration optimize,
        @Property(documentation = "Tool permission settings.") PermissionsConfiguration permissions,
        @Property(documentation = "MCP integration settings.") McpConfiguration mcp,
        @Property(documentation = "Session retention settings (age based cleanup and compression).") SessionConfiguration sessions,
        @Property(documentation = "Skills loading settings.") SkillsConfiguration skills,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.AgentMode.BUILD", documentation = "Agent access mode: build (all tools, any action) or plan (read-only tools only). Can be switched at runtime with /mode.") AgentMode mode) {

    public String resolveStorageHome() {
        var home = storageHome == null || storageHome.isBlank() ? "~/.yupiik/coding-agent" : storageHome;
        if (home.startsWith("~/")) {
            home = System.getProperty("user.home") + home.substring(1);
        }
        return home;
    }

    public Path resolveWorkingDir() {
        final var value = workingDir == null || workingDir.isBlank() ? "." : workingDir;
        return Path.of(value).toAbsolutePath().normalize();
    }
}
