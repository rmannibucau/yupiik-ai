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
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
                        new SlashCommandSpec(
                                "/model",
                                "[name] [variant] [temperature] [topK] [topP] | [temp=..] [top_k=..] [top_p=..]",
                                "show or switch the model, optionally a named variant and in-memory sampling overrides",
                                SlashGroup.CONFIG),
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
                                String.join("|", PermissionMode.VALUES) + "|all|none|show",
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
                                "show|collapse|hide",
                                "show, collapse to a summary line, or hide tool invocations and output",
                                SlashGroup.CONFIG),
                        ArgCompletion.SHOW_COLLAPSE_HIDE,
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
        final var result = agentSessionService.setMode(
                arg, env.session().sessionId(), env.session().runtime());
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
            return modelWizard(env);
        }
        return CompletableFuture.completedStage(applyModelArg(arg, env));
    }

    private CompletionStage<ShellOutcome> modelWizard(final ShellEnv env) {
        final List<String> ids;
        try {
            ids = chatClient.models().ids();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedStage(ShellOutcome.error("cannot list models: " + e.getMessage()));
        } catch (final IOException | RuntimeException e) {
            return CompletableFuture.completedStage(ShellOutcome.error("cannot list models: " + e.getMessage()));
        }
        return env.io().pick("model", ids).thenCompose(selected -> {
            if (selected.isEmpty()) {
                return CompletableFuture.completedStage(ShellOutcome.status(
                        modelStatus(chatClient.model(env.session().chatSettings()), env)));
            }
            return pickVariant(env, selected.get());
        });
    }

    /**
     * Ask for the variant (or the implicit {@code default}) once a model has been chosen. The
     * "default" entry clears any forced variant and resets the in-memory sampling overrides so the
     * implicit tuner defaults are proposed again. The variants are resolved for the chosen model so
     * the list reflects it rather than the still-active model; when the model exposes no variant
     * beyond the implicit default (one option or fewer) the step is skipped entirely.
     */
    private CompletionStage<ShellOutcome> pickVariant(final ShellEnv env, final String model) {
        final var settings = env.session().chatSettings();
        final var options = new ArrayList<String>();
        options.add("default");
        chatClient.variants(model).forEach(v -> options.add(v.name()));
        agentSessionService.setModel(model, settings);
        if (options.size() <= 1) {
            settings.setVariant(null);
            settings.setSampling(null);
            return CompletableFuture.completedStage(ShellOutcome.status(modelStatus(model, env)));
        }
        return env.io().pick("variant", options).thenApply(selected -> {
            if (selected.isEmpty()) {
                return ShellOutcome.status("model wizard cancelled");
            }
            if ("default".equals(selected.get())) {
                settings.setVariant(null);
                settings.setSampling(null);
            } else {
                settings.setVariant(selected.get());
                settings.setSampling(null);
            }
            return ShellOutcome.status(modelStatus(model, env));
        });
    }

    /**
     * Apply the non-interactive form: {@code /model [model] [variant] [temperature] [topK] [topP]} or a
     * mix with {@code key=value} overrides ({@code temp} / {@code top_k} / {@code top_p}). Positional
     * arguments follow a fixed order once the first sampling value is given; a token containing {@code =}
     * is always treated as a key/value pair. The model and variant are optional (falling back to the
     * current ones); a sampling value may be a decimal or {@code none}/{@code -}/{@code null} to force-omit
     * the field from the payload and bypass the variant and implicit tuner defaults.
     */
    private ShellOutcome applyModelArg(final String arg, final ShellEnv env) {
        final var settings = env.session().chatSettings();
        final var upserts = new LinkedHashMap<String, String>();
        final var positional = new ArrayList<String>();
        for (final var token : arg.strip().split("\\s+")) {
            final var eq = token.indexOf('=');
            if (eq > 0) {
                upserts.put(token.substring(0, eq).toLowerCase(Locale.ROOT), token.substring(eq + 1));
            } else {
                positional.add(token);
            }
        }
        final var model = positional.isEmpty() ? chatClient.model(settings) : positional.get(0);
        final String variant;
        if (positional.size() > 1) {
            variant = positional.get(1);
        } else if (upserts.isEmpty()) {
            variant = null;
        } else {
            variant = chatClient.variant(settings);
        }
        // positional order is fixed: model, variant, temperature, topK, topP
        if (positional.size() >= 3) {
            upserts.putIfAbsent("temp", positional.get(2));
        }
        if (positional.size() >= 4) {
            upserts.putIfAbsent("top_k", positional.get(3));
        }
        if (positional.size() >= 5) {
            upserts.putIfAbsent("top_p", positional.get(4));
        }
        agentSessionService.setModel(model, settings);
        settings.setVariant(variant);
        settings.setSampling(upserts.isEmpty() ? null : parseSampling(upserts));
        return ShellOutcome.status(modelStatus(model, env));
    }

    private ChatClient.SamplingOverride parseSampling(final Map<String, String> upserts) {
        if (upserts.isEmpty()) {
            return null;
        }
        final var values = new HashMap<ChatClient.SamplingField, Optional<Double>>();
        putSampling(values, ChatClient.SamplingField.TEMPERATURE, upserts.get("temp"), upserts.get("temperature"));
        putSampling(values, ChatClient.SamplingField.TOP_K, upserts.get("top_k"));
        putSampling(values, ChatClient.SamplingField.TOP_P, upserts.get("top_p"));
        return ChatClient.SamplingOverride.of(values);
    }

    private void putSampling(
            final Map<ChatClient.SamplingField, Optional<Double>> into,
            final ChatClient.SamplingField field,
            final String... raw) {
        final var value = firstNonBlank(raw);
        if (value == null) {
            return;
        }
        if (isNone(value)) {
            into.put(field, Optional.empty());
            return;
        }
        try {
            into.put(field, Optional.of(Double.valueOf(value)));
        } catch (final NumberFormatException e) {
            into.put(field, Optional.empty());
        }
    }

    private String firstNonBlank(final String... values) {
        for (final var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isNone(final String value) {
        return "none".equalsIgnoreCase(value) || "null".equalsIgnoreCase(value) || "-".equals(value);
    }

    /** Render the model status line: active model, sampling overrides, implicit default variant and explicit variants. */
    private String modelStatus(final String model, final ShellEnv env) {
        final var settings = env.session().chatSettings();
        final var sb = new StringBuilder("model: ").append(model);
        final var sampling = chatClient.sampling(settings);
        if (sampling != null && !sampling.isEmpty()) {
            sb.append(" [sampling ");
            final var fields = new ArrayList<String>();
            appendOverride(sampling, fields, ChatClient.SamplingField.TEMPERATURE, "temp");
            appendOverride(sampling, fields, ChatClient.SamplingField.TOP_K, "top_k");
            appendOverride(sampling, fields, ChatClient.SamplingField.TOP_P, "top_p");
            sb.append(String.join(", ", fields));
            sb.append(']');
        }
        final var variant = chatClient.variant(settings);
        final boolean namedVariant = variant != null && !variant.isBlank();
        sb.append(" [variant ").append(namedVariant ? variant : "default").append(']');
        final var implicit = chatClient.implicitVariant(settings);
        if (implicit != null) {
            sb.append(" - default");
            final var fields = new ArrayList<String>();
            if (implicit.temperature() != null) {
                fields.add("temp=" + implicit.temperature());
            }
            if (implicit.topK() != null) {
                fields.add("top_k=" + implicit.topK());
            }
            if (implicit.topP() != null) {
                fields.add("top_p=" + implicit.topP());
            }
            if (!fields.isEmpty()) {
                sb.append("(").append(String.join(", ", fields)).append(')');
            }
        }
        final var variants = chatClient.variants(settings);
        final var activeVariant = namedVariant ? variant : "default";
        if (!variants.isEmpty()) {
            sb.append(" - variants: ");
            final var names = new ArrayList<String>();
            names.add("default");
            for (final var v : variants) {
                names.add(appendVariantFields(v));
            }
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                final var name = names.get(i);
                if (activeVariant.equals(name)) {
                    sb.append("**").append(name).append("**");
                } else {
                    sb.append(name);
                }
            }
        }
        return sb.toString();
    }

    private String appendVariantFields(final ModelVariantConfiguration v) {
        if (v.temperature() == null && v.topP() == null && v.topK() == null) {
            return v.name();
        }
        final var fields = new ArrayList<String>();
        if (v.temperature() != null) {
            fields.add("temp=" + v.temperature());
        }
        if (v.topP() != null) {
            fields.add("top_p=" + v.topP());
        }
        if (v.topK() != null) {
            fields.add("top_k=" + v.topK());
        }
        return v.name() + "(" + String.join(", ", fields) + ")";
    }

    private void appendOverride(
            final ChatClient.SamplingOverride sampling,
            final List<String> fields,
            final ChatClient.SamplingField field,
            final String label) {
        final var value = sampling.get(field);
        if (value == null) {
            return;
        }
        fields.add(value.isPresent() ? label + "=" + value.get() : label + "=none");
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
            final var info = agentSessionService.permissionsInfo(env.session().runtime());
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
        final var permitAll =
                agentSessionService.setPermissions(value, env.session().runtime());
        if (permitAll == null) {
            return ShellOutcome.status("invalid /permissions value '" + value + "'; expected one of: "
                    + String.join("|", PermissionMode.VALUES) + "|all|none|show");
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
        final var current = env.session().showToolOutput();
        if (arg == null || arg.isBlank()) {
            return ShellOutcome.status("tool output is " + current.name().toLowerCase(Locale.ROOT)
                    + " - usage: /tool-output show|collapse|hide");
        }
        final var value = ToolOutputVisibility.parse(arg.strip());
        if (value == null) {
            return ShellOutcome.status("tool output is " + current.name().toLowerCase(Locale.ROOT)
                    + " - usage: /tool-output show|collapse|hide");
        }
        env.session().showToolOutput(value);
        env.frontend().showToolOutput(value);
        return ShellOutcome.status("tool output " + value.name().toLowerCase(Locale.ROOT));
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
