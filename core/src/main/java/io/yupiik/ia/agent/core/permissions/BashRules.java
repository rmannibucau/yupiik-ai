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
package io.yupiik.ia.agent.core.permissions;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandOptionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles the {@code yca.permissions.bash.rules} configuration into fast matchers and evaluates
 * bash commands against them.
 *
 * <p>The command is split into segments on {@code &&}, {@code ||}, {@code ;}, {@code |}, single {@code &}
 * and newlines with a quote aware tokenizer (line continuations and pipelines spanning several lines are
 * supported). Every segment must match a rule resolving to {@code all}/{@code yolo} for the active agent
 * mode for the whole command to be auto-allowed; redirections, command substitutions and excluded options
 * (eg. {@code sed -i}) make the segment fall back to the normal ask/gate flow.</p>
 */
public final class BashRules {

    /**
     * Result of a command evaluation: whether the command is auto-allowed, its arity-style prefix and,
     * when denied, the first segment that was not auto-allowed plus {@link Cause why}.
     */
    public record Evaluation(boolean allowed, String prefix, String deniedSegment, Cause deniedCause) {}

    /** Why a bash segment was not auto-allowed by the rules, used to explain approval prompts and denials. */
    public enum Cause {
        /** No configured rule matched the segment (eg. {@code git add}). */
        NO_MATCH,
        /** A rule matched but resolves to interactive approval (ask/auto_edit) in the active mode. */
        NOT_YOLO,
        /** The segment carries a redirection or a command substitution; never auto-allowed. */
        REDIRECT_OR_SUBSTITUTION
    }

    private BashRules() {
        // no-op
    }

    public static Compiled compile(final BashPermissionsConfiguration configuration) {
        if (configuration == null
                || configuration.rules() == null
                || configuration.rules().isEmpty()) {
            return new Compiled(List.of());
        }
        final var rules = new ArrayList<CompiledRule>();
        for (final CommandPermissionConfiguration rule : configuration.rules()) {
            if (rule == null || rule.matcher() == null || rule.matcher().commandStart() == null) {
                continue;
            }
            final var commandStart = rule.matcher().commandStart().normalizedOptions();
            if (commandStart.isEmpty()) {
                continue;
            }
            rules.add(new CompiledRule(
                    commandStart, compileExcludes(rule.matcher().excludeOptions()), rule.plan(), rule.defaultMode()));
        }
        return new Compiled(rules);
    }

    private static Excludes compileExcludes(final List<CommandOptionsConfiguration> excludeOptions) {
        final var exact = new HashSet<String>();
        final var longOptions = new ArrayList<String>();
        final var sequences = new ArrayList<List<String>>();
        if (excludeOptions != null) {
            for (final CommandOptionsConfiguration unit : excludeOptions) {
                if (unit == null) {
                    continue;
                }
                final var options = unit.normalizedOptions();
                if (options.isEmpty()) {
                    continue;
                }
                if (options.size() == 1) {
                    final var option = options.get(0);
                    if (option.startsWith("--")) {
                        longOptions.add(option);
                    } else {
                        exact.add(option);
                    }
                } else {
                    sequences.add(options);
                }
            }
        }
        return new Excludes(exact, longOptions, sequences);
    }

    public static final class Compiled {
        private static final Set<String> TOLERATED_REDIRECTS = Set.of("2>/dev/null", ">/dev/null", "2>&1");

        private final List<CompiledRule> rules;

        private Compiled(final List<CompiledRule> rules) {
            this.rules = rules;
        }

        /**
         * @param agentMode the active agent mode, selecting the {@code plan} or {@code default} value of each rule.
         * @param command the raw bash command.
         * @return whether every segment of the command is auto-allowed by a rule resolving to {@code all}/{@code yolo},
         * plus the arity-style command prefix (matched rule {@code commandStart}) used to key session grants.
         */
        public Evaluation evaluate(final AgentMode agentMode, final String command) {
            if (command == null || command.isBlank()) {
                return new Evaluation(true, null, null, null);
            }
            final var segments = tokenize(command);
            var allowed = true;
            var matchedSegments = 0;
            var matchedStart = (String) null;
            String deniedSegment = null;
            Cause deniedCause = null;
            for (final var segment : segments) {
                final var decision = evaluateSegment(segment, agentMode);
                if (!decision.allowed()) {
                    allowed = false;
                    if (deniedSegment == null) {
                        deniedSegment = join(segment);
                        deniedCause = decision.cause();
                    }
                }
                if (decision.matchedStart() != null) {
                    matchedSegments++;
                    if (matchedStart == null) {
                        matchedStart = decision.matchedStart();
                    }
                }
            }
            // the fine grained prefix is only meaningful for plain single segment commands: a compound
            // command approved once must not silently unlock its other segments
            return new Evaluation(
                    allowed,
                    segments.size() == 1 && matchedSegments == 1 ? matchedStart : null,
                    deniedSegment,
                    deniedCause);
        }

        private static String join(final Segment segment) {
            final var sb = new StringBuilder();
            for (final var token : segment.tokens()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(token.text());
            }
            return sb.toString();
        }

        /** Command segments (quote aware, operators stripped) as normalized text, for allow/deny pattern matching. */
        public List<String> segments(final String command) {
            if (command == null || command.isBlank()) {
                return List.of();
            }
            final var segments = new ArrayList<String>();
            for (final var segment : tokenize(command)) {
                final var sb = new StringBuilder();
                for (final var token : segment.tokens()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(token.text());
                }
                if (sb.length() > 0) {
                    segments.add(sb.toString());
                }
            }
            return segments;
        }

        /**
         * Exploration-coverage keys of a bash command for the plateau detector: one key per segment,
         * {@code <matched-rule-prefix>:<target>} where the prefix comes from the configured bash rules
         * ({@code yca.permissions.bash.rules} {@code commandStart}, so a rule added there - eg.
         * {@code jq} - is automatically covered instead of duplicating a hardcoded command list) and
         * the target is the last non-flag, non-redirected path-like token of the segment. Commands
         * with no matching rule, pure env assignments, {@code cd} and shell wrappers yield no key
         * (they are never plateau signals). The tokenizer (quote aware, operator segmentation) is the
         * same one used for permission evaluation.
         */
        public List<String> coverageKeys(final String command) {
            if (command == null || command.isBlank()) {
                return List.of();
            }
            final var keys = new ArrayList<String>(1);
            for (final var segment : tokenize(command)) {
                final var effective = new ArrayList<Token>(segment.tokens().size());
                for (final var token : segment.tokens()) {
                    if (!TOLERATED_REDIRECTS.contains(token.text())) {
                        effective.add(token);
                    }
                }
                if (effective.isEmpty()) {
                    continue;
                }
                var i = 0;
                while (i < effective.size() && isEnvAssignment(effective.get(i).text())) {
                    i++;
                }
                if (i >= effective.size()) {
                    continue;
                }
                final var head = effective.get(i).text();
                if ("cd".equals(head)) {
                    continue;
                }
                final var tokens = new ArrayList<String>(effective.size() - i);
                var skipNext = false;
                for (var j = i; j < effective.size(); j++) {
                    final var token = effective.get(j);
                    if (token.redirect() || token.substitution()) {
                        skipNext = true; // the redirect operator AND its target are not coverage
                        continue;
                    }
                    if (skipNext) {
                        skipNext = false;
                        continue;
                    }
                    tokens.add(token.text());
                }
                if (tokens.isEmpty()) {
                    continue;
                }
                if (isShellCommand(tokens.get(0))) {
                    continue;
                }
                final var prefix = coveragePrefix(tokens);
                if (prefix == null) {
                    continue;
                }
                final var last = tokens.get(tokens.size() - 1);
                final var target = last.contains(":") ? last.substring(last.lastIndexOf(':') + 1) : last;
                keys.add(prefix + ":" + target);
            }
            return keys;
        }

        /**
         * Best matching configured rule prefix ({@code commandStart} joined) for the given command
         * tokens, or null when no rule matches (the command is not a recognized read-only family, so
         * it cannot be classified as an exploration target).
         */
        private String coveragePrefix(final List<String> tokens) {
            String best = null;
            var bestLength = 0;
            for (final var rule : rules) {
                if (!rule.matches(tokens)) {
                    continue;
                }
                if (best == null || rule.commandStart().size() > bestLength) {
                    best = String.join(" ", rule.commandStart());
                    bestLength = rule.commandStart().size();
                }
            }
            return best;
        }

        private record SegmentDecision(boolean allowed, String matchedStart, Cause cause) {}

        private SegmentDecision evaluateSegment(final Segment segment, final AgentMode agentMode) {
            final var effective = new ArrayList<Token>(segment.tokens().size());
            for (final var token : segment.tokens()) {
                if (!TOLERATED_REDIRECTS.contains(token.text())) {
                    effective.add(token);
                }
            }
            // remaining redirections (>, <) and command substitutions ($(), backticks) are never auto-allowed
            for (final var token : effective) {
                if (token.redirect() || token.substitution()) {
                    return new SegmentDecision(false, null, Cause.REDIRECT_OR_SUBSTITUTION);
                }
            }
            var i = 0;
            while (i < effective.size() && isEnvAssignment(effective.get(i).text())) {
                i++;
            }
            if (i >= effective.size()) {
                return new SegmentDecision(true, null, null); // pure env assignments
            }

            final var text = effective.get(i).text();
            if ("cd".equals(text)) {
                return new SegmentDecision(true, null, null); // directory change has no repository side effect
            }
            if ("xargs".equals(text)) {
                return evaluateXargs(effective, i, agentMode);
            }

            final var tokens = new ArrayList<String>(effective.size() - i);
            for (var j = i; j < effective.size(); j++) {
                tokens.add(effective.get(j).text());
            }
            return evaluateCommandTokens(tokens, agentMode);
        }

        private SegmentDecision evaluateCommandTokens(final List<String> tokens, final AgentMode agentMode) {

            var allowed = false;
            String bestStart = null;
            var bestLength = 0;

            for (final var rule : rules) {
                if (!rule.matches(tokens)) {
                    continue;
                }
                if (bestStart == null || rule.commandStart().size() > bestLength) {
                    bestStart = String.join(" ", rule.commandStart());
                    bestLength = rule.commandStart().size();
                }
                if (rule.resolve(agentMode) == PermissionMode.YOLO) {
                    allowed = true;
                }
            }

            return new SegmentDecision(
                    allowed, bestStart, allowed ? null : (bestStart != null ? Cause.NOT_YOLO : Cause.NO_MATCH));
        }

        private SegmentDecision evaluateXargs(
                final List<Token> tokens, final int xargsIndex, final AgentMode agentMode) {

            var i = xargsIndex + 1;

            while (i < tokens.size()) {
                final var option = tokens.get(i).text();

                // `--` terminates xargs options.
                if ("--".equals(option)) {
                    i++;
                    break;
                }

                // Not an option anymore: this is the command xargs will execute.
                if (!option.startsWith("-") || "-".equals(option)) {
                    break;
                }

                final var consumed = consumeXargsOption(tokens, i);
                if (consumed < 0) {
                    // Unknown/unsupported option or malformed option.
                    // Fail closed rather than accidentally auto-allowing it.
                    return new SegmentDecision(false, null, Cause.NO_MATCH);
                }

                i += consumed;
            }

            // Plain `xargs` executes `echo` by default. Don't treat that as
            // an implicitly allowed command.
            if (i >= tokens.size()) {
                return new SegmentDecision(false, null, Cause.NO_MATCH);
            }

            final var command = new ArrayList<String>(tokens.size() - i);
            for (; i < tokens.size(); i++) {
                command.add(tokens.get(i).text());
            }

            // Do not allow xargs to hide a shell command from the evaluator.
            if (isShellCommand(command.get(0))) {
                return new SegmentDecision(false, null, Cause.NO_MATCH);
            }

            return evaluateCommandTokens(command, agentMode);
        }

        /**
         * Parses one xargs option.
         *
         * @return the number of tokens consumed, or -1 for an unsupported/malformed option.
         */
        private int consumeXargsOption(final List<Token> tokens, final int index) {

            final var option = tokens.get(index).text();

            // Long options with separate arguments.
            if (option.equals("--delimiter")
                    || option.equals("--eof")
                    || option.equals("--replace")
                    || option.equals("--max-lines")
                    || option.equals("--max-args")
                    || option.equals("--max-chars")
                    || option.equals("--max-procs")
                    || option.equals("--arg-file")) {
                return index + 1 < tokens.size() ? 2 : -1;
            }

            // Long options with attached arguments.
            if (option.startsWith("--delimiter=")
                    || option.startsWith("--eof=")
                    || option.startsWith("--replace=")
                    || option.startsWith("--max-lines=")
                    || option.startsWith("--max-args=")
                    || option.startsWith("--max-chars=")
                    || option.startsWith("--max-procs=")
                    || option.startsWith("--arg-file=")) {
                return 1;
            }

            // Argument-less options.
            if (option.equals("-0")
                    || option.equals("--null")
                    || option.equals("-r")
                    || option.equals("--no-run-if-empty")
                    || option.equals("-t")
                    || option.equals("--verbose")
                    || option.equals("-p")
                    || option.equals("--interactive")
                    || option.equals("-x")
                    || option.equals("--exit")
                    || option.equals("-o")
                    || option.equals("--open-tty")) {
                return 1;
            }

            // Short options with separate arguments.
            if (option.equals("-d")
                    || option.equals("-E")
                    || option.equals("-I")
                    || option.equals("-L")
                    || option.equals("-n")
                    || option.equals("-P")
                    || option.equals("-s")
                    || option.equals("-a")) {
                return index + 1 < tokens.size() ? 2 : -1;
            }

            // Short options with attached arguments.
            if (option.length() > 2) {
                final var prefix = option.substring(0, 2);

                if (prefix.equals("-d")
                        || prefix.equals("-E")
                        || prefix.equals("-I")
                        || prefix.equals("-L")
                        || prefix.equals("-n")
                        || prefix.equals("-P")
                        || prefix.equals("-s")) {
                    return 1;
                }
            }

            // Unknown xargs option -> fail closed.
            return -1;
        }

        private boolean isShellCommand(final String command) {
            return switch (command) {
                case "sh", "bash", "dash", "zsh", "ksh", "fish", "csh", "tcsh" -> true;
                default -> false;
            };
        }

        // ------------------------------------------------------------------
        // quote aware tokenizer with operator segmentation
        // ------------------------------------------------------------------

        private record Token(String text, boolean redirect, boolean substitution) {}

        private record Segment(List<Token> tokens) {}

        private static final class TokenBuilder {
            private final StringBuilder text = new StringBuilder();
            private boolean started;
            private boolean redirect;
            private boolean substitution;

            void append(final char c) {
                text.append(c);
                started = true;
            }

            void markRedirect() {
                redirect = true;
                started = true;
            }

            void markSubstitution() {
                substitution = true;
                started = true;
            }

            boolean endsWithRedirect() {
                return text.length() > 0
                        && (text.charAt(text.length() - 1) == '>' || text.charAt(text.length() - 1) == '<');
            }

            Token build() {
                final var token = new Token(text.toString(), redirect, substitution);
                reset();
                return token;
            }

            void reset() {
                text.setLength(0);
                started = false;
                redirect = false;
                substitution = false;
            }
        }

        private static List<Segment> tokenize(final String raw) {
            final var segments = new ArrayList<Segment>();
            var tokens = new ArrayList<Token>();
            final var builder = new TokenBuilder();
            var continuation = false; // the previous separator (|, ||, &&) expects the command to continue
            final var length = raw.length();
            for (var i = 0; i < length; i++) {
                final var c = raw.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r') {
                    if (builder.started) {
                        tokens.add(builder.build());
                    }
                    continue;
                }
                if (c == '\n') {
                    if (continuation && tokens.isEmpty() && !builder.started) {
                        continue; // the pipeline continues on the next line
                    }
                    if (builder.started) {
                        tokens.add(builder.build());
                    }
                    if (!tokens.isEmpty()) {
                        segments.add(new Segment(tokens));
                        tokens = new ArrayList<>();
                    }
                    continuation = false;
                    continue;
                }
                if (c == ';' || c == '&' || c == '|') {
                    if (c == '&' && builder.endsWithRedirect()) {
                        builder.append(c); // fd manipulation such as 2>&1 stays part of the redirect token
                        builder.markRedirect();
                        continue;
                    }
                    if (builder.started) {
                        tokens.add(builder.build());
                    }
                    if (!tokens.isEmpty()) {
                        segments.add(new Segment(tokens));
                        tokens = new ArrayList<>();
                    }
                    if (c == '|') {
                        continuation = true;
                    } else if (c == '&' && i + 1 < length && raw.charAt(i + 1) == '&') {
                        i++;
                        continuation = true;
                    } else {
                        continuation = false;
                    }
                    continue;
                }
                if (c == '\'') { // literal until the closing quote
                    for (i = i + 1; i < length && raw.charAt(i) != '\''; i++) {
                        builder.append(raw.charAt(i));
                    }
                    continue;
                }
                if (c == '"') { // $() and backticks are live, backslash escapes
                    for (i = i + 1; i < length && raw.charAt(i) != '"'; i++) {
                        final var d = raw.charAt(i);
                        if (d == '\\' && i + 1 < length && raw.charAt(i + 1) != '\n') {
                            builder.append(raw.charAt(++i));
                            continue;
                        }
                        if (d == '`' || (d == '$' && i + 1 < length && raw.charAt(i + 1) == '(')) {
                            builder.markSubstitution();
                        }
                        builder.append(d);
                    }
                    continue;
                }
                if (c == '\\') {
                    if (i + 1 < length) {
                        final var next = raw.charAt(i + 1);
                        if (next == '\n') {
                            i++; // consume the newline too: the line continues without a separator
                            continue;
                        }
                        builder.append(next);
                        i++;
                    }
                    continue;
                }
                if (c == '>' || c == '<') {
                    builder.append(c);
                    builder.markRedirect();
                    continue;
                }
                if (c == '$') {
                    if (i + 1 < length && raw.charAt(i + 1) == '(') {
                        builder.markSubstitution();
                        builder.append('$');
                        builder.append('(');
                        i++;
                    } else {
                        builder.append(c);
                    }
                    continue;
                }
                if (c == '`') {
                    builder.markSubstitution();
                    builder.append(c);
                    continue;
                }
                builder.append(c);
            }
            if (builder.started) {
                tokens.add(builder.build());
            }
            if (!tokens.isEmpty()) {
                segments.add(new Segment(tokens));
            }
            return segments;
        }

        private static boolean isEnvAssignment(final String token) {
            if (token.isEmpty() || !Character.isLetterOrDigit(token.charAt(0)) && token.charAt(0) != '_') {
                return false;
            }
            for (var i = 0; i < token.length(); i++) {
                final var c = token.charAt(i);
                if (c == '=') {
                    return i > 0 && (Character.isLetterOrDigit(token.charAt(i - 1)) || token.charAt(i - 1) == '_');
                }
                if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                    return false;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // compiled rule model
    // ------------------------------------------------------------------

    private record Excludes(Set<String> exact, List<String> longOptions, List<List<String>> sequences) {

        boolean anyMatch(final List<String> tokens) {
            for (final var token : tokens) {
                if (exact.contains(token)) {
                    return true;
                }
                for (final var option : longOptions) {
                    if (token.equals(option) || token.startsWith(option + "=")) {
                        return true;
                    }
                }
            }
            for (final var sequence : sequences) {
                if (containsConsecutive(tokens, sequence)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CompiledRule(
            List<String> commandStart, Excludes excludes, PermissionMode plan, PermissionMode defaultMode) {

        PermissionMode resolve(final AgentMode agentMode) {
            if (agentMode == AgentMode.PLAN) {
                return plan != null ? plan : defaultMode;
            }
            return defaultMode != null ? defaultMode : plan;
        }

        boolean matches(final List<String> tokens) {
            if (commandStart.size() > tokens.size()) {
                return false;
            }
            for (var i = 0; i < commandStart.size(); i++) {
                if (!commandStart.get(i).equals(tokens.get(i))) {
                    return false;
                }
            }
            return !excludes.anyMatch(tokens);
        }
    }

    private static boolean containsConsecutive(final List<String> tokens, final List<String> sequence) {
        for (var i = 0; i + sequence.size() <= tokens.size(); i++) {
            var matches = true;
            for (var j = 0; j < sequence.size(); j++) {
                if (!sequence.get(j).equals(tokens.get(i + j))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
