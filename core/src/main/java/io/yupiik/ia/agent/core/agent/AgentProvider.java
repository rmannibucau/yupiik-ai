package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class AgentProvider {
    private static final int MAX_PROMPT_LENGTH = 8000;

    private final CodingAgentConfiguration configuration;

    public AgentProvider(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected AgentProvider() {
        this.configuration = null;
    }

    public List<AgentConfiguration> agents() {
        final var agents = configuration.agents();
        return agents == null ? List.of() : agents;
    }

    public AgentConfiguration byName(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (final var agent : agents()) {
            if (name.equals(agent.name())) {
                return agent;
            }
        }
        return null;
    }

    /**
     * Inflates the agent prompt. A value is treated as a file path when it expands to an existing
     * regular file; otherwise it is used as inline text. Returns null when the agent is unknown.
     */
    public String resolvePrompt(final AgentConfiguration agent) {
        final var raw = agent.prompt() == null ? "" : agent.prompt();
        if (raw.isBlank()) {
            return "";
        }
        final var path = expandPath(raw);
        if (Files.isRegularFile(path)) {
            try {
                var content = Files.readString(path);
                if (content.length() > MAX_PROMPT_LENGTH) {
                    content = content.substring(0, MAX_PROMPT_LENGTH) + "\n[...truncated...]";
                }
                return content;
            } catch (final IOException e) {
                return "Agent prompt file unreadable: " + path;
            }
        }
        return raw.length() > MAX_PROMPT_LENGTH ? raw.substring(0, MAX_PROMPT_LENGTH) : raw;
    }

    private static Path expandPath(final String value) {
        var result = value.strip();
        if (result.startsWith("~/")) {
            result = System.getProperty("user.home") + result.substring(1);
        }
        return Path.of(result).toAbsolutePath().normalize();
    }
}