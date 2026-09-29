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

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

class SlashCommandCompleterTest {

    private static SlashRegistry registry() {
        final SlashCommandSet set = () -> List.of(
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
