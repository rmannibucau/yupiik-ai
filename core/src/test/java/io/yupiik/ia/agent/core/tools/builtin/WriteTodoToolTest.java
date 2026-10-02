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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class WriteTodoToolTest {
    @TempDir
    Path storageHome;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private TodoStore todoStore() {
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

    /** The tool is stateless: the session's todo list and permissions arrive through the context. */
    private ToolContext context(final TodoStore todos, final PermissionService permissions) {
        return new ToolContext("s1", null, null, permissions, null, todos, null, null, null, Cancellations.NEVER);
    }

    @Test
    void planModeNormalizesNewlyCompletedTodo() {
        final var store = todoStore();
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "step 1", "status", "completed"),
                                Map.of("content", "step 2", "status", "pending"))),
                context(store, permissions(AgentMode.PLAN)));
        assertTrue(result.success(), result.output());
        assertTrue(
                store.render().contains("[ ] step 1"),
                "completed new item must be normalized to pending: " + store.render());
        assertTrue(store.render().contains("[ ] step 2"), store.render());
        assertTrue(
                result.output().contains("normalized to pending"),
                "the tool must report the normalization: " + result.output());
    }

    @Test
    void planModeNormalizesNewlyInProgressTodo() {
        final var store = todoStore();
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of("todos", List.of(Map.of("content", "step 1", "status", "in_progress"))),
                context(store, permissions(AgentMode.PLAN)));
        assertTrue(result.success(), result.output());
        assertTrue(
                store.render().contains("[ ] step 1"),
                "in_progress new item must be normalized to pending: " + store.render());
        assertTrue(result.output().contains("normalized to pending"), result.output());
    }

    @Test
    void planModeAllowsAllPendingPlan() {
        final var store = todoStore();
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "step 1", "status", "pending"),
                                Map.of("content", "step 2", "status", "pending"))),
                context(store, permissions(AgentMode.PLAN)));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[ ] step 1"), store.render());
    }

    @Test
    void planModeToleratesItemsAlreadyAdvancedInBuildMode() {
        final var store = todoStore();
        store.replace(List.of(new TodoItem("step 1", "completed")));
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "step 1", "status", "completed"),
                                Map.of("content", "step 2", "status", "pending"))),
                context(store, permissions(AgentMode.PLAN)));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[x] step 1"), store.render());
    }

    @Test
    void buildModeAcceptsCompletedTodos() {
        final var store = todoStore();
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of("todos", List.of(Map.of("content", "step 1", "status", "completed"))),
                context(store, permissions(AgentMode.BUILD)));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[x] step 1"), store.render());
    }

    @Test
    void buildModeAcceptsCancelledTodos() {
        final var store = todoStore();
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of("todos", List.of(Map.of("content", "step 1", "status", "cancelled"))),
                context(store, permissions(AgentMode.BUILD)));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[-] step 1"), store.render());
    }

    @Test
    void planModeKeepsNewlyCancelledTodoAsScopeDecision() {
        final var store = todoStore();
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "step 1", "status", "cancelled"),
                                Map.of("content", "step 2", "status", "pending"))),
                context(store, permissions(AgentMode.PLAN)));
        assertTrue(result.success(), result.output());
        assertTrue(
                store.render().contains("[-] step 1"),
                "cancelled new item is kept as a scope decision, not normalized: " + store.render());
        assertTrue(store.render().contains("[ ] step 2"), store.render());
    }

    @Test
    void planModeToleratesItemsAlreadyCancelledInBuildMode() {
        final var store = todoStore();
        store.replace(List.of(new TodoItem("step 1", "cancelled")));
        final var tool = new WriteTodoTool();
        final var result = tool.execute(
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "step 1", "status", "cancelled"),
                                Map.of("content", "step 2", "status", "pending"))),
                context(store, permissions(AgentMode.PLAN)));
        assertTrue(result.success(), result.output());
        assertTrue(store.render().contains("[-] step 1"), store.render());
    }

    @Test
    void rejectsStringTodosWithCleanError() {
        final var store = todoStore();
        final var tool = new WriteTodoTool();
        final var result = tool.execute(Map.of("todos", "not an array"), context(store, permissions(AgentMode.BUILD)));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("got String"), result.output());
    }

    @Test
    void descriptionCarriesPlanModeRuleAndAtomicRejection() {
        final var tool = new WriteTodoTool();
        final var description = tool.description();
        assertTrue(
                description.contains("PLAN mode (the default)"),
                "the description must lead with the plan-mode contract (default mode): " + description);
        assertTrue(
                description.contains("new items must stay 'pending'"),
                "the description must state the all-pending plan rule: " + description);
        assertTrue(
                description.contains("nothing is written"),
                "the description must state the atomic-rejection wording: " + description);
        assertTrue(
                description.contains("one or several may be in_progress at once"),
                "the description must allow parallel in_progress in build mode: " + description);
        assertFalse(
                description.contains("exactly one in_progress"),
                "the description must not carry the relaxed single-in_progress rule anymore: " + description);
    }

    @Test
    void schemaStatusIsAnEnumWithPlanHint() {
        final var tool = new WriteTodoTool();
        @SuppressWarnings("unchecked")
        final var todos = (Map<String, Object>) tool.schema().get("properties");
        @SuppressWarnings("unchecked")
        final var items = (Map<String, Object>) ((Map<String, Object>) todos.get("todos")).get("items");
        @SuppressWarnings("unchecked")
        final var status = (Map<String, Object>) ((Map<String, Object>) items.get("properties")).get("status");
        assertEquals("string", status.get("type"));
        assertEquals(List.of("pending", "in_progress", "completed", "cancelled"), status.get("enum"));
        assertTrue(
                String.valueOf(status.get("description")).contains("PLAN mode new items"),
                "the status schema must carry the plan-mode hint");
    }

    @Test
    void withoutSessionContextTheToolFailsCleanly() {
        final var tool = new WriteTodoTool();
        final var result = tool.execute(Map.of("todos", List.of(Map.of("content", "step 1", "status", "pending"))));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("session"), result.output());
    }
}
