package io.yupiik.ia.agent.tui;

/**
 * Parses a tool region of the transcript (the markdown emitted by
 * {@link TuiTranscriptModel} between its {@code TOOL_BEGIN}/{@code TOOL_END}
 * markers) into structured fields used to render a bordered "tool card":
 * the tool name, its command/arguments (or the error message when it failed),
 * the captured output and the wall-clock duration in milliseconds.
 */
public record ToolCard(String name, String args, String output, boolean failed, long elapsedMs) {
    public static ToolCard parse(final String regionText) {
        final var text = regionText.strip();
        final var newline = text.indexOf('\n');
        final var header = newline < 0 ? text : text.substring(0, newline);
        final var failed = header.contains(" failed: ");
        final var name = nameOf(header);
        final var argsAndDuration = failed ? failedArgs(header) : successArgs(header);
        return new ToolCard(name, argsAndDuration.args(), extractCode(text), failed, argsAndDuration.elapsed());
    }

    private static String nameOf(final String header) {
        final int bt = header.indexOf('`');
        final int et = bt >= 0 ? header.indexOf('`', bt + 1) : -1;
        return et > bt ? header.substring(bt + 1, et) : "tool";
    }

    private static Args successArgs(final String header) {
        final int bt = header.indexOf('`');
        final int et = bt >= 0 ? header.indexOf('`', bt + 1) : -1;
        if (et <= bt) {
            return new Args("", 0);
        }
        var rest = header.substring(et + 1).strip();
        if (rest.startsWith(":")) {
            rest = rest.substring(1).strip();
        }
        return stripDuration(rest);
    }

    private static Args failedArgs(final String header) {
        final var idx = header.indexOf(" failed: ");
        if (idx < 0) {
            return stripDuration(header);
        }
        return stripDuration(header.substring(idx + " failed: ".length()).strip());
    }

    /** Removes a trailing {@code (NNN ms)} then any trailing asterisk from an args/error string. */
    private static Args stripDuration(final String raw) {
        final var initial = raw.strip();
        var rest = initial;
        long elapsed = 0;
        final var open = rest.lastIndexOf('(');
        if (open >= 0 && rest.endsWith(")") && rest.substring(open).matches("\\(\\s*\\d+\\s*ms\\s*\\)")) {
            final var inner = rest.substring(open + 1, rest.length() - 1).replaceAll("[^0-9]", "");
            try {
                elapsed = Long.parseLong(inner);
            } catch (final NumberFormatException e) {
                elapsed = 0;
            }
            rest = rest.substring(0, open).strip();
        }
        rest = rest.replaceAll("\\*$", "").strip();
        if (rest.startsWith(":")) {
            rest = rest.substring(1).strip();
        }
        return new Args(rest, elapsed);
    }

    private static String extractCode(final String text) {
        final var fence = "```text\n";
        final var start = text.indexOf(fence);
        if (start < 0) {
            return "";
        }
        final var from = start + fence.length();
        var end = text.indexOf("\n```", from);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(from, end).stripTrailing();
    }

    /** True when the captured output looks like a git/udiff patch. */
    public boolean isDiff() {
        var hunks = 0;
        var changed = 0;
        for (final var line : output.split("\n")) {
            final var s = line.stripLeading();
            if (s.startsWith("@@")) {
                hunks++;
            } else if (s.startsWith("+") || s.startsWith("-")) {
                changed++;
            }
        }
        return changed >= 2 || (hunks >= 1 && changed >= 1);
    }

    private record Args(String args, long elapsed) {
    }
}