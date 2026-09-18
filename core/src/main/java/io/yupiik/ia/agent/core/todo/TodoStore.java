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

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
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

    public void onStart(@OnEvent final Start start) {
        load();
    }

    public synchronized void load() {
        final var path = Path.of(configuration.resolveStorageHome(), "todos.json");
        if (!Files.isReadable(path)) {
            return;
        }
        try {
            final var loaded = mapper.fromString(TodoItem[].class, Files.readString(path));
            if (loaded != null && loaded.length > 0) {
                todos = List.of(loaded);
            }
        } catch (final IOException | RuntimeException e) {
            // corrupted/legacy file: ignore and keep the in-memory list
        }
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
            sb.append(
                            switch (t.status() == null ? "" : t.status()) {
                                case "in_progress" -> "[> ] ";
                                case "completed" -> "[x] ";
                                default -> "[ ] ";
                            })
                    .append(t.content())
                    .append('\n');
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
