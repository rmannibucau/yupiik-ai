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
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Configuration commands shared by every shell: mode, model, provider, agent,
 * permissions, rendering toggles and metrics.
 */
@ApplicationScoped
public class ConfigCommands implements SlashCommandSet {
    @Override
    public String title() {
        return "Configuration commands";
    }

    private final AgentSessionService agentSessionService;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;

    public ConfigCommands(
            final AgentSessionService agentSessionService,
            final ChatClient chatClient,
            final ProviderManager providerManager,
            final AgentProvider agentProvider) {
        this.agentSessionService = agentSessionService;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/mode", "plan|build", "switch the agent working mode", SlashGroup.CONFIG),
                        ArgCompletion.MODE,
                        SlashAction.sync(this::mode)),
                SlashCommand.of(
                        new SlashCommandSpec("/model", "[name]", "show or switch the model", SlashGroup.CONFIG),
                        ArgCompletion.MODELS,
                        this::model),
                SlashCommand.of(
                        new SlashCommandSpec("/provider", "[id]", "show or switch the provider", SlashGroup.CONFIG),
                        ArgCompletion.PROVIDERS,
                        this::provider),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/agent", "[name]", "show or set the active custom agent", SlashGroup.CONFIG),
                        ArgCompletion.AGENTS,
                        this::agent),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/permissions",
                                "all|none|show",
                                "allow all tools, ask for approval, or show current (session-scoped) settings",
                                SlashGroup.CONFIG),
                        ArgCompletion.PERMISSIONS,
                        SlashAction.sync(this::permissions)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/thinking",
                                "show|hide",
                                "show or hide the reasoning/thinking stream",
                                SlashGroup.CONFIG),
                        ArgCompletion.SHOW_HIDE,
                        SlashAction.sync(ConfigCommands::thinking)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/tool-output",
                                "show|hide",
                                "show or hide tool invocations and output",
                                SlashGroup.CONFIG),
                        ArgCompletion.SHOW_HIDE,
                        SlashAction.sync(ConfigCommands::toolOutput)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/metrics",
                                "[show|hide|compact|full|session on|off|status]",
                                "control the turn/session metrics display",
                                SlashGroup.CONFIG),
                        SlashAction.sync(this::metrics)));
    }

    private ShellOutcome mode(final String arg, final ShellEnv env) {
        if (env.session().busy()) {
            return ShellOutcome.status("cannot switch mode while busy");
        }
        final var result = agentSessionService.setMode(arg, env.session().sessionId());
        final var sb = new StringBuilder(result.message());
        if (result.seedMessage() != null) {
            sb.append('\n').append(result.seedMessage());
        }
        if (result.seedError() != null) {
            sb.append('\n').append(result.seedError());
        }
        return ShellOutcome.status(sb.toString());
    }

    private CompletionStage<ShellOutcome> model(final String arg, final ShellEnv env) {
        if (arg.isBlank()) {
            final List<String> ids;
            try {
                ids = chatClient.models().ids();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.completedStage(ShellOutcome.error("cannot list models: " + e.getMessage()));
            } catch (final IOException | RuntimeException e) {
                return CompletableFuture.completedStage(ShellOutcome.error("cannot list models: " + e.getMessage()));
            }
            return env.io()
                    .pick("model", ids)
                    .thenApply(selected -> selected.isEmpty()
                            ? ShellOutcome.status("model picker cancelled")
                            : ShellOutcome.status("model: " + agentSessionService.setModel(selected.get())));
        }
        return CompletableFuture.completedStage(ShellOutcome.status("model: " + agentSessionService.setModel(arg)));
    }

    private CompletionStage<ShellOutcome> provider(final String arg, final ShellEnv env) {
        if (!arg.isBlank()) {
            return CompletableFuture.completedStage(
                    ShellOutcome.status("provider: " + agentSessionService.setProvider(arg)));
        }
        final var ids = providerManager.providers().stream().map(p -> p.id()).toList();
        return env.io()
                .pick("provider", ids)
                .thenApply(selected -> selected.isEmpty()
                        ? ShellOutcome.status("provider picker cancelled")
                        : ShellOutcome.status("provider: " + agentSessionService.setProvider(selected.get())));
    }

    private CompletionStage<ShellOutcome> agent(final String arg, final ShellEnv env) {
        if (!arg.isBlank()) {
            return CompletableFuture.completedStage(setAgent(arg, env));
        }
        final var names = agentProvider.agents().stream()
                .map(AgentConfiguration::name)
                .sorted()
                .toList();
        return env.io()
                .pick("agent", names)
                .thenApply(selected -> selected.isEmpty()
                        ? ShellOutcome.status("agent picker cancelled")
                        : setAgent(selected.get(), env));
    }

    private ShellOutcome setAgent(final String arg, final ShellEnv env) {
        final var name = agentSessionService.setAgent(arg);
        if (name == null) {
            return ShellOutcome.error("unknown agent: " + arg);
        }
        env.session().activeAgent(name);
        return ShellOutcome.status("agent: " + name);
    }

    private ShellOutcome permissions(final String arg, final ShellEnv env) {
        final var value = arg == null ? "" : arg.strip();
        if (value.equals("show") || value.equals("list")) {
            final var info = agentSessionService.permissionsInfo();
            final var sb = new StringBuilder("**permissions status (session-scoped)**\n\n");
            sb.append("- permit all: ").append(info.permitAll() ? "on" : "off").append('\n');
            sb.append("- agent mode: ")
                    .append(info.agentMode().name().toLowerCase(Locale.ROOT))
                    .append(", permission mode: ")
                    .append(
                            info.permissionMode() == null
                                    ? "?"
                                    : info.permissionMode().toString().toLowerCase(Locale.ROOT))
                    .append('\n');
            if (info.sessionGrants().isEmpty()) {
                sb.append("- session grants: none (use 'a' in the approval prompt to add one)");
            } else {
                sb.append("- session grants (in-memory, lost on restart):");
                for (final var grant : info.sessionGrants()) {
                    sb.append("\n  - ").append(grant);
                }
            }
            return ShellOutcome.transcript(sb.toString());
        }
        final var permitAll = agentSessionService.setPermissions(value);
        if (permitAll == null) {
            return ShellOutcome.status("usage: /permissions all|none|show");
        }
        return ShellOutcome.status(permitAll ? "permissions: all tools allowed" : "permissions: asking for approval");
    }

    private static ShellOutcome thinking(final String arg, final ShellEnv env) {
        return toggle(
                env,
                arg,
                "thinking",
                value -> {
                    env.session().showThinking(value);
                    env.frontend().showThinking(value);
                },
                env.session().showThinking());
    }

    private static ShellOutcome toolOutput(final String arg, final ShellEnv env) {
        return toggle(
                env,
                arg,
                "tool output",
                value -> {
                    env.session().showToolOutput(value);
                    env.frontend().showToolOutput(value);
                },
                env.session().showToolOutput());
    }

    private static ShellOutcome toggle(
            final ShellEnv env,
            final String arg,
            final String label,
            final java.util.function.Consumer<Boolean> setter,
            final boolean current) {
        final var parsed = parseShowHide(arg);
        if (parsed == null) {
            return ShellOutcome.status(label + " " + (current ? "shown" : "hidden") + " - usage: /"
                    + label.replace(" ", "-") + " show|hide");
        }
        setter.accept(parsed);
        return ShellOutcome.status(label + " " + (parsed ? "shown" : "hidden"));
    }

    private static Boolean parseShowHide(final String arg) {
        final var value = arg.strip().toLowerCase(Locale.ROOT);
        if (value.equals("show")) {
            return true;
        }
        if (value.equals("hide")) {
            return false;
        }
        return null;
    }

    private ShellOutcome metrics(final String arg, final ShellEnv env) {
        final var value = arg == null ? "" : arg.strip().toLowerCase(Locale.ROOT);
        var metrics = env.session().metrics();
        if (value.isBlank() || "status".equals(value)) {
            return ShellOutcome.status("metrics: showPerTurn=" + metrics.showPerTurn()
                    + " showSessionTotals=" + metrics.showSessionTotals()
                    + " displayMode=" + metrics.displayMode());
        }
        final var parts = value.split("\\s+", 2);
        switch (parts[0]) {
            case "show" -> metrics = new MetricsConfiguration(true, metrics.showSessionTotals(), metrics.displayMode());
            case "hide" ->
                metrics = new MetricsConfiguration(false, metrics.showSessionTotals(), metrics.displayMode());
            case "compact" ->
                metrics = new MetricsConfiguration(
                        metrics.showPerTurn(), metrics.showSessionTotals(), MetricsDisplayMode.COMPACT);
            case "full" ->
                metrics = new MetricsConfiguration(
                        metrics.showPerTurn(), metrics.showSessionTotals(), MetricsDisplayMode.FULL);
            case "session" -> {
                if (parts.length > 1) {
                    metrics = new MetricsConfiguration(
                            metrics.showPerTurn(), "on".equalsIgnoreCase(parts[1].strip()), metrics.displayMode());
                } else {
                    return ShellOutcome.status("usage: /metrics session on|off");
                }
            }
            default -> {
                return ShellOutcome.status("usage: /metrics [show|hide|compact|full|session on|off|status]");
            }
        }
        env.session().metrics(metrics);
        return ShellOutcome.status("metrics updated");
    }
}
