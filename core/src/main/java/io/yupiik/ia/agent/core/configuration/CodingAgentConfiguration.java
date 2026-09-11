package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.nio.file.Path;
import java.util.List;

@RootConfiguration("yca")
public record CodingAgentConfiguration(
        @Property(defaultValue = "\"~/.yupiik/coding-agent\"", documentation = "Storage root for sessions, memory, todos and the global config file.") String storageHome,
        @Property(defaultValue = "\".\"", documentation = "Working directory from which project files (AGENTS.md hierarchy, skills) are discovered. Defaults to the current directory.") String workingDir,
        @Property(documentation = "Target project directory the current task is about (where project files should be created or read). Defaults to the working directory. When it points at a fresh/empty directory, the agent behaves like a neutral scaffolder: it does not index an unrelated code graph nor inject an unrelated repository README.") String projectDir,
        @Property(documentation = "OpenAI compatible provider settings. Either a list of providers (providers + defaultProvider) or the convenience flat scalars below acting as a single implicit 'default' provider.") ProviderListConfiguration provider,
        @Property(documentation = "Model overrides for compaction and pruning. Empty values fall back to the provider model.") ModelConfiguration model,
        @Property(documentation = "LLM client resilience settings: retries, rate limiting and concurrency.") LlmConfiguration llm,
        @Property(documentation = "Context compaction settings.") CompactionConfiguration compaction,
        @Property(documentation = "Context pruning settings (applied before compaction).") PruningConfiguration pruning,
        @Property(documentation = "Auto memory settings.") MemoryConfiguration memory,
        @Property(documentation = "Tool output optimization settings (rtk style token reduction).") OptimizeConfiguration optimize,
        @Property(documentation = "Tool permission settings.") PermissionsConfiguration permissions,
        @Property(documentation = "MCP integration settings.") McpConfiguration mcp,
        @Property(documentation = "Structural code graph settings (symbol/call index of the project).") StructuralCodeGraphConfiguration structuralCodeGraph,
        @Property(documentation = "Session retention settings (age based cleanup and compression).") SessionConfiguration sessions,
        @Property(documentation = "Skills loading settings.") SkillsConfiguration skills,
        @Property(defaultValue = "java.util.List.of()", documentation = "Custom agents. Each agent is mainly a prompt: inline text, or a file path whose content is used. Select one with --agent on run, /agent in the TUI, or a @<name> prompt prefix.") List<AgentConfiguration> agents,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.AgentMode.BUILD", documentation = "Agent access mode: build (all tools, any action) or plan (read-only tools only). Can be switched at runtime with /mode.") AgentMode mode,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig()", documentation = "Shared HTTP client settings used by webfetch, websearch and MCP servers over HTTP.") HttpClientConfiguration httpClient,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig()", documentation = "Agent tool settings (built-in tool server and web search).") ToolsConfiguration tools) {

    public ToolsConfiguration tools() {
        return tools == null ? ToolsConfiguration.defaultConfig() : tools;
    }

    public HttpClientConfiguration httpClient() {
        return httpClient == null ? HttpClientConfiguration.defaultConfig() : httpClient;
    }

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

    public Path resolveProjectDir() {
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).toAbsolutePath().normalize();
        }
        return resolveWorkingDir();
    }
}
