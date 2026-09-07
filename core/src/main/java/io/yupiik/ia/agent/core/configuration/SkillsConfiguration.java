package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record SkillsConfiguration(
        @Property(defaultValue = "true", documentation = "Load skills from the project (.opencode/skills, .claude/skills, .yca/skills, searched hierarchically up from the working directory) and from the global directories (~/.yupiik/coding-agent/skills, ~/.config/opencode/skills, ~/.claude/skills).") boolean enabled) {
}