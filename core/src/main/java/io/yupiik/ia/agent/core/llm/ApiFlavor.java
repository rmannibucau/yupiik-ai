package io.yupiik.ia.agent.core.llm;

public enum ApiFlavor {
    AUTO, COMPLETIONS, RESPONSES;

    public static ApiFlavor fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final var norm = value.strip().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        for (final var v : values()) {
            if (v.name().equals(norm)) {
                return v;
            }
        }
        return AUTO;
    }
}
