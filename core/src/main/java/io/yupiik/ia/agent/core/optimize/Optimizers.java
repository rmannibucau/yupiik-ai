package io.yupiik.ia.agent.core.optimize;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class Optimizers {
    private Optimizers() {
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]|\u001B\\][^\u0007]*\u0007");
    private static final Set<String> KNOWN_EXTENSIONS = Set.of("cmd", "exe", "sh");

    /**
     * Normalizes a command or processor alias for loose matching: strips './' (and '../') prefixes,
     * keeps only the file name, drops a trailing known extension (.cmd, .exe, .sh) and strips whitespace.
     */
    public static String normalizeCommand(final String name) {
        if (name == null) {
            return "";
        }
        var value = name.strip();
        while (value.startsWith("./") || value.startsWith("../")) {
            value = value.substring(2);
        }
        final var sep = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (sep >= 0) {
            value = value.substring(sep + 1);
        }
        final var dot = value.lastIndexOf('.');
        if (dot > 0 && KNOWN_EXTENSIONS.contains(value.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            value = value.substring(0, dot);
        }
        return value;
    }

    /**
     * @return true if the configured value loosely matches one of the aliases.
     */
    public static boolean matchesAlias(final String configured, final Collection<String> aliases) {
        final var normalized = normalizeCommand(configured);
        if (normalized.isEmpty()) {
            return false;
        }
        for (final var alias : aliases) {
            if (normalized.equalsIgnoreCase(normalizeCommand(alias))) {
                return true;
            }
        }
        return false;
    }

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
