package io.yupiik.ia.agent.core.cli;

import java.util.Locale;

/**
 * Controls how much of the raw tool activity is shown in the CLI.
 * {@code COMPACT} collapses streaming tool output into progress markers and
 * human summaries; {@code VERBOSE} dims the full stream; {@code RAW} prints
 * tool calls and output verbatim (same as the legacy behaviour).
 */
public enum CliVerbosity {
    COMPACT,
    VERBOSE,
    RAW;

    public static CliVerbosity parse(final String value) {
        if (value == null || value.isBlank()) {
            return COMPACT;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "raw" -> RAW;
            case "verbose", "full" -> VERBOSE;
            default -> COMPACT;
        };
    }
}