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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentRunSpec;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRunner;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SpawnSubagentTool implements EmbeddedTool {
    private final SubagentRegistry subagentRegistry;
    private final SubagentRunner subagentRunner;
    private final SessionStore sessionStore;
    private final SnapshotStore snapshotStore;
    private final CodingAgentConfiguration configuration;
    private final ProviderManager providerManager;

    public SpawnSubagentTool(
            final SubagentRegistry subagentRegistry,
            final SubagentRunner subagentRunner,
            final SessionStore sessionStore,
            final SnapshotStore snapshotStore,
            final CodingAgentConfiguration configuration,
            final ProviderManager providerManager) {
        this.subagentRegistry = subagentRegistry;
        this.subagentRunner = subagentRunner;
        this.sessionStore = sessionStore;
        this.snapshotStore = snapshotStore;
        this.configuration = configuration;
        this.providerManager = providerManager;
    }

    public SpawnSubagentTool(
            final SubagentRegistry subagentRegistry,
            final SubagentRunner subagentRunner,
            final SessionStore sessionStore,
            final SnapshotStore snapshotStore,
            final CodingAgentConfiguration configuration) {
        this(subagentRegistry, subagentRunner, sessionStore, snapshotStore, configuration, null);
    }

    protected SpawnSubagentTool() {
        this.subagentRegistry = null;
        this.subagentRunner = null;
        this.sessionStore = null;
        this.snapshotStore = null;
        this.configuration = null;
        this.providerManager = null;
    }

    @Override
    public String name() {
        return "spawn_subagent";
    }

    @Override
    public String description() {
        return "Launch a sub-agent to work on a task in parallel with isolated state. "
                + "It inherits the parent's project context and permission ceiling, and has its own "
                + "conversation history, permission grants, todo list, and snapshots. Optional agent profiles "
                + "and the tools allowlist can further restrict which tools the child may use. Use list_subagent_options "
                + "to discover configured profiles and model routes when needed. Delegate clear, independent tasks "
                + "with a concrete expected result, then collect and synthesize the child result. max_turns sets a positive "
                + "upper bound on the child's model round-trips; configured subagent and provider/model limits may lower it. "
                + "Returns a subagent_id for tracking.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "task", Schemas.str("The task description for the sub-agent to execute."),
                        "name", Schemas.str("Optional name for the sub-agent (for display/logging)."),
                        "agent", Schemas.str("Optional named agent profile whose prompt and tool restrictions apply."),
                        "model",
                                Schemas.str("Optional model (or $provider/model) routing this sub-agent to a specific "
                                        + "model/provider instead of inheriting the parent session's selection or the "
                                        + "provider's defaultSubagentModel. The explicit model always wins."),
                        "max_turns",
                                Schemas.intProp(
                                        "Maximum model round-trips for the sub-agent (optional; capped by configured subagent and provider/model limits)."),
                        "tools",
                                Schemas.arrayOf(
                                        "Optional allowlist of tool names for the sub-agent; an empty list allows no tools.",
                                        Schemas.str("Tool name"))),
                "task");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        return ToolResult.error("spawn_subagent requires an active session context.");
    }

    /**
     * The sub-agent inherits the spawning session: its parent todo store and
     * parent session id come from the context so a sub-agent spawned in tab B
     * never sees tab A's state.
     */
    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var parentTodos = context != null ? context.todos() : null;
        if (parentTodos == null) {
            return ToolResult.error("spawn_subagent requires an active session todo list.");
        }
        if (context.subagentId() != null) {
            return ToolResult.error("Nested sub-agents are disabled.");
        }
        if (!subagentRegistry.enabled()) {
            return ToolResult.error("Sub-agents are disabled (yca.subagents.enabled=false or concurrency limit is 0).");
        }
        final var task = arg(args, "task");
        if (task == null || task.isBlank()) {
            return ToolResult.error("task is required");
        }
        final var name = arg(args, "name");
        final var profile = arg(args, "agent");
        final var model = arg(args, "model");
        final var profileName = profile == null || profile.isBlank() ? null : profile.strip();
        if (profile != null && profileName == null) {
            return ToolResult.error("agent profile must not be blank");
        }
        final var selectedProfile = profileName == null || configuration.agents() == null
                ? null
                : configuration.agents().stream()
                        .filter(a -> profileName.equals(
                                a.name() == null ? null : a.name().strip()))
                        .findFirst()
                        .orElse(null);
        if (profileName != null && selectedProfile == null) {
            return ToolResult.error("Unknown agent profile: " + profileName);
        }
        if (model != null && model.isBlank()) {
            return ToolResult.error("model must not be blank");
        }
        final var normalizedModel = model == null ? null : model.strip();
        final var policy =
                configuration.tools() == null ? null : configuration.tools().spawnSubagent();
        if (policy != null && profileName != null && !policy.allowsAgent(profileName)) {
            return ToolResult.error("Agent profile is not available for subagents: " + profileName);
        }
        if (policy != null) {
            final var providerDefault = defaultModel(context);
            final var effective = io.yupiik.ia.agent.core.agent.EffectiveAgentModel.resolve(
                    providerManager, context.settings(), selectedProfile, normalizedModel, providerDefault);
            if (!io.yupiik.ia.agent.core.agent.SubagentModelPolicy.allows(policy, effective)) {
                return ToolResult.error("Model is not available for subagents: " + effective.routedReference());
            }
        }
        final var maxTurnsObj = args.get("max_turns");
        final Integer maxTurns = maxTurnsObj instanceof Number n ? n.intValue() : null;
        if (maxTurns != null && maxTurns < 1) {
            return ToolResult.error("max_turns must be a positive integer");
        }
        final Set<String> allowedTools = args.get("tools") instanceof List<?> list
                ? list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : args.containsKey("tools") ? Set.of() : null;
        final var stateOpt = subagentRegistry.start(new AgentRunSpec(
                context.sessionId(),
                task,
                name,
                profileName,
                normalizedModel,
                maxTurns,
                allowedTools,
                context.allowedTools()));
        if (stateOpt.isEmpty()) {
            return ToolResult.error("Cannot start sub-agent: maximum concurrent sub-agents reached ("
                    + subagentRegistry.maxConcurrent() + ").");
        }
        final var state = stateOpt.get();

        // the parent's probe, so an interrupt (Ctrl+C) or a cancelled turn stops the child too:
        // it would otherwise keep burning tokens on an abandoned task. SubagentRunner also ORs the
        // registry latch, so an explicit cancel_subagent keeps working.
        subagentRunner.runAsync(state, sessionStore, snapshotStore, parentTodos, context, context::isCancelled);

        final var displayName = state.name() != null ? state.name() : state.id();
        return ToolResult.ok("Sub-agent started: " + displayName + " (task: " + task + ")");
    }

    private String defaultModel(final ToolContext context) {
        return providerManager == null
                ? context.settings() == null ? null : context.settings().model()
                : providerManager.effectiveSubagentDefault(context.settings());
    }
}
