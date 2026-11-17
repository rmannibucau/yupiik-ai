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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class TodoStorePersistenceTest {
    @TempDir
    Path home;

    @Test
    void roundTripsTheExistingArrayFormat(final JsonMapper mapper) throws IOException {
        final var items = List.of(
                new TodoItem("check \"quotes\" and\nnewlines", "in_progress"), new TodoItem("done", "completed"));
        store(mapper, null).replace(items);
        final var loaded = store(mapper, null);
        loaded.load();
        assertEquals(items, loaded.all());
        assertEquals(mapper.toString(items), Files.readString(home.resolve("todos.json")));
    }

    @Test
    void readsKnownFieldsAndIgnoresExtensions(final JsonMapper mapper) throws IOException {
        Files.writeString(home.resolve("todos.json"), """
                [{"status":"pending","extension":{"nested":[1,true]},"content":"step"},
                 {"content":"legacy"}]
                """);
        final var loaded = store(mapper, null);
        loaded.load();
        assertEquals(List.of(new TodoItem("step", "pending"), new TodoItem("legacy", null)), loaded.all());
    }

    @Test
    void emptyArrayClearsPreviouslyLoadedItems(final JsonMapper mapper) throws IOException {
        final var store = store(mapper, null);
        store.replace(List.of(new TodoItem("old", "pending")));
        Files.writeString(home.resolve("todos.json"), "[]");
        store.load();
        assertEquals(List.of(), store.all());
    }

    @Test
    void corruptOrWrongShapedFileKeepsTheCurrentList(final JsonMapper mapper) throws IOException {
        final var items = List.of(new TodoItem("keep", "pending"));
        final var store = store(mapper, null);
        store.replace(items);
        for (final var json : List.of("{broken", "{}", "null", "[null]", "[42]", "[{\"content\":{}}]")) {
            Files.writeString(home.resolve("todos.json"), json);
            store.load();
            assertEquals(items, store.all(), json);
        }
    }

    @Test
    void loadsLegacyKeyedFileWithoutRewritingIt(final JsonMapper mapper) throws IOException {
        final var key = "s123abcdef";
        final var path = home.resolve("todos-" + key + ".json");
        final var json = "[{\"content\":\"legacy\",\"status\":\"pending\"}]";
        Files.writeString(path, json);
        final var loaded = store(mapper, key);
        loaded.load();
        assertEquals(List.of(new TodoItem("legacy", "pending")), loaded.all());
        assertEquals(json, Files.readString(path));
        assertFalse(Files.exists(home.resolve("sessions").resolve(key).resolve("todos.json")));
    }

    @Test
    void generatedSessionsPersistSeparatelyAndPreferTheSessionFile(final JsonMapper mapper) throws IOException {
        final var first = List.of(new TodoItem("first", "pending"));
        final var second = List.of(new TodoItem("second", "completed"));
        store(mapper, "s123abc").replace(first);
        store(mapper, "s456def").replace(second);
        Files.writeString(home.resolve("todos-s123abc.json"), "[]");
        final var loadedFirst = store(mapper, "s123abc");
        final var loadedSecond = store(mapper, "s456def");
        loadedFirst.load();
        loadedSecond.load();
        assertEquals(first, loadedFirst.all());
        assertEquals(second, loadedSecond.all());
        assertEquals(mapper.toString(first), Files.readString(home.resolve("sessions/s123abc/todos.json")));
    }

    private TodoStore store(final JsonMapper mapper, final String key) {
        return new TodoStore(TestConfigs.codingAgent(Map.of("yca.storageHome", home.toString())), mapper, key);
    }
}
