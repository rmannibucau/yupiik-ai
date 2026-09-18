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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class WriteTodoTool implements EmbeddedTool {
    private final TodoStore todoStore;
    private final PermissionService permissions;

    public WriteTodoTool(final TodoStore todoStore, final PermissionService permissions) {
        this.todoStore = todoStore;
        this.permissions = permissions;
    }

    protected WriteTodoTool() {
        this.todoStore = null;
        this.permissions = null;
    }

    @Override
    public String name() {
        return "write_todo";
    }

    @Override
    public String description() {
        return "Replace the current todo list (content + status: pending, in_progress, completed). Keep exactly one in_progress item. In plan mode every item must be 'pending': the plan is not implemented yet.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "todos",
                        Schemas.arrayOf(
                                "The full todo list.",
                                Schemas.object(
                                        Map.of(
                                                "content", Schemas.str("Task description."),
                                                "status", Schemas.str("pending, in_progress or completed.")),
                                        "content",
                                        "status"))),
                "todos");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        if (!(args.get("todos") instanceof List<?> raw)) {
            return ToolResult.error(
                    "todos must be a non empty array of {content, status} (got " + typeName(args.get("todos")) + ")");
        }
        final var todos = todoStore.from(raw);
        if (todos.isEmpty()) {
            return ToolResult.error("todos must be a non empty array of {content, status}");
        }
        for (final var t : todos) {
            if (!t.isValid()) {
                return ToolResult.error("Invalid todo item: " + t);
            }
        }
        if (permissions != null && permissions.mode() == AgentMode.PLAN) {
            final var error = validatePlanStatuses(todos);
            if (error != null) {
                return ToolResult.error(error);
            }
        }
        todoStore.replace(todos);
        return ToolResult.ok("Todo list updated:\n" + todoStore.render());
    }

    /**
     * In PLAN mode the todo list is the plan, not a progress report: items describe future work and must stay
     * 'pending'. Items already advanced in a previous build-mode session keep their status so a plan/build
     * mode switch never loses real progress.
     */
    private String validatePlanStatuses(final List<TodoItem> todos) {
        final var alreadyAdvanced = todoStore.all().stream()
                .map(t -> t.status() == null || "pending".equals(t.status()) ? null : t.content() + '|' + t.status())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (final var t : todos) {
            if ("pending".equals(t.status()) || alreadyAdvanced.contains(t.content() + '|' + t.status())) {
                continue;
            }
            return "In PLAN mode todos are the plan and every item must be 'pending': '" + t.content() + "' cannot be '"
                    + t.status()
                    + "' before implementation. Switch to build mode (/mode build or --yca-run-mode build) to execute and complete steps.";
        }
        return null;
    }

    private static String typeName(final Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
