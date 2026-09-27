/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.tui;

import io.yupiik.ia.agent.core.cli.ToolPresenter;
import java.util.regex.Pattern;

/**
 * Parses a tool region of the transcript (the markdown emitted by
 * {@link TuiTranscriptModel} between its {@code TOOL_BEGIN}/{@code TOOL_END}
 * markers) into structured fields used to render a bordered "tool card":
 * the tool name, its command/arguments (or the error message when it failed),
 * the captured output, the wall-clock duration in milliseconds and the
 * {@code #N} ordinal embedded by {@code ConversationTranscript} (used to key
 * full outputs, {@code -1} when absent).
 */
public record ToolCard(
        String name, String args, String output, boolean failed, long elapsedMs, boolean running, long ordinal) {
    private static final Pattern DURATION = Pattern.compile("\\(\\s*(?:#(\\d+)\\s*,\\s*)?(\\d+)\\s*ms\\s*\\)");
    private static final int KEY_COMMAND_MAX = 48;

    public static ToolCard parse(final String regionText) {
        final var text = regionText.strip();
        final var newline = text.indexOf('\n');
        final var header = newline < 0 ? text : text.substring(0, newline);
        final var failed = header.contains(" failed: ");
        // live running block from ConversationTranscript.markdown(): "`<spinner> running <command>`"
        if (!failed && header.startsWith("`")) {
            final var content = header.endsWith("`") ? header.substring(1, header.length() - 1) : header.substring(1);
            final var idx = content.indexOf(" running ");
            final var command =
                    idx >= 0 ? content.substring(idx + " running ".length()).strip() : content.strip();
            return new ToolCard(command, "", extractCode(text), false, 0, true, -1);
        }
        final var name = nameOf(header);
        final var argsAndDuration = failed ? failedArgs(header) : successArgs(header);
        return new ToolCard(
                name,
                argsAndDuration.args(),
                extractCode(text),
                failed,
                argsAndDuration.elapsed(),
                false,
                argsAndDuration.ordinal());
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
            return new Args("", 0, -1);
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

    /** Removes a trailing {@code (#NNN, NNN ms)} then any trailing asterisk from an args/error string. */
    private static Args stripDuration(final String raw) {
        final var initial = raw.strip();
        var rest = initial;
        long elapsed = 0;
        long ordinal = -1;
        final var open = rest.lastIndexOf('(');
        if (open >= 0 && rest.endsWith(")")) {
            final var matcher = DURATION.matcher(rest.substring(open));
            if (matcher.matches()) {
                if (matcher.group(1) != null) {
                    try {
                        ordinal = Long.parseLong(matcher.group(1));
                    } catch (final NumberFormatException e) {
                        ordinal = -1;
                    }
                }
                try {
                    elapsed = Long.parseLong(matcher.group(2));
                } catch (final NumberFormatException e) {
                    elapsed = 0;
                }
                rest = rest.substring(0, open).strip();
            }
        }
        rest = rest.replaceAll("\\*$", "").strip();
        if (rest.startsWith(":")) {
            rest = rest.substring(1).strip();
        }
        return new Args(rest, elapsed, ordinal);
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
    public boolean isDiff(final DiffRenderer diffRenderer) {
        return diffRenderer.looksLikeDiff(output);
    }

    /**
     * Short, significant command marker for the condensed rendering: the
     * meaningful tail of a compound command line (eg. {@code cd /foo && ls -la}
     * -> {@code ls -la}) instead of the full command. Running cards carry the
     * raw command as name (args are not parsed yet); failed cards carry the
     * error first line. Blank when there is nothing worth showing.
     */
    public String keyCommand() {
        return keyCommand(null);
    }

    /**
     * Like {@link #keyCommand()} but with a transform applied to the significant
     * command *before* the display-length clip, so shortening (e.g. relativizing
     * an absolute path) sees the untruncated value {@code beforeClip}.
     */
    public String keyCommand(final java.util.function.Function<String, String> beforeClip) {
        final var raw = args.isBlank() && running ? name : args;
        var significant = ToolPresenter.significantCommand(raw);
        if (beforeClip != null) {
            significant = beforeClip.apply(significant);
        }
        if (significant.length() <= KEY_COMMAND_MAX) {
            return significant;
        }
        return significant.substring(0, KEY_COMMAND_MAX) + "...";
    }

    private record Args(String args, long elapsed, long ordinal) {}
}
