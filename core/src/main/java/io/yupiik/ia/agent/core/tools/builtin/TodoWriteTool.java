package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TodoWriteTool implements EmbeddedTool {
    private final TodoStore todoStore;

    public TodoWriteTool(final TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    protected TodoWriteTool() {
        this.todoStore = null;
    }

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "Replace the current todo list (content + status: pending, in_progress, completed). Keep exactly one in_progress item.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "todos", Schemas.arrayOf("The full todo list.", Schemas.object(Map.of(
                        "content", Schemas.str("Task description."),
                        "status", Schemas.str("pending, in_progress or completed.")), "content", "status"))), "todos");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var todos = todoStore.from((List<?>) args.get("todos"));
        if (todos.isEmpty()) {
            return ToolResult.error("todos must be a non empty array of {content, status}");
        }
        for (final var t : todos) {
            if (!t.isValid()) {
                return ToolResult.error("Invalid todo item: " + t);
            }
        }
        todoStore.replace(todos);
        return ToolResult.ok("Todo list updated:\n" + todoStore.render());
    }
}
