package io.yupiik.ia.agent.core.cli.slash;

public record SlashCommandSpec(String name, String usage, String description, SlashGroup group) {

    public SlashCommandSpec {
        if (group == null) {
            throw new IllegalArgumentException("group is required for " + name);
        }
    }

    public String display() {
        return usage == null || usage.isBlank() ? name : name + " " + usage;
    }
}