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
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.ArrayList;
import java.util.Map;

@ApplicationScoped
public class CheckSubagentsTool implements EmbeddedTool {
    private final SubagentRegistry subagentRegistry;
    private final JsonMapper mapper;

    public CheckSubagentsTool(final SubagentRegistry subagentRegistry, final JsonMapper mapper) {
        this.subagentRegistry = subagentRegistry;
        this.mapper = mapper;
    }

    protected CheckSubagentsTool() {
        this.subagentRegistry = null;
        this.mapper = null;
    }

    @Override
    public String name() {
        return "check_subagents";
    }

    @Override
    public String description() {
        return "Monitor and manage spawned sub-agents. Actions: poll (list active), collect (get result), cancel (terminate).";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "action", Schemas.str("Action to perform: 'poll', 'collect', or 'cancel'."),
                        "subagent_id", Schemas.str("Sub-agent ID (required for 'collect' and 'cancel').")),
                "action");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        if (!subagentRegistry.enabled()) {
            return ToolResult.error("Sub-agents are disabled.");
        }
        final var action = arg(args, "action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("action is required (poll, collect, cancel)");
        }

        return switch (action) {
            case "poll" -> poll();
            case "collect" -> collect(arg(args, "subagent_id"));
            case "cancel" -> cancel(arg(args, "subagent_id"));
            default -> ToolResult.error("Invalid action: " + action + " (must be poll, collect, or cancel)");
        };
    }

    private ToolResult poll() {
        final var active = subagentRegistry.listActive();
        final var list = new ArrayList<Map<String, Object>>();
        for (final var state : active) {
            list.add(Map.of(
                    "id", state.id(),
                    "task", state.task(),
                    "started_at", state.startedAt(),
                    "status", state.isDone() ? (state.isCancelled() ? "cancelled" : "completed") : "running"));
        }
        try {
            return ToolResult.ok(mapper.toString(Map.of("subagents", list)));
        } catch (final RuntimeException e) {
            return ToolResult.error("Failed to serialize result: " + e.getMessage());
        }
    }

    private ToolResult collect(final String subagentId) {
        if (subagentId == null || subagentId.isBlank()) {
            return ToolResult.error("subagent_id is required for collect action");
        }
        // First check if subagent exists and its status
        final var stateOpt = subagentRegistry.get(subagentId);
        if (stateOpt.isEmpty()) {
            return ToolResult.error("Sub-agent not found: " + subagentId);
        }
        final var state = stateOpt.get();
        if (!state.isDone()) {
            return ToolResult.error("Sub-agent still running: " + subagentId);
        }
        // Now try to collect (will remove state)
        final var resultOpt = subagentRegistry.collect(subagentId);
        if (resultOpt.isEmpty()) {
            return ToolResult.error("Sub-agent has no result: " + subagentId);
        }
        final var result = resultOpt.get();
        if (result.success()) {
            try {
                return ToolResult.ok(mapper.toString(Map.of(
                        "subagent_id",
                        subagentId,
                        "summary",
                        result.summary(),
                        "artifacts",
                        result.artifacts(),
                        "completed",
                        true)));
            } catch (final RuntimeException e) {
                return ToolResult.error("Failed to serialize result: " + e.getMessage());
            }
        } else {
            try {
                return ToolResult.error(
                        mapper.toString(Map.of("subagent_id", subagentId, "error", result.error(), "completed", true)));
            } catch (final RuntimeException e) {
                return ToolResult.error("Sub-agent failed: " + result.error());
            }
        }
    }

    private ToolResult cancel(final String subagentId) {
        if (subagentId == null || subagentId.isBlank()) {
            return ToolResult.error("subagent_id is required for cancel action");
        }
        final var stateOpt = subagentRegistry.get(subagentId);
        if (stateOpt.isEmpty()) {
            return ToolResult.error("Sub-agent not found: " + subagentId);
        }
        final var state = stateOpt.get();
        if (state.isDone()) {
            try {
                return ToolResult.ok(mapper.toString(
                        Map.of("subagent_id", subagentId, "cancelled", false, "reason", "already completed")));
            } catch (final RuntimeException e) {
                return ToolResult.error("Failed to serialize result: " + e.getMessage());
            }
        }
        subagentRegistry.cancel(subagentId);
        try {
            return ToolResult.ok(mapper.toString(Map.of("subagent_id", subagentId, "cancelled", true)));
        } catch (final RuntimeException e) {
            return ToolResult.error("Failed to serialize result: " + e.getMessage());
        }
    }
}
