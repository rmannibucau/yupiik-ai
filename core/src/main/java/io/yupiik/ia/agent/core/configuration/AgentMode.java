package io.yupiik.ia.agent.core.configuration;

public enum AgentMode {
    BUILD,
    PLAN;

    public static AgentMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return BUILD;
        }
        return "plan".equals(value.strip().toLowerCase()) ? PLAN : BUILD;
    }
}