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
package io.yupiik.ia.agent.core.todo;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Seeds the todo list from the last assistant message (the plan) when the session
 * switches from PLAN mode to BUILD mode and the model did not record the plan
 * with write_todo itself.
 */
@ApplicationScoped
public class PlanTodoSeeder {
    private static final Logger LOGGER = Logger.getLogger(PlanTodoSeeder.class.getName());
    private static final int MAX_PLAN_CHARS = 8_000;

    private final ChatClient chatClient;
    private final TodoStore todoStore;
    private final SessionStore sessionStore;
    private final JsonMapper mapper;

    public PlanTodoSeeder(
            final ChatClient chatClient,
            final TodoStore todoStore,
            final SessionStore sessionStore,
            final JsonMapper mapper) {
        this.chatClient = chatClient;
        this.todoStore = todoStore;
        this.sessionStore = sessionStore;
        this.mapper = mapper;
    }

    protected PlanTodoSeeder() {
        this.chatClient = null;
        this.todoStore = null;
        this.sessionStore = null;
        this.mapper = null;
    }

    /**
     * @return true when the todo list got seeded from the plan, false when it was
     * already populated, no assistant message was found or the derivation failed.
     */
    public boolean seedFromLastAssistant(final String sessionId) {
        if (!todoStore.all().isEmpty()) {
            return false;
        }
        final var plan = lastAssistantContent(sessionId);
        if (plan == null || plan.isBlank()) {
            return false;
        }
        final var items = extract(plan);
        if (items.isEmpty()) {
            return false;
        }
        todoStore.replace(items);
        return true;
    }

    private List<TodoItem> extract(final String plan) {
        try {
            final var request = new ChatCompletionRequest(
                    null,
                    List.of(
                            Message.system(
                                    "Convert this implementation plan into a todo list. The response is constrained to a "
                                            + "JSON object {\"steps\":[{\"content\": string, \"status\": \"pending\"} ...]} by the output schema, "
                                            + "one concrete implementation step in execution order, max 10 items. Use an empty steps array when the "
                                            + "text contains no implementation plan."),
                            Message.user(plan)),
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);
            final var res = chatClient.chatStructured(request, "todo_steps", stepsSchema());
            final var content =
                    res.result().message() == null || res.result().message().content() == null
                            ? ""
                            : String.valueOf(res.result().message().content());
            if (res.structured()) {
                return parseSteps(content);
            }
            return parse(content);
        } catch (final IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warning("Plan todo seeding failed: " + e.getMessage());
            return List.of();
        }
    }

    private static Map<String, Object> stepsSchema() {
        final var step = new LinkedHashMap<String, Object>();
        step.put("type", "object");
        step.put(
                "properties",
                Map.of(
                        "content", Map.of("type", "string"),
                        "status", Map.of("type", "string", "enum", List.of("pending"))));
        step.put("required", List.of("content", "status"));
        step.put("additionalProperties", false);
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of("steps", Map.of("type", "array", "items", step, "additionalProperties", false)),
                "required",
                List.of("steps"),
                "additionalProperties",
                false);
    }

    private List<TodoItem> parseSteps(final String content) {
        try {
            final var root = mapper.fromString(Object.class, content);
            if (!(root instanceof Map<?, ?> map) || !(map.get("steps") instanceof List<?> raw)) {
                return List.of();
            }
            final var out = new ArrayList<TodoItem>();
            for (final var t : todoStore.from(raw)) {
                if (t.isValid()) {
                    out.add(t);
                }
            }
            return out;
        } catch (final RuntimeException e) {
            LOGGER.warning("Plan todo structured parse failed: " + e.getMessage());
            return List.of();
        }
    }

    private List<TodoItem> parse(final String content) {
        final var start = content.indexOf('[');
        final var end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        final var array = mapper.fromString(Object.class, content.substring(start, end + 1));
        if (!(array instanceof List<?> raw)) {
            return List.of();
        }
        final var out = new ArrayList<TodoItem>();
        for (final var t : todoStore.from(raw)) {
            if (t.isValid()) {
                out.add(t);
            }
        }
        return out;
    }

    private String lastAssistantContent(final String sessionId) {
        final var plan = plan(sessionId);
        return plan == null || plan.length() <= MAX_PLAN_CHARS ? plan : plan.substring(0, MAX_PLAN_CHARS);
    }

    /**
     * Returns the full (uncapped) content of the last assistant message of the
     * session, or {@code null} when there is none. Used to export the plan for
     * manual editing; {@code seedFromLastAssistant} operates on the capped
     * {@code lastAssistantContent} so the LLM derivation stays bounded.
     *
     * @param sessionId the session to inspect.
     * @return the last assistant message content or {@code null}.
     */
    public String plan(final String sessionId) {
        String last = null;
        for (final var m : sessionStore.load(sessionId)) {
            if ("assistant".equals(m.role()) && m.content() instanceof String s && !s.isBlank()) {
                last = s;
            }
        }
        return last;
    }
}
