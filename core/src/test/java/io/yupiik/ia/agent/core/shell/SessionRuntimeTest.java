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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SessionRuntimeFactory} isolation: every session gets its own
 * permissions, feedback gate, usage tracker, todo list and chat settings, and
 * nothing leaks from one tab into another (the original motivation for the
 * tab work: unrelated processes shared nothing; tabs must share the process
 * budget but nothing conversation-scoped).
 */
@ExtendWith(TestJsonMapper.class)
class SessionRuntimeTest {

    @TempDir
    Path storageHome;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private SessionRuntimeFactory factory() {
        final var props = new HashMap<String, String>();
        props.put("yca.storageHome", storageHome.toString());
        final CodingAgentConfiguration configuration = TestConfigs.codingAgent(props);
        return new SessionRuntimeFactory(
                null,
                null,
                null,
                configuration,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                mapper,
                null,
                Clock.systemUTC(),
                null);
    }

    @Test
    void permissionsAndTodosAreIsolatedPerSession() {
        final var factory = factory();
        final var first = factory.create("k1", "s1");
        final var second = factory.create("k2", "s2");

        assertTrue(first.permissions() != second.permissions());
        first.permissions().setMode(AgentMode.BUILD);
        assertEquals(AgentMode.BUILD, first.permissions().mode());
        assertFalse(second.permissions().mode() == AgentMode.BUILD, "mode of one tab must not leak to the other");

        first.todos().replace(List.of(new TodoItem("only in tab one", "pending")));
        assertEquals(1, first.todos().all().size());
        assertTrue(second.todos().all().isEmpty(), "todos of one tab must not leak to the other");

        assertTrue(first.usage() != second.usage());
        assertTrue(first.feedback() != second.feedback());
        assertTrue(first.loop() != second.loop());
    }

    @Test
    void todosArePersistedPerSessionKey() throws Exception {
        final var factory = factory();
        factory.create("k1", "s1").todos().replace(List.of(new TodoItem("step", "pending")));

        final var file = storageHome.resolve("todos-k1.json");
        assertTrue(Files.isRegularFile(file), "the tab todo list is stored under its own key");
        assertFalse(Files.exists(storageHome.resolve("todos-k2.json")));

        // a later runtime for the same key reloads its own list, a different key starts empty
        assertEquals(1, factory.create("k1", "s1-again").todos().all().size());
        assertTrue(factory.create("k2", "s2").todos().all().isEmpty());
    }

    @Test
    void sessionShapedKeysPersistTodosInTheSessionFolder() throws Exception {
        final var factory = factory();
        final var key = "s1a0e47e830a";
        factory.create(key, "s1").todos().replace(List.of(new TodoItem("step", "pending")));

        final var file = storageHome.resolve("sessions").resolve(key).resolve("todos.json");
        assertTrue(Files.isRegularFile(file), "a session-shaped key stores todos in the session folder");
        assertFalse(Files.exists(storageHome.resolve("todos-" + key + ".json")), "no legacy root file anymore");

        // a later runtime for the same key reloads its own list
        assertEquals(1, factory.create(key, "s1-again").todos().all().size());
    }

    @Test
    void chatSettingsArePerSessionAndSharedWithTheLoop() {
        final var factory = factory();
        final var first = factory.create("k1", "s1");
        final var second = factory.create("k2", "s2");

        assertSame(first.loop().chatSettings(), first.chatSettings(), "the runtime exposes the loop's settings");
        first.updateChatSettings(first.chatSettings().withModel("model-a"));
        assertEquals("model-a", first.chatSettings().model());
        assertNull(second.chatSettings().model(), "model selection of one tab must not leak to the other");
    }
}
