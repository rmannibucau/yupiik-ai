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
package io.yupiik.ia.agent.github.store;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.parser.BufferProvider;
import io.yupiik.fusion.json.internal.parser.JsonParser;
import io.yupiik.fusion.json.spi.Parser;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Short transactions on a stable sidecar inode; invocation receipts outlive tasks. */
public final class TaskStore {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path home;
    private final JsonMapper json;

    public TaskStore(final Path home, final JsonMapper json) {
        this.home = home.toAbsolutePath().normalize();
        this.json = json;
        try {
            Path current = this.home.getRoot();
            for (final var segment : this.home) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("State directory must not contain symlinks");
                }
            }
            Files.createDirectories(this.home);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public <T> T transaction(final Function<State, T> operation) {
        final var local = LOCKS.computeIfAbsent(home, ignored -> new ReentrantLock());
        local.lock();
        try {
            final var lockPath = home.resolve("state.lock");
            if (Files.isSymbolicLink(lockPath)) {
                throw new IllegalStateException("Symbolic state lock");
            }
            try (final var channel = FileChannel.open(
                            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    final var lock = channel.lock()) {
                final var path = home.resolve("state.json");
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("Symbolic state file");
                }
                final Map<String, Object> data = Files.exists(path) ? read(path) : new LinkedHashMap<>();
                if (data.containsKey("schemaVersion")
                        && (!(data.get("schemaVersion") instanceof Number version) || version.doubleValue() != 1)) {
                    throw new IllegalStateException("Unsupported state schema; preserve file for operator recovery");
                }
                for (final var section : List.of("tasks", "cursors", "receipts")) {
                    if (data.containsKey(section) && !(data.get(section) instanceof Map<?, ?>)) {
                        throw new IllegalStateException("Invalid state section; preserve file for operator recovery");
                    }
                }
                data.put("schemaVersion", 1);
                final var state = new State(data);
                final var result = operation.apply(state);
                final var temp = Files.createTempFile(home, ".state-", ".tmp");
                try {
                    Files.writeString(temp, json.toString(StateDocument.from(data)));
                    try (final var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                        output.force(true);
                    }
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    try (final var directory = FileChannel.open(home, StandardOpenOption.READ)) {
                        directory.force(true);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
                return result;
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private Map<String, Object> read(final Path path) throws IOException {
        final var content = Files.readString(path);
        // Generated record codecs intentionally treat absent and null fields alike. State recovery
        // must distinguish them, so validate top-level section tokens before binding the model.
        try (final var parser = new JsonParser(new StringReader(content), 8192, new BufferProvider(8192, 2), true)) {
            parser.enforceNext(Parser.Event.START_OBJECT);
            while (parser.hasNext()) {
                final var event = parser.next();
                if (event == Parser.Event.END_OBJECT) {
                    break;
                }
                if (event != Parser.Event.KEY_NAME) {
                    throw new IllegalStateException("Invalid state object");
                }
                final var key = parser.getString();
                final var value = parser.next();
                if (List.of("tasks", "cursors", "receipts").contains(key) && value != Parser.Event.START_OBJECT) {
                    throw new IllegalStateException("Invalid state section; preserve file for operator recovery");
                }
                if ("schemaVersion".equals(key)
                        && (value != Parser.Event.VALUE_NUMBER
                                || parser.getBigDecimal().compareTo(BigDecimal.ONE) != 0)) {
                    throw new IllegalStateException("Unsupported state schema; preserve file for operator recovery");
                }
                if (value == Parser.Event.START_OBJECT) {
                    parser.skipObject();
                } else if (value == Parser.Event.START_ARRAY) {
                    parser.skipArray();
                }
            }
        }
        return json.fromString(StateDocument.class, content).toMap();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(final Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static final class State {
        private final Map<String, Object> data;

        private State(final Map<String, Object> data) {
            this.data = data;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> tasks() {
            return (Map<String, Object>) data.computeIfAbsent("tasks", ignored -> new LinkedHashMap<>());
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> cursors() {
            return (Map<String, Object>) data.computeIfAbsent("cursors", ignored -> new LinkedHashMap<>());
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> receipts() {
            return (Map<String, Object>) data.computeIfAbsent("receipts", ignored -> new LinkedHashMap<>());
        }

        public String enqueue(final String invocation, final Map<String, Object> task) {
            if (receipts().containsKey(invocation)) {
                return String.valueOf(receipts().get(invocation));
            }
            final var id = UUID.randomUUID().toString();
            final var copy = new LinkedHashMap<>(task);
            copy.put("id", id);
            copy.put("state", "pending");
            copy.put("attempts", 0);
            copy.put("runs", new ArrayList<>());
            tasks().put(id, copy);
            receipts().put(invocation, id);
            return id;
        }

        public Map<String, Object> task(final String id) {
            final var value = tasks().get(id);
            if (value == null) {
                throw new IllegalArgumentException("Unknown task: " + id);
            }
            return map(value);
        }

        public List<Map<String, Object>> list() {
            return tasks().values().stream()
                    .map(TaskStore::map)
                    .map(LinkedHashMap::new)
                    .map(it -> (Map<String, Object>) it)
                    .toList();
        }
    }
}
