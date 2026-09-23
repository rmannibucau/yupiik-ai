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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanTodoSeederTest {
    @TempDir
    Path dir;

    @Test
    void seedsTodosFromLastAssistantPlan() {
        final var sessions = newSessionStore();
        final var id = sessions.create();
        sessions.append(id, SessionEntry.of(1, "user", "plan a refactor", null, null));
        sessions.append(id, SessionEntry.of(2, "assistant", "earlier answer", null, null));
        sessions.append(
                id,
                SessionEntry.of(3, "assistant", "Plan:\n1. extract service\n2. add tests\n3. update docs", null, null));

        final var todos = newTodoStore();
        final var seeder = seeder(
                todos,
                sessions,
                "[{\"content\": \"extract service\", \"status\": \"pending\"}, "
                        + "{\"content\": \"add tests\", \"status\": \"pending\"}]");

        assertSame(PlanTodoSeeder.SeedResult.SEEDED, seeder.seedFromLastAssistant(id));
        final var all = todos.all();
        assertEquals(2, all.size());
        assertEquals("extract service", all.get(0).content());
        assertEquals("pending", all.get(0).status());
        assertEquals("add tests", all.get(1).content());
    }

    @Test
    void skipsWhenTodosAlreadyExist() {
        final var sessions = newSessionStore();
        final var id = sessions.create();
        sessions.append(id, SessionEntry.of(1, "assistant", "a plan", null, null));

        final var todos = newTodoStore();
        todos.replace(List.of(new TodoItem("existing", "pending")));
        final var seeder = seeder(todos, sessions, "[{\"content\": \"x\", \"status\": \"pending\"}]");

        assertSame(PlanTodoSeeder.SeedResult.ALREADY_POPULATED, seeder.seedFromLastAssistant(id));
        assertEquals(1, todos.all().size());
        assertEquals("existing", todos.all().get(0).content());
    }

    @Test
    void skipsWithoutAssistantMessage() {
        final var sessions = newSessionStore();
        final var id = sessions.create();
        sessions.append(id, SessionEntry.of(1, "user", "hi", null, null));

        final var todos = newTodoStore();
        final var seeder = seeder(todos, sessions, "[{\"content\": \"x\", \"status\": \"pending\"}]");

        assertSame(PlanTodoSeeder.SeedResult.NO_PLAN, seeder.seedFromLastAssistant(id));
        assertTrue(todos.all().isEmpty());
    }

    @Test
    void invalidResponseLeavesTodosEmpty() {
        final var sessions = newSessionStore();
        final var id = sessions.create();
        sessions.append(id, SessionEntry.of(1, "assistant", "a plan", null, null));

        final var todos = newTodoStore();
        final var seeder = seeder(todos, sessions, "sorry, I cannot answer that");

        assertSame(PlanTodoSeeder.SeedResult.NO_ACTIONABLE_STEPS, seeder.seedFromLastAssistant(id));
        assertTrue(todos.all().isEmpty());
    }

    @Test
    void emptyStepsArrayYieldsNoActionableSteps() {
        final var sessions = newSessionStore();
        final var id = sessions.create();
        sessions.append(id, SessionEntry.of(1, "assistant", "greeting, no plan here", null, null));

        final var todos = newTodoStore();
        final var seeder = seeder(todos, sessions, "{\"steps\":[]}");

        assertSame(PlanTodoSeeder.SeedResult.NO_ACTIONABLE_STEPS, seeder.seedFromLastAssistant(id));
        assertTrue(todos.all().isEmpty());
    }

    @Test
    void llmFailureYieldsDerivationFailed() {
        final var sessions = newSessionStore();
        final var id = sessions.create();
        sessions.append(id, SessionEntry.of(1, "assistant", "a plan", null, null));

        final var todos = newTodoStore();
        final var seeder = new PlanTodoSeeder(new FailingChatClient(), todos, sessions, mapper());

        assertSame(PlanTodoSeeder.SeedResult.DERIVATION_FAILED, seeder.seedFromLastAssistant(id));
        assertTrue(todos.all().isEmpty());
    }

    @Test
    void invalidItemsAreDropped() {
        final var sessions = newSessionStore();
        final var id = sessions.create();
        sessions.append(id, SessionEntry.of(1, "assistant", "a plan", null, null));

        final var todos = newTodoStore();
        final var seeder = seeder(
                todos,
                sessions,
                "[{\"content\": \"ok\", \"status\": \"pending\"}, "
                        + "{\"content\": \"\", \"status\": \"pending\"}, "
                        + "{\"content\": \"bad status\", \"status\": \"later\"}]");

        assertSame(PlanTodoSeeder.SeedResult.SEEDED, seeder.seedFromLastAssistant(id));
        assertEquals(1, todos.all().size());
        assertEquals("ok", todos.all().get(0).content());
    }

    private PlanTodoSeeder seeder(final TodoStore todos, final SessionStore sessions, final String llmResponse) {
        return new PlanTodoSeeder(new StubChatClient(llmResponse), todos, sessions, mapper());
    }

    private TodoStore newTodoStore() {
        return new TodoStore(configuration(), mapper());
    }

    private SessionStore newSessionStore() {
        return new FileSessionStore(configuration(), mapper(), Clock.systemUTC());
    }

    private CodingAgentConfiguration configuration() {
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.of("yca.storageHome", dir.toString()));
    }

    private JsonMapper mapper() {
        return new JsonMapperImpl(
                List.of(
                        new io.yupiik.ia.agent.core.session.SessionEntry$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.Message$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.ToolCall$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.ToolCall$FunctionCall$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.Usage$FusionJsonCodec(),
                        new TodoItem$FusionJsonCodec()),
                key -> Optional.empty());
    }

    private static final class StubChatClient extends ChatClient {
        private final String response;

        private StubChatClient(final String response) {
            super();
            this.response = response;
        }

        @Override
        public ChatResult chat(final ChatCompletionRequest request) {
            return new ChatResult(Message.assistant(response, null), Usage.EMPTY);
        }

        @Override
        public StructuredResult chatStructured(
                final ChatCompletionRequest request,
                final String schemaName,
                final java.util.Map<String, Object> schema) {
            return new StructuredResult(new ChatResult(Message.assistant(response, null), Usage.EMPTY), false);
        }
    }

    private static final class FailingChatClient extends ChatClient {
        @Override
        public ChatResult chat(final ChatCompletionRequest request) {
            throw new IllegalStateException("boom");
        }

        @Override
        public StructuredResult chatStructured(
                final ChatCompletionRequest request,
                final String schemaName,
                final java.util.Map<String, Object> schema) {
            throw new IllegalStateException("boom");
        }
    }
}
