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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.todo.TodoItem$FusionJsonCodec;
import io.yupiik.ia.agent.core.todo.TodoStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteTodoToolTest {
    @TempDir
    Path storageHome;

    private TodoStore todoStore() {
        final JsonMapper mapper =
                new JsonMapperImpl(List.of(new TodoItem$FusionJsonCodec()), key -> java.util.Optional.empty());
        return new TodoStore(
                io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(
                        Map.of("yca.storageHome", storageHome.toString())),
                mapper);
    }

    private PermissionService permissions(final AgentMode mode) {
        final var service = new PermissionService(io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(
                Map.of("yca.storageHome", storageHome.toString())));
        service.setMode(mode);
        return service;
    }

    @Test
    void planModeRejectsNewlyCompletedTodo() {
        final var store = todoStore();
        final var tool = new WriteTodoTool(store, permissions(AgentMode.PLAN));
        final var result = tool.execute(Map.of(
                "todos",
                List.of(
                        Map.of("content", "step 1", "status", "completed"),
                        Map.of("content", "step 2", "status", "pending"))));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("must be 'pending'"), result.output());
        assertTrue(store.all().isEmpty(), "rejected update must not touch the store");
    }

    @Test
    void planModeRejectsNewlyInProgressTodo() {
        final var tool = new WriteTodoTool(todoStore(), permissions(AgentMode.PLAN));
        final var result = tool.execute(Map.of("todos", List.of(Map.of("content", "step 1", "status", "in_progress"))));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("PLAN mode"), result.output());
    }

    @Test
    void planModeAllowsAllPendingPlan() {
        final var store = todoStore();
        final var tool = new WriteTodoTool(store, permissions(AgentMode.PLAN));
        final var result = tool.execute(Map.of(
                "todos",
                List.of(
                        Map.of("content", "step 1", "status", "pending"),
                        Map.of("content", "step 2", "status", "pending"))));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[ ] step 1"), store.render());
    }

    @Test
    void planModeToleratesItemsAlreadyAdvancedInBuildMode() {
        final var store = todoStore();
        store.replace(List.of(new io.yupiik.ia.agent.core.todo.TodoItem("step 1", "completed")));
        final var tool = new WriteTodoTool(store, permissions(AgentMode.PLAN));
        final var result = tool.execute(Map.of(
                "todos",
                List.of(
                        Map.of("content", "step 1", "status", "completed"),
                        Map.of("content", "step 2", "status", "pending"))));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[x] step 1"), store.render());
    }

    @Test
    void buildModeAcceptsCompletedTodos() {
        final var store = todoStore();
        final var tool = new WriteTodoTool(store, permissions(AgentMode.BUILD));
        final var result = tool.execute(Map.of("todos", List.of(Map.of("content", "step 1", "status", "completed"))));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[x] step 1"), store.render());
    }

    @Test
    void rejectsStringTodosWithCleanError() {
        final var tool = new WriteTodoTool(todoStore(), permissions(AgentMode.BUILD));
        final var result = tool.execute(Map.of("todos", "not an array"));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("got String"), result.output());
    }
}
