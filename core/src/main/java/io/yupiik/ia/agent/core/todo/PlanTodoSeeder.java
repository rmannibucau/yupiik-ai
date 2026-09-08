package io.yupiik.ia.agent.core.todo;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.session.SessionStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Seeds the todo list from the last assistant message (the plan) when the session
 * switches from PLAN mode to BUILD mode and the model did not record the plan
 * with todo_write itself.
 */
@ApplicationScoped
public class PlanTodoSeeder {
    private static final Logger LOGGER = Logger.getLogger(PlanTodoSeeder.class.getName());
    private static final int MAX_PLAN_CHARS = 8_000;

    private final ChatClient chatClient;
    private final TodoStore todoStore;
    private final SessionStore sessionStore;
    private final JsonMapper mapper;

    public PlanTodoSeeder(final ChatClient chatClient, final TodoStore todoStore,
                          final SessionStore sessionStore, final JsonMapper mapper) {
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
            final ChatResult res = chatClient.chat(new ChatCompletionRequest(null, List.of(
                    Message.system("Convert this implementation plan into a todo list. Reply with a JSON array of "
                            + "{\"content\": string, \"status\": \"pending\"} objects, one per concrete implementation step, "
                            + "in execution order, max 10 items. Reply with [] if the text contains no implementation plan. "
                            + "Reply only with the JSON array."),
                    Message.user(plan)), null, false, null, null, null, null, null));
            final var content = res.message() == null || res.message().content() == null
                    ? "" : String.valueOf(res.message().content());
            return parse(content);
        } catch (final IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warning("Plan todo seeding failed: " + e.getMessage());
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
        String last = null;
        for (final var m : sessionStore.load(sessionId)) {
            if ("assistant".equals(m.role()) && m.content() instanceof String s && !s.isBlank()) {
                last = s;
            }
        }
        return last == null ? null : last.length() > MAX_PLAN_CHARS ? last.substring(0, MAX_PLAN_CHARS) : last;
    }
}
