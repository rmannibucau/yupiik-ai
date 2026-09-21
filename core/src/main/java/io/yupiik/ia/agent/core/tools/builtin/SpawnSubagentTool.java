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

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRunner;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SpawnSubagentTool implements EmbeddedTool {
    private final SubagentRegistry subagentRegistry;
    private final SubagentRunner subagentRunner;
    private final SessionStore sessionStore;
    private final SnapshotStore snapshotStore;
    private final TodoStore todoStore;
    private final CodingAgentConfiguration configuration;

    public SpawnSubagentTool(
            final SubagentRegistry subagentRegistry,
            final SubagentRunner subagentRunner,
            final SessionStore sessionStore,
            final SnapshotStore snapshotStore,
            final TodoStore todoStore,
            final CodingAgentConfiguration configuration) {
        this.subagentRegistry = subagentRegistry;
        this.subagentRunner = subagentRunner;
        this.sessionStore = sessionStore;
        this.snapshotStore = snapshotStore;
        this.todoStore = todoStore;
        this.configuration = configuration;
    }

    protected SpawnSubagentTool() {
        this.subagentRegistry = null;
        this.subagentRunner = null;
        this.sessionStore = null;
        this.snapshotStore = null;
        this.todoStore = null;
        this.configuration = null;
    }

    @Override
    public String name() {
        return "spawn_subagent";
    }

    @Override
    public String description() {
        return "Launch a sub-agent to work on a task in parallel with isolated state. "
                + "The sub-agent inherits the parent's system prompt (AGENTS.md, skills) but has its own "
                + "conversation history, todo list, and snapshots. Returns a subagent_id for tracking.";
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
                        "max_turns",
                                Schemas.intProp(
                                        "Maximum turns for the sub-agent (optional, defaults to global maxIterations)."),
                        "tools",
                                Schemas.arrayOf(
                                        "Optional list of tool names to allow for the sub-agent.",
                                        Schemas.str("Tool name"))),
                "task");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        if (!subagentRegistry.enabled()) {
            return ToolResult.error("Sub-agents are disabled (yca.subagents.enabled=false or concurrency limit is 0).");
        }
        final var task = arg(args, "task");
        if (task == null || task.isBlank()) {
            return ToolResult.error("task is required");
        }
        final var name = arg(args, "name");
        final var maxTurnsObj = args.get("max_turns");
        final Integer maxTurns = maxTurnsObj instanceof Number n ? n.intValue() : null;

        final var stateOpt = subagentRegistry.start(null, task, name);
        if (stateOpt.isEmpty()) {
            return ToolResult.error("Cannot start sub-agent: maximum concurrent sub-agents reached ("
                    + subagentRegistry.maxConcurrent() + ").");
        }
        final var state = stateOpt.get();

        @SuppressWarnings("unchecked")
        final List<String> allowedTools = args.get("tools") instanceof List<?> list
                ? list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList()
                : List.of();

        // TODO: pass allowedTools to subagentRunner to filter tool registry
        subagentRunner.runAsync(state, sessionStore, snapshotStore, todoStore, () -> false);

        final var displayName = state.name() != null ? state.name() : state.id();
        return ToolResult.ok("Sub-agent started: " + displayName + " (task: " + task + ")");
    }
}
