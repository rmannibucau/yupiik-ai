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
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.Date;
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
    private final MemoryProductionPolicy memoryProductionPolicy;

    public InspectCommands(final AgentSessionService agentSessionService) {
        this(agentSessionService, null);
    }

    public InspectCommands(
            final AgentSessionService agentSessionService, final MemoryProductionPolicy memoryProductionPolicy) {
        this.agentSessionService = agentSessionService;
        this.memoryProductionPolicy = memoryProductionPolicy;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/memory",
                                "show | on|off | enabled=true|false | compact",
                                "show memory, show/disable/enable memory production for this session, or compact",
                                SlashGroup.INSPECT),
                        ArgCompletion.options(
                                "show", "on", "off", "enable", "disable", "enabled=true", "enabled=false"),
                        SlashAction.sync(this::memory)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/tools", "[filter]", "list tools, optionally filtered", SlashGroup.INSPECT),
                        ArgCompletion.from("tool", ArgCompletion.Env::tools),
                        SlashAction.sync(this::tools)),
                SlashCommand.of(
                        new SlashCommandSpec("/todos", null, "show the todo list", SlashGroup.INSPECT),
                        SlashAction.sync(this::todos)));
    }

    private ShellOutcome memory(final String arg, final ShellEnv env) {
        final var option = arg == null ? "" : arg.strip();
        if ("compact".equalsIgnoreCase(option)) {
            return ShellOutcome.transcript("**Memory compaction**\n\n" + agentSessionService.compactMemory());
        }
        // toggle memory production for THIS tab (per-session): only stops producing new facts
        // (extraction + memory_write persist); the system prompt / injected memory are unchanged.
        final var sessionId = env.session().sessionId();
        final var isOn = isOn(option);
        final var isOff = isOff(option);
        if (isOn || isOff) {
            if (memoryProductionPolicy == null || sessionId == null || sessionId.isBlank()) {
                return ShellOutcome.status("cannot toggle memory production for this session");
            }
            memoryProductionPolicy.set(sessionId, isOn);
            final var enabled = memoryProductionPolicy.enabled(sessionId);
            return ShellOutcome.status(
                    "memory production is now " + (enabled ? "enabled" : "disabled") + " for this session");
        }
        final var stats = agentSessionService.memoryStats();
        final var sb = new StringBuilder("\n```text\n");
        if (memoryProductionPolicy != null) {
            sb.append("-- production: ")
                    .append(memoryProductionPolicy.enabled(sessionId) ? "enabled" : "disabled")
                    .append(" (this session)\n");
        }
        sb.append("-- global: ")
                .append(stats.globalChars())
                .append(" chars, ")
                .append(stats.globalBullets())
                .append(" bullets\n");
        sb.append("-- project: ")
                .append(stats.projectChars())
                .append(" chars, ")
                .append(stats.projectBullets())
                .append(" bullets\n");
        if (stats.lastCompaction() != null) {
            sb.append("-- last compaction: ")
                    .append(new Date(stats.lastCompaction().timestamp()))
                    .append(" (before=")
                    .append(stats.lastCompaction().before())
                    .append(", after=")
                    .append(stats.lastCompaction().after())
                    .append(")\n");
        }
        sb.append("\n");
        final var memory = agentSessionService.memory();
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

    /** Whether the given /memory argument is an explicit "produce" token. */
    private static boolean isOn(final String option) {
        return "on".equalsIgnoreCase(option)
                || "enable".equalsIgnoreCase(option)
                || "enabled=true".equalsIgnoreCase(option);
    }

    /** Whether the given /memory argument is an explicit "do not produce" token. */
    private static boolean isOff(final String option) {
        return "off".equalsIgnoreCase(option)
                || "disable".equalsIgnoreCase(option)
                || "enabled=false".equalsIgnoreCase(option);
    }
}
