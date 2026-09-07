package io.yupiik.ia.agent.core.configuration;

public enum ReasoningField {
    AUTO,
    REASONING,
    REASONING_CONTENT,
    NONE;

    public static ReasoningField fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.strip().toLowerCase().replace('-', '_')) {
            case "reasoning" -> REASONING;
            case "reasoning_content" -> REASONING_CONTENT;
            case "none" -> NONE;
            default -> AUTO;
        };
    }
}
