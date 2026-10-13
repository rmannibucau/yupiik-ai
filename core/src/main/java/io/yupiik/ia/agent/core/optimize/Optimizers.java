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
package io.yupiik.ia.agent.core.optimize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class Optimizers {
    private Optimizers() {}

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

    public static String secondToken(final String command) {
        final var tokens = command.strip().split("\\s+");
        for (var i = 1; i < tokens.length; i++) {
            if (!tokens[i].startsWith("-")) {
                return tokens[i];
            }
        }
        return "";
    }

    /**
     * The third significant (non-flag) token: {@code gh pr view 42} -> {@code view},
     * {@code gh run list} -> {@code list}, {@code gh api} -> {@code ""}.
     */
    public static String thirdToken(final String command) {
        final var tokens = command.strip().split("\\s+");
        int found = 0;
        for (var i = 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("-")) {
                continue;
            }
            found++;
            if (found == 2) {
                return tokens[i];
            }
        }
        return "";
    }

    public static String baseName(final String path) {
        final var i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }

    /**
     * @return true when the first significant head token of {@code command} matches one of {@code names}
     *     (token-wise, after dropping env assignments and leading {@code cd <dir> &&} segments).
     */
    public static boolean matchesHeadToken(final String command, final String... names) {
        final var tokens = tokenize(command);
        var i = 0;
        boolean progressed;
        do {
            progressed = false;
            while (i < tokens.size() && isEnvAssignment(tokens.get(i))) {
                i++;
                progressed = true;
            }
            if (i + 1 < tokens.size() && "cd".equals(tokens.get(i)) && !"&&".equals(tokens.get(i + 1))) {
                i += 2;
                if (i < tokens.size() && "&&".equals(tokens.get(i))) {
                    i++;
                }
                progressed = true;
            }
        } while (progressed);
        if (i >= tokens.size()) {
            return false;
        }
        final var token = baseName(tokens.get(i));
        for (final var n : names) {
            if (token.equals(n) || token.equals(n + ".cmd") || token.equals(n + ".bat")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the first significant head token, or {@code null} when the command is empty.
     */
    public static String headToken(final String command) {
        final var tokens = tokenize(command);
        var i = 0;
        boolean progressed;
        do {
            progressed = false;
            while (i < tokens.size() && isEnvAssignment(tokens.get(i))) {
                i++;
                progressed = true;
            }
            if (i + 1 < tokens.size() && "cd".equals(tokens.get(i)) && !"&&".equals(tokens.get(i + 1))) {
                i += 2;
                if (i < tokens.size() && "&&".equals(tokens.get(i))) {
                    i++;
                }
                progressed = true;
            }
        } while (progressed);
        return i < tokens.size() ? baseName(tokens.get(i)) : null;
    }

    public static boolean matchesHead(final String command, final String... names) {
        final var tokens = tokenize(command);
        // skip a leading preamble made of env assignments (FOO=bar ...) and `cd <dir> &&` segments,
        // in any order and possibly repeated, eg. `cd /path && MY_FLAG=1 mvn ...`
        var i = 0;
        boolean progressed;
        do {
            progressed = false;
            while (i < tokens.size() && isEnvAssignment(tokens.get(i))) {
                i++;
                progressed = true;
            }
            if (i + 1 < tokens.size() && "cd".equals(tokens.get(i)) && !"&&".equals(tokens.get(i + 1))) {
                i += 2; // cd + its directory argument (quoted paths collapse to a single token)
                if (i < tokens.size() && "&&".equals(tokens.get(i))) {
                    i++;
                }
                progressed = true;
            }
        } while (progressed);
        if (i >= tokens.size()) {
            return false;
        }
        final var token = baseName(tokens.get(i));
        for (final var n : names) {
            if (token.equals(n) || token.equals(n + ".cmd") || token.equals(n + ".bat")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the command with a leading well-known wrapper chain stripped, so `timeout 120 mvn -o apply`,
     *     `sudo mvn ...`, `nohup git ...`, `env FOO=1 mvn ...`, `command mvn ...` or `sh -c "mvn ..."`
     *     all resolve to the inner command (e.g. `mvn -o apply`). Handled wrappers (chained and repeatable):
     *     {@code sudo}, {@code nohup}, {@code timeout <seconds>}, {@code env [VAR=..]}, {@code command} and
     *     {@code sh/bash/zsh/dash -c "..."}. Returns empty when the head is not a recognized wrapper or the
     *     unwrapped command is degenerate (a bare wrapper with nothing after it, or a {@code -c} multi-command
     *     script containing `;`, `&&` or `|`, which is left to the generic fallback).
     */
    public static Optional<String> unwrapCommandWrapper(final String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        return stripWrappers(tokenize(command));
    }

    private static Optional<String> stripWrappers(final List<String> tokens) {
        var i = 0;
        // skip a leading preamble made of env assignments and `cd <dir> &&` segments, like matchesHead
        boolean progressed;
        do {
            progressed = false;
            while (i < tokens.size() && isEnvAssignment(tokens.get(i))) {
                i++;
                progressed = true;
            }
            if (i + 1 < tokens.size() && "cd".equals(tokens.get(i)) && !"&&".equals(tokens.get(i + 1))) {
                i += 2;
                if (i < tokens.size() && "&&".equals(tokens.get(i))) {
                    i++;
                }
                progressed = true;
            }
        } while (progressed);
        if (i >= tokens.size()) {
            return Optional.empty();
        }
        final var head = baseName(tokens.get(i));
        return switch (head) {
            case "sudo", "nohup", "command" -> {
                final var j = skipFlags(tokens, i + 1);
                if (j >= tokens.size()) {
                    yield Optional.empty(); // bare wrapper with nothing after it
                }
                final var rest = tokens.subList(j, tokens.size());
                yield stripWrappers(rest).or(() -> Optional.of(String.join(" ", rest)));
            }
            case "env" -> {
                var j = i + 1;
                while (j < tokens.size() && (tokens.get(j).startsWith("-") || isEnvAssignment(tokens.get(j)))) {
                    j++;
                }
                if (j >= tokens.size()) {
                    yield Optional.empty();
                }
                final var rest = tokens.subList(j, tokens.size());
                yield stripWrappers(rest).or(() -> Optional.of(String.join(" ", rest)));
            }
            case "timeout" -> {
                final var j = skipFlags(tokens, i + 1);
                // a bare numeric duration follows the (flag-less) wrapper; a non-numeric token
                // is not a duration, so `timeout` here is an actual command being invoked
                if (j >= tokens.size() || !tokens.get(j).matches("\\d+")) {
                    yield Optional.empty();
                }
                final var after = j + 1;
                if (after >= tokens.size()) {
                    yield Optional.empty(); // `timeout <duration>` with no command
                }
                final var rest = tokens.subList(after, tokens.size());
                yield stripWrappers(rest).or(() -> Optional.of(String.join(" ", rest)));
            }
            case "sh", "bash", "zsh", "dash" -> {
                if (i + 2 < tokens.size() && "-c".equals(tokens.get(i + 1))) {
                    final var script = tokens.get(i + 2);
                    if (script.contains(";") || script.contains("&&") || script.contains("|")) {
                        // multi-command script: leave the whole thing to the generic fallback
                        yield Optional.empty();
                    }
                    final var inner = tokenize(script);
                    if (inner.isEmpty()) {
                        yield Optional.empty();
                    }
                    yield stripWrappers(inner).or(() -> Optional.of(String.join(" ", inner)));
                }
                yield Optional.empty(); // sh without -c: not acting as a wrapper
            }
            default -> Optional.empty(); // not a recognized wrapper: the head is the command itself
        };
    }

    private static int skipFlags(final List<String> tokens, final int start) {
        var j = start;
        while (j < tokens.size() && tokens.get(j).startsWith("-")) {
            j++;
        }
        return j;
    }

    private static boolean isEnvAssignment(final String token) {
        return token.matches("[A-Za-z_][A-Za-z0-9_]*=.*");
    }

    /**
     * Splits a command line into tokens, keeping double- and single-quoted segments together
     * (quotes themselves are dropped).
     */
    private static List<String> tokenize(final String command) {
        final var tokens = new ArrayList<String>();
        if (command == null) {
            return tokens;
        }
        final var sb = new StringBuilder();
        char quote = 0;
        var started = false;
        for (var idx = 0; idx < command.length(); idx++) {
            final var c = command.charAt(idx);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    sb.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(sb.toString());
                    sb.setLength(0);
                    started = false;
                }
            } else {
                sb.append(c);
                started = true;
            }
        }
        if (started) {
            tokens.add(sb.toString());
        }
        return tokens;
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
