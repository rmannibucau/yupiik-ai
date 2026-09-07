package io.yupiik.ia.agent.core.optimize;

import java.util.regex.Pattern;

public final class Optimizers {
    private Optimizers() {
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]|\u001B\\][^\u0007]*\u0007");

    public static String stripAnsi(final String s) {
        return s == null ? "" : ANSI.matcher(s).replaceAll("");
    }

    public static String firstToken(final String command) {
        final var trimmed = command.strip();
        final var space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    public static String baseName(final String path) {
        final var i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }

    public static boolean matchesHead(final String command, final String... names) {
        final var token = baseName(firstToken(command));
        for (final var n : names) {
            if (token.equals(n) || token.equals(n + ".cmd") || token.equals(n + ".bat")) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsFlag(final String command, final String... flags) {
        for (final var f : flags) {
            if (command.contains(f)) {
                return true;
            }
        }
        return false;
    }
}
