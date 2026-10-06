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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.SessionStorageLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TodoStore {
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final String key;
    private volatile List<TodoItem> todos = List.of();

    public TodoStore(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this(configuration, mapper, null);
    }

    /**
     * A store persisting under a session key: each tab owns its own file
     * ({@code todos-<key>.json}) so concurrent tabs do not share a list.
     *
     * @param key the session (tab) key or null for the legacy shared {@code todos.json}
     */
    public TodoStore(final CodingAgentConfiguration configuration, final JsonMapper mapper, final String key) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.key = sanitize(key);
    }

    protected TodoStore() {
        this.configuration = null;
        this.mapper = null;
        this.key = null;
    }

    private static String sanitize(final String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * The persisted location: the shared root {@code todos.json} for the legacy
     * unkeyed store, the session folder ({@code sessions/<key>/todos.json}) for
     * generated session ids, and the legacy root {@code todos-<key>.json} for
     * any other key (which never becomes a session folder).
     */
    private Path path() {
        final var home = Path.of(configuration.resolveStorageHome());
        if (key == null) {
            return home.resolve("todos.json");
        }
        if (SessionStorageLayout.isGeneratedSessionId(key)) {
            return SessionStorageLayout.sessionDir(home, key).resolve("todos.json");
        }
        return home.resolve("todos-" + key + ".json");
    }

    /** The legacy root {@code todos-<key>.json} (read-only fallback; never migrated). */
    private Path legacyPath() {
        return key == null ? null : Path.of(configuration.resolveStorageHome()).resolve("todos-" + key + ".json");
    }

    public List<TodoItem> all() {
        return List.copyOf(todos);
    }

    public synchronized List<TodoItem> replace(final List<TodoItem> items) {
        todos = items == null ? List.of() : List.copyOf(items);
        persist();
        return all();
    }

    public synchronized void load() {
        var path = path();
        // backward compatibility: a session whose todos live in the legacy root file is still read
        if (!Files.isReadable(path) && legacyPath() != null && Files.isReadable(legacyPath())) {
            path = legacyPath();
        }
        if (!Files.isReadable(path)) {
            return;
        }
        try {
            // read as a raw object graph then convert: keeps the parse independent of
            // mapper array/list codec support, {@link #from} accepts maps or items
            final var loaded = mapper.fromString(Object.class, Files.readString(path));
            if (loaded instanceof final List<?> list && !list.isEmpty()) {
                todos = List.copyOf(from(list));
            }
        } catch (final IOException | RuntimeException e) {
            // corrupted/legacy file: ignore and keep the in-memory list
        }
    }

    private void persist() {
        try {
            final var path = path();
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
                                case "cancelled" -> "[-] ";
                                default -> "[ ] ";
                            })
                    .append(t.content())
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Parses raw todo entries ({@link TodoItem} or maps) into todo items. */
    public static List<TodoItem> from(final List<?> raw) {
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
