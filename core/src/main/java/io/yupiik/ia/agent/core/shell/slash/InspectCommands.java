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
package io.yupiik.ia.agent.core.shell.slash;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.List;

/**
 * Read-only inspection commands shared by every shell: memory, tools, todos.
 */
@ApplicationScoped
public class InspectCommands implements SlashCommandSet {
    @Override
    public String title() {
        return "Inspection commands";
    }

    private final AgentSessionService agentSessionService;

    public InspectCommands(final AgentSessionService agentSessionService) {
        this.agentSessionService = agentSessionService;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/memory", null, "show global and project memory", SlashGroup.INSPECT),
                        SlashAction.sync(this::memory)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/tools", "[filter]", "list tools, optionally filtered", SlashGroup.INSPECT),
                        ArgCompletion.TOOLS,
                        SlashAction.sync(this::tools)),
                SlashCommand.of(
                        new SlashCommandSpec("/todos", null, "show the todo list", SlashGroup.INSPECT),
                        SlashAction.sync(this::todos)));
    }

    private ShellOutcome memory(final String arg, final ShellEnv env) {
        final var memory = agentSessionService.memory();
        if (memory.global().isBlank() && memory.project().isBlank()) {
            return ShellOutcome.status("(empty memory)");
        }
        final var sb = new StringBuilder("\n```text\n");
        if (!memory.global().isBlank()) {
            sb.append("-- global memory --\n").append(memory.global()).append('\n');
        }
        if (!memory.project().isBlank()) {
            sb.append("-- project memory --\n").append(memory.project()).append('\n');
        }
        sb.append("\n```\n");
        return ShellOutcome.transcript(sb.toString());
    }

    private ShellOutcome tools(final String arg, final ShellEnv env) {
        final var listing = agentSessionService.tools(arg);
        if (!listing.anyTool()) {
            return ShellOutcome.status("(no tools available)");
        }
        final var sb = new StringBuilder("**Tools**\n\n");
        if (listing.matches() == 0) {
            sb.append("(no tool matches)\n");
        } else {
            for (final var server : listing.servers()) {
                final var rows = server.tools().stream()
                        .map(tool -> List.of(
                                SlashRegistry.cell(tool.name()),
                                SlashRegistry.cell(tool.description() == null ? "" : tool.description())))
                        .toList();
                sb.append("### ")
                        .append(server.server())
                        .append("\n\n")
                        .append(SlashRegistry.table(List.of("tool", "description"), rows));
            }
        }
        return ShellOutcome.transcript(sb.toString());
    }

    private ShellOutcome todos(final String arg, final ShellEnv env) {
        return ShellOutcome.transcript(env.session().runtime().todos().render());
    }
}
