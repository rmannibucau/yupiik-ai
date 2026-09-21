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

import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Completion engine for the prompt line: slash commands, per-command
 * parameter completers, @agent prefixes and filesystem paths. Command names
 * and argument kinds come from the shared {@link SlashRegistry}; all dynamic
 * value sources (models, providers, agents, sessions, tools) are supplied by
 * the shell. Pure and terminal-free so it can be unit tested.
 */
public final class CompletionEngine {
    /** A completion candidate: the range of text to replace and the matches. */
    public record Suggestion(List<String> items, int replaceStart, int replaceEnd, String label) {
        public boolean empty() {
            return items == null || items.isEmpty();
        }

        public static Suggestion none() {
            return new Suggestion(List.of(), 0, 0, "");
        }
    }

    private final SlashRegistry registry;
    private final Supplier<List<String>> models;
    private final Supplier<List<String>> providers;
    private final Supplier<List<String>> agents;
    private final Supplier<List<String>> sessions;
    private final Supplier<List<String>> tools;
    private final Supplier<List<String>> agentNames;
    private final java.util.function.Function<String, List<String>> variants;

    public CompletionEngine(
            final SlashRegistry registry,
            final Supplier<List<String>> models,
            final Supplier<List<String>> providers,
            final Supplier<List<String>> agents,
            final Supplier<List<String>> sessions,
            final Supplier<List<String>> tools,
            final Supplier<List<String>> agentNames,
            final java.util.function.Function<String, List<String>> variants) {
        this.registry = registry;
        this.models = orEmpty(models);
        this.providers = orEmpty(providers);
        this.agents = orEmpty(agents);
        this.sessions = orEmpty(sessions);
        this.tools = orEmpty(tools);
        this.agentNames = orEmpty(agentNames);
        this.variants = variants == null ? model -> List.of() : variants;
    }

    private static Supplier<List<String>> orEmpty(final Supplier<List<String>> supplier) {
        return supplier == null ? () -> List.of() : supplier;
    }

    /**
     * Builds a completion suggestion for the whole prompt line.
     *
     * @param text   the full prompt text
     * @param cursor the cursor column (index into text)
     */
    public Suggestion suggest(final String text, final int cursor) {
        if (text == null || text.isEmpty()) {
            return Suggestion.none();
        }
        return suggestWithin(text, cursor);
    }

    private Suggestion suggestWithin(final String text, final int cursor) {
        // token the cursor is inside of
        int tokenStart = cursor;
        while (tokenStart > 0 && text.charAt(tokenStart - 1) != ' ') {
            tokenStart--;
        }
        final var prefixInner = text.substring(tokenStart, cursor);
        if (cursor == text.length() && prefixInner.startsWith("@")) {
            // free-text @agent prefix: strip '@' for matching ("@coder" -> "coder")
            final var name = prefixInner.substring(1);
            final var items = fuzzy(agentNames.get(), name);
            return items.isEmpty() ? Suggestion.none() : new Suggestion(items, tokenStart + 1, cursor, "agents");
        }
        final var beforeRaw = text.substring(0, tokenStart);
        // no text before the current token means we are still typing the command itself
        if (beforeRaw.isBlank()) {
            if (text.startsWith("/")) {
                return complete(text.substring(0, cursor), 0, registry.names(), "commands");
            }
            return Suggestion.none();
        }
        final var firstToken = beforeRaw.strip().split("\\s+")[0];
        if (!firstToken.startsWith("/")) {
            return Suggestion.none();
        }
        final var command = firstToken.substring(1);
        // for /model the first argument completes model names and the second (separated by a
        // space) completes variant names; detect the position from the already-typed tokens
        if ("model".equals(command) && beforeRaw.strip().split("\\s+").length >= 2) {
            final var tokens = beforeRaw.strip().split("\\s+");
            final var typedModel = tokens.length > 1 ? tokens[1] : "";
            return complete(prefixInner, tokenStart, variants.apply(typedModel), "variant");
        }
        return switch (registry.completionFor(command)) {
            case NONE -> Suggestion.none();
            case MODELS -> complete(prefixInner, tokenStart, models.get(), "model");
            case PROVIDERS -> complete(prefixInner, tokenStart, providers.get(), "provider");
            case AGENTS -> complete(prefixInner, tokenStart, agents.get(), "agent");
            case SESSIONS -> complete(prefixInner, tokenStart, sessions.get(), "session");
            case TOOLS -> complete(prefixInner, tokenStart, tools.get(), "tool");
            case MODE -> suggestionsWithHint("build", "plan", prefixInner, tokenStart);
            case PERMISSIONS -> suggestionsWithHint("all", "none", "show", prefixInner, tokenStart);
            case SHOW_HIDE -> suggestionsWithHint("show", "hide", prefixInner, tokenStart);
            case PATH -> {
                final var items = pathCandidates(prefixInner);
                yield items.isEmpty() ? Suggestion.none() : new Suggestion(items, tokenStart, cursor, "path");
            }
        };
    }

    private Suggestion complete(
            final String token, final int replaceStart, final List<String> candidates, final String label) {
        final var items = fuzzy(candidates, token);
        return items.isEmpty()
                ? Suggestion.none()
                : new Suggestion(items, replaceStart, replaceStart + token.length(), label);
    }

    private Suggestion suggestionsWithHint(
            final String first, final String second, final String token, final int tokenStart) {
        final var out = new ArrayList<String>();
        final var lc = token.toLowerCase(Locale.ROOT);
        if (first.toLowerCase(Locale.ROOT).contains(lc)) {
            out.add(first);
        }
        if (second.toLowerCase(Locale.ROOT).contains(lc)) {
            out.add(second);
        }
        return out.isEmpty()
                ? Suggestion.none()
                : new Suggestion(out, tokenStart, tokenStart + token.length(), "option");
    }

    private Suggestion suggestionsWithHint(
            final String first, final String second, final String third, final String token, final int tokenStart) {
        final var a = suggestionsWithHint(first, second, token, tokenStart);
        final var lc = token.toLowerCase(Locale.ROOT);
        if (third.toLowerCase(Locale.ROOT).contains(lc)) {
            final var items = new ArrayList<String>(a.items());
            if (!items.contains(third)) {
                items.add(third);
            }
            return items.isEmpty()
                    ? Suggestion.none()
                    : new Suggestion(items, a.replaceStart(), a.replaceStart() + token.length(), "option");
        }
        return a;
    }

    /** Substring + contains fuzzy match, lower-cased, stable sorted. */
    static List<String> fuzzy(final List<String> candidates, final String token) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        final var lc = token.toLowerCase(Locale.ROOT);
        final var out = new ArrayList<String>();
        for (final var c : candidates) {
            if (c != null && !c.isBlank() && c.toLowerCase(Locale.ROOT).contains(lc)) {
                out.add(c);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Filesystem path completer used by /context and /editplan. */
    static List<String> pathCandidates(final String token) {
        final var base = token.isEmpty() ? "." : token;
        final var norm = base.replace("~", System.getProperty("user.home"));
        final var path = Path.of(norm).toAbsolutePath();
        final var parent = path.getParent() == null ? Path.of(".") : path.getParent();
        final var lastSlash = base.lastIndexOf('/');
        final var dirPart = lastSlash >= 0 ? base.substring(0, lastSlash + 1) : "";
        final var fileName =
                path.getFileName() == null ? "" : path.getFileName().toString();
        final var lcToken = token.toLowerCase(Locale.ROOT);
        final List<String> out;
        try (final var stream = Files.list(parent)) {
            out = stream.filter(p -> p.getFileName() != null)
                    .map(p -> {
                        final var name = p.getFileName().toString();
                        return Files.isDirectory(p) ? dirPart + name + "/" : dirPart + name;
                    })
                    .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(lcToken))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            return List.of();
        }
        return out;
    }
}
