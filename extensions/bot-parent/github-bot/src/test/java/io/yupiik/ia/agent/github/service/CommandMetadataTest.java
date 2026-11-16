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
package io.yupiik.ia.agent.github.service;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.ia.agent.github.command.CleanupCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.CreateCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.DeleteCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.ListCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.PollCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.PurgeCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.RetryCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.ShowCommand$FusionCliCommand;
import io.yupiik.ia.agent.github.command.WorkerCommand$FusionCliCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandMetadataTest {
    @Test
    void exposesSeparatePathsAndOnlyOperationSpecificOptions() {
        final List<CliCommand<?>> commands = List.of(
                new PollCommand$FusionCliCommand(null),
                new ListCommand$FusionCliCommand(null),
                new ShowCommand$FusionCliCommand(null),
                new CreateCommand$FusionCliCommand(null),
                new RetryCommand$FusionCliCommand(null),
                new DeleteCommand$FusionCliCommand(null),
                new PurgeCommand$FusionCliCommand(null),
                new WorkerCommand$FusionCliCommand(null),
                new CleanupCommand$FusionCliCommand(null));
        assertEquals(
                List.of("poll", "list", "show", "create", "retry", "delete", "purge", "worker", "cleanup"),
                commands.stream().map(command -> command.path()[2]).toList());
        for (final var command : commands) {
            assertEquals("github", command.path()[0]);
            assertEquals("bot", command.path()[1]);
            assertEquals(3, command.path().length);
            assertFalse(command.parameters().stream()
                    .anyMatch(parameter -> parameter.cliName().equals("--action")));
            assertTrue(command.parameters().stream()
                    .noneMatch(parameter -> parameter.cliName().startsWith("--bot-")));
            assertTrue(command.parameters().stream()
                    .allMatch(parameter -> !parameter.description().isBlank()));
        }
        final var list = commands.get(1).parameters().stream()
                .map(CliCommand.Parameter::cliName)
                .toList();
        assertEquals(List.of("--filterKind", "--filterRepository", "--filterState"), list);
        assertEquals(
                List.of("--diagnostics", "--task"),
                commands.get(2).parameters().stream()
                        .map(CliCommand.Parameter::cliName)
                        .toList());
        assertTrue(commands.get(0).parameters().stream()
                .anyMatch(parameter -> parameter.cliName().equals("--poll-pageBudget")));
        assertTrue(commands.get(7).parameters().stream()
                .anyMatch(parameter -> parameter.cliName().equals("--worker-maxRetries")));
        for (final var index : List.of(0, 7)) {
            assertFalse(commands.get(index).parameters().stream()
                    .anyMatch(parameter -> parameter.cliName().equals("--dryRun")));
        }
        assertTrue(commands.get(6).parameters().stream()
                .anyMatch(parameter -> parameter.cliName().equals("--retentionDays")));
        assertTrue(commands.get(6).parameters().stream()
                .anyMatch(parameter -> parameter.cliName().equals("--dryRun")));
    }
}
