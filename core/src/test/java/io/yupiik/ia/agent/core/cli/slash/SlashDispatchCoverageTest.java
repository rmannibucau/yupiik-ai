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
package io.yupiik.ia.agent.core.cli.slash;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.cli.CliCommand;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlashDispatchCoverageTest {

    private java.io.PrintStream originalOut;
    private java.io.PrintStream originalErr;
    private final java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new java.io.PrintStream(
                java.io.ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void cliSlashHandlersCoverAllReplSlashCommands() throws Exception {
        final var conf = new CliCommand.CliConf("", "", "", false, false, "compact", false, true, false, false);
        final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
        final var config = TestConfigs.codingAgent(Map.of());
        final var command = new CliCommand(
                conf, null, null, null, null, null, null, null, null, null, null, null, null, mapper, null, null, null,
                null, null, config);
        final Method slashHandlers = CliCommand.class.getDeclaredMethod("slashHandlers");
        slashHandlers.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<String, java.util.function.Function<String, Boolean>> handlers =
                (Map<String, java.util.function.Function<String, Boolean>>) slashHandlers.invoke(command);
        final Set<String> handlerKeys = handlers.keySet();

        final Set<String> catalogKeys = new ReplSlashCommands()
                .commands().stream()
                        .map(SlashCommandSpec::command)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // /quit has alias [/exit] in its usage - allow "exit" as extra handler
        final Set<String> knownAliases = Set.of("exit", "metrics");

        final var missing =
                catalogKeys.stream().filter(k -> !handlerKeys.contains(k)).toList();
        final var extra = handlerKeys.stream()
                .filter(k -> !catalogKeys.contains(k) && !knownAliases.contains(k))
                .toList();
        assertTrue(
                missing.isEmpty() && extra.isEmpty(),
                "CLI slash handlers must cover exactly the ReplSlashCommands catalog. "
                        + "Missing in handlers: " + missing
                        + " Extra in handlers: " + extra);
    }
}
