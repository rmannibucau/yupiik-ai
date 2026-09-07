package io.yupiik.ia.agent.core.todo;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TodoStore {
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private volatile List<TodoItem> todos = List.of();

    public TodoStore(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.configuration = configuration;
        this.mapper = mapper;
    }

    protected TodoStore() {
        this.configuration = null;
        this.mapper = null;
    }

    public List<TodoItem> all() {
        return List.copyOf(todos);
    }

    public synchronized List<TodoItem> replace(final List<TodoItem> items) {
        todos = items == null ? List.of() : List.copyOf(items);
        persist();
        return all();
    }

    private void persist() {
        try {
            final var path = Path.of(configuration.resolveStorageHome(), "todos.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, mapper.toString(todos));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String render() {
        if (todos.isEmpty()) {
            return "(empty)";
        }
        final var sb = new StringBuilder();
        for (final var t : todos) {
            sb.append(switch (t.status() == null ? "" : t.status()) {
                case "in_progress" -> "[> ] ";
                case "completed" -> "[x] ";
                default -> "[ ] ";
            }).append(t.content()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public List<TodoItem> from(final List<?> raw) {
        final var out = new ArrayList<TodoItem>();
        if (raw != null) {
            for (final var o : raw) {
                if (o instanceof TodoItem t) {
                    out.add(t);
                } else if (o instanceof java.util.Map<?, ?> m) {
                    out.add(new TodoItem(str(m.get("content")), str(m.get("status"))));
                }
            }
        }
        return out;
    }

    private static String str(final Object o) {
        return o == null ? null : o.toString();
    }
}
