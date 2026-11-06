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
package io.yupiik.ia.agent.tui.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellRegistered;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jline.reader.Candidate;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

class SlashCommandCompleterTest {

    private static SlashRegistry registry() {
        final SlashCommandSet set = () -> List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/subagents", "[id|name]", "subagents", SlashGroup.SESSION),
                        ArgCompletion.wholeArgument("subagent", ArgCompletion.Env::subagents),
                        (arg, env) -> null),
                SlashCommand.of(
                        new SlashCommandSpec("/model", null, "model cmd", SlashGroup.CONFIG),
                        ArgCompletion.from("model", ArgCompletion.Env::models),
                        (arg, env) -> null),
                SlashCommand.of(
                        new SlashCommandSpec("/mode", null, "mode cmd", SlashGroup.CONFIG),
                        ArgCompletion.options("build", "plan"),
                        (arg, env) -> null),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/metrics",
                                "[show|hide|compact|full|session on|off]",
                                "metrics cmd",
                                SlashGroup.CONFIG),
                        (arg, token, env) -> {
                            if ("session".equals(ArgCompletion.previousToken(arg))) {
                                return ArgCompletion.fuzzyResult("option", List.of("on", "off"), token);
                            }
                            return ArgCompletion.fuzzyResult(
                                    "option", List.of("show", "hide", "compact", "full", "session"), token);
                        },
                        (arg, env) -> null));
        return new SlashRegistry(List.of(set));
    }

    @Test
    void completesSlashCommandNames() {
        final var completer = new SlashCommandCompleter(registry(), null, null, null, null, null);
        final var candidates = new ArrayList<Candidate>();
        completer.complete(null, line("/mod", 4), candidates);
        assertTrue(candidates.stream().anyMatch(c -> "/model".equals(c.value())), candidates.toString());
    }

    @Test
    void completesArgumentsThroughTheEngine() {
        final var completer = new SlashCommandCompleter(registry(), null, null, null, null, null);
        final var candidates = new ArrayList<Candidate>();
        // no dynamic source is wired (all null): must not throw and must not invent candidates
        completer.complete(null, line("/model ", 7), candidates);
        assertTrue(candidates.isEmpty(), candidates.toString());
        // mode has static hints baked in the command completion
        completer.complete(null, line("/mode b", 7), candidates);
        assertTrue(candidates.stream().anyMatch(c -> "build".equals(c.value())), candidates.toString());
    }

    @Test
    void completesMetricsThroughJLine() {
        final var completer = new SlashCommandCompleter(registry(), null, null, null, null, null);
        final var candidates = new ArrayList<Candidate>();
        completer.complete(null, line("/metrics c", 10), candidates);
        assertTrue(candidates.stream().anyMatch(c -> "compact".equals(c.value())), candidates.toString());
        assertTrue(candidates.stream().noneMatch(c -> "show".equals(c.value())), candidates.toString());
        final var sessionCandidates = new ArrayList<Candidate>();
        completer.complete(null, line("/metrics session o", 18), sessionCandidates);
        assertTrue(sessionCandidates.stream().anyMatch(c -> "on".equals(c.value())), sessionCandidates.toString());
        assertTrue(sessionCandidates.stream().anyMatch(c -> "off".equals(c.value())), sessionCandidates.toString());
    }

    @Test
    void ignoresFreeText() {
        final var completer = new SlashCommandCompleter(registry(), null, null, null, null, null);
        final var candidates = new ArrayList<Candidate>();
        completer.complete(null, line("hello", 5), candidates);
        assertTrue(candidates.isEmpty(), candidates.toString());
    }

    @Test
    void completesSessionScopedNamesAndIdsAndTracksSessionChanges() {
        final var values = Map.of(
                "yca.subagents.enabled", "true", "yca.subagents.maxConcurrent", "4", "yca.llm.maxConcurrent", "8");
        final var config = new CodingAgentConfiguration$FusionConfigurationFactory(
                key -> Optional.ofNullable(values.get(key))).get();
        final var subagents = new SubagentRegistry(config, new ProviderManager(config, null));
        final var child = subagents.start("parent", "review", "code reviewer").orElseThrow();
        final var other = subagents.start("other", "review", "other reviewer").orElseThrow();
        final var completer = new SlashCommandCompleter(registry(), null, null, null, null, null, subagents);
        final var session = new ShellSession().sessionId("parent");
        completer.onRegistered(new ShellRegistered(new ShellEnv(null, null, session, null)));
        final var candidates = new ArrayList<Candidate>();
        completer.complete(null, line("/subagents ", 11), candidates);
        assertEquals(List.of(child.id(), "code reviewer").stream().sorted().toList(),
                candidates.stream().map(Candidate::value).sorted().toList());
        candidates.clear();
        session.sessionId("other");
        completer.complete(null, line("/subagents ", 11), candidates);
        assertEquals(List.of(other.id(), "other reviewer").stream().sorted().toList(),
                candidates.stream().map(Candidate::value).sorted().toList());
    }

    @Test
    void parserReplacesLiteralMultiwordNamesWithoutQuotingOrDuplicatePrefixes() {
        final var parser = new SlashCommandParser(registry());
        final var text = "/subagents code rev-old";
        final var parsed = (CompletingParsedLine) parser.parse(text, 19, Parser.ParseContext.COMPLETE);
        assertEquals("code rev-old", parsed.word());
        assertEquals(8, parsed.rawWordCursor());
        assertEquals(12, parsed.rawWordLength());
        final var replacement = parsed.escape("code reviewer's notes", true).toString();
        final var start = parsed.cursor() - parsed.rawWordCursor();
        assertEquals("/subagents code reviewer's notes", text.substring(0, start) + replacement
                + text.substring(start + parsed.rawWordLength()));
        assertEquals("session", parser.parse("/metrics session o", 17, Parser.ParseContext.COMPLETE).words().get(1));
    }

    private static ParsedLine line(final String text, final int cursor) {
        return (ParsedLine) Proxy.newProxyInstance(
                ParsedLine.class.getClassLoader(),
                new Class<?>[] {ParsedLine.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "line" -> text;
                    case "cursor" -> cursor;
                    default -> null;
                });
    }
}
