package io.yupiik.ia.agent.tui;

import java.util.List;

/**
 * Pure input helpers of the TUI: slash command registry, word motions and
 * first-line extraction. Extracted from the TUI command so they can be unit
 * tested without a terminal.
 */
public final class TuiInputSupport {
    public static final List<String> SLASH_COMMANDS = List.of(
            "/help", "/shortcuts", "/new", "/resume", "/compact", "/model", "/provider", "/mode", "/memory", "/context",
            "/permissions", "/structural-code-graph", "/tools", "/agent", "/clear", "/quit", "/exit");

    private TuiInputSupport() {
    }

    public static int nextWord(final String s, final int col) {
        final var len = s.length();
        var i = col;
        while (i < len && s.charAt(i) == ' ') {
            i++;
        }
        while (i < len && s.charAt(i) != ' ') {
            i++;
        }
        return Math.min(i, len);
    }

    public static int previousWord(final String s, final int col) {
        var i = Math.min(col, s.length());
        if (i > 0) {
            i--;
        }
        while (i >= 0 && s.charAt(i) == ' ') {
            i--;
        }
        while (i >= 0 && s.charAt(i) != ' ') {
            i--;
        }
        return Math.max(0, i + 1);
    }

    public static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var idx = text.indexOf('\n');
        final var line = idx < 0 ? text : text.substring(0, idx);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }
}
