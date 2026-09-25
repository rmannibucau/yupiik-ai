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
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Maintenance commands shared by every shell: compaction, snapshots, plan
 * edition, code graph and Maven knowledge index, MCP servers, housekeeping.
 */
@ApplicationScoped
public class MaintainCommands implements SlashCommandSet {
    @Override
    public String title() {
        return "Maintenance commands";
    }

    private final AgentSessionService agentSessionService;
    private final McpManager mcpManager;
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;

    public MaintainCommands(
            final AgentSessionService agentSessionService,
            final McpManager mcpManager,
            final MavenRepositoryKnowledgeService mavenKnowledgeService) {
        this.agentSessionService = agentSessionService;
        this.mcpManager = mcpManager;
        this.mavenKnowledgeService = mavenKnowledgeService;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/compact",
                                "[force]",
                                "compact the context window (/compact [force])",
                                SlashGroup.MAINTAIN),
                        SlashAction.sync(this::compact)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/snapshot",
                                "save|cancel|restore|status",
                                "manage context checkpoints that survive compaction",
                                SlashGroup.MAINTAIN),
                        SlashAction.sync(this::snapshot)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/editplan", "[path]", "edit the current plan (in plan mode)", SlashGroup.MAINTAIN),
                        ArgCompletion.PATH,
                        this::editPlan),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/structural-code-graph", null, "resync the code structure index", SlashGroup.MAINTAIN),
                        SlashAction.sync(this::resyncGraph)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/maven-repository-knowledge",
                                "<refresh [gav]|status>",
                                "index local Maven artifacts so agents do not rediscover library usage",
                                SlashGroup.MAINTAIN),
                        SlashAction.sync(this::mavenRepositoryKnowledge)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/mcp",
                                "status|reset [server]",
                                "show MCP server status, or reset (reconnect) a single server or all of them",
                                SlashGroup.MAINTAIN),
                        SlashAction.sync(this::mcp)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/session-housekeeping",
                                null,
                                "clean up sessions (compress, delete empty/expired) and report",
                                SlashGroup.MAINTAIN),
                        SlashAction.sync(this::sessionHousekeeping)));
    }

    private ShellOutcome compact(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return ShellOutcome.status("cannot compact while busy");
        }
        final var trimmed = arg == null ? "" : arg.strip();
        if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("force")) {
            return ShellOutcome.status("usage: /compact [force]");
        }
        try {
            final var result = agentSessionService.compact(
                    env.session().sessionId(),
                    trimmed.equalsIgnoreCase("force"),
                    env.session().runtime());
            if (result.applied() && result.error() != null) {
                return ShellOutcome.status(result.message() + "\n" + result.error());
            }
            return ShellOutcome.status(result.message());
        } catch (final RuntimeException e) {
            return ShellOutcome.error("compact failed: " + e.getMessage());
        }
    }

    private ShellOutcome snapshot(final String arg, final ShellEnv env) {
        try {
            return ShellOutcome.status(
                    agentSessionService.snapshot(arg, env.session().sessionId()));
        } catch (final RuntimeException e) {
            return ShellOutcome.error("snapshot failed: " + e.getMessage());
        }
    }

    private CompletionStage<ShellOutcome> editPlan(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return CompletableFuture.completedStage(ShellOutcome.status("editplan is not available while busy"));
        }
        final var export = agentSessionService.exportPlan(
                env.session().sessionId(), arg, "plan", env.session().runtime());
        if (export.error() != null) {
            return CompletableFuture.completedStage(ShellOutcome.error(export.error()));
        }
        final var exported = ShellOutcome.status("plan exported to " + export.path()
                + " - edit it, save, then press Enter to reload (type 'c' to cancel)");
        return env.io().readLine("").thenApply(input -> {
            if (input.isEmpty()) {
                return ShellOutcome.status("plan edit cancelled");
            }
            final var decision = input.get().strip();
            if (decision.equalsIgnoreCase("c") || decision.equalsIgnoreCase("cancel")) {
                return ShellOutcome.status("plan edit cancelled");
            }
            final var reload = agentSessionService.reloadPlan(
                    env.session().sessionId(),
                    export.path(),
                    export.original(),
                    env.session().runtime());
            if (reload.error() != null) {
                return ShellOutcome.error(reload.error());
            }
            return ShellOutcome.status(reload.message());
        });
    }

    private ShellOutcome resyncGraph(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return ShellOutcome.status("cannot resync the graph while busy");
        }
        try {
            return ShellOutcome.status(agentSessionService.resyncGraph());
        } catch (final RuntimeException e) {
            return ShellOutcome.error("resync failed: " + e.getMessage());
        }
    }

    private ShellOutcome mavenRepositoryKnowledge(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return ShellOutcome.status("cannot run maven knowledge while busy");
        }
        try {
            if (arg == null || arg.isBlank()) {
                return ShellOutcome.status("usage: /maven-repository-knowledge <refresh [gav]|status>");
            }
            final var parts = arg.strip().split("\\s+", 2);
            final var sub = parts[0].toLowerCase(Locale.ROOT);
            if ("status".equals(sub)) {
                return ShellOutcome.transcript(
                        "> maven-repository-knowledge:\n" + String.join("\n", mavenKnowledgeService.status()));
            }
            if (!"refresh".equalsIgnoreCase(sub)) {
                return ShellOutcome.status("usage: /maven-repository-knowledge <refresh [gav]|status>");
            }
            final var gav = parts.length > 1 ? parts[1].strip() : "";
            final var ack = mavenKnowledgeService.refresh(
                    gav,
                    progress -> env.frontend().status(progress),
                    summary -> env.frontend().transcript("\n\n> maven-repository-knowledge:\n" + summary));
            return ShellOutcome.transcript("> maven-repository-knowledge:\n" + String.join("\n", ack));
        } catch (final RuntimeException e) {
            return ShellOutcome.error("maven-repository-knowledge failed: " + e.getMessage());
        }
    }

    private ShellOutcome mcp(final String arg, final ShellEnv env) {
        try {
            final var value = arg == null ? "" : arg.strip();
            if (value.isBlank() || value.equalsIgnoreCase("status")) {
                final var rows = mcpManager.serverStatuses().stream()
                        .map(status -> List.of(
                                SlashRegistry.cell(status.name()),
                                status.state().name().toLowerCase(Locale.ROOT)))
                        .toList();
                return ShellOutcome.transcript(
                        "**MCP servers**\n\n" + SlashRegistry.table(List.of("server", "state"), rows));
            }
            final var parts = value.split("\\s+", 2);
            if (!parts[0].equalsIgnoreCase("reset")) {
                return ShellOutcome.status("usage: /mcp status|reset [server]");
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                final var server = parts[1].strip();
                mcpManager.reset(server);
                return ShellOutcome.status("MCP server " + server + " reset");
            }
            mcpManager.reset();
            return ShellOutcome.status("MCP servers reset");
        } catch (final RuntimeException e) {
            return ShellOutcome.error("mcp failed: " + e.getMessage());
        }
    }

    private ShellOutcome sessionHousekeeping(final String arg, final ShellEnv env) {
        final var report = agentSessionService.housekeeping();
        return ShellOutcome.status("housekeeping done - " + report.compressed() + " compressed, " + report.deleted()
                + " deleted, " + report.kept() + " kept");
    }
}
