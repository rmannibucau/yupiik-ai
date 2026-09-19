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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.Message$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FunctionCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.Usage$FusionJsonCodec;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionEntry$FusionJsonCodec;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionLifecycleTest {
    @TempDir
    Path dir;

    private SessionStore newStore() {
        final JsonMapper mapper = new JsonMapperImpl(
                List.of(
                        new SessionEntry$FusionJsonCodec(),
                        new Message$FusionJsonCodec(),
                        new ToolCall$FusionJsonCodec(),
                        new ToolCall$FunctionCall$FusionJsonCodec(),
                        new Usage$FusionJsonCodec()),
                key -> Optional.empty());
        return new SessionStore(
                TestConfigs.codingAgent(Map.of("yca.storageHome", dir.toString())), mapper, Clock.systemUTC());
    }

    private SessionLifecycle newLifecycle(final SessionStore store) {
        return new SessionLifecycle(
                store, new PermissionService(TestConfigs.codingAgent(Map.of())), new UserFeedbackService());
    }

    @Test
    void resolvesSessionThenContextThenCreates() {
        final var store = newStore();
        final var lifecycle = newLifecycle(store);

        assertEquals("s1", lifecycle.resolveSessionId("s1", "ignored.jsonl"));

        final var fromContext = lifecycle.resolveSessionId(" ", "some/dir/bar.jsonl");
        assertEquals("bar", fromContext);

        final var created = lifecycle.resolveSessionId(null, null);
        assertFalse(created == null || created.isBlank());
        assertNotEquals("s1", created);
    }

    @Test
    void prepareContextFileCreatesMissingFileAndSkipsBlank() throws Exception {
        final var lifecycle = newLifecycle(newStore());

        final var target = dir.resolve("nested").resolve("ctx.jsonl");
        lifecycle.prepareContextFile(target, "custom.jsonl");
        assertTrue(Files.isRegularFile(target));
        assertEquals("", Files.readString(target));

        final var untouched = dir.resolve("never.jsonl");
        lifecycle.prepareContextFile(untouched, " ");
        assertFalse(Files.exists(untouched));
    }

    @Test
    void installAppliesPermitAllAndReleaseKeepsNonEmptySession() {
        final var store = newStore();
        final var lifecycle = newLifecycle(store);
        final ApprovalGate approval = (title, detail, toolName, args) -> ApprovalGate.Result.DENY;
        final FeedbackGate feedback = questions -> Map.of();

        lifecycle.installGates(approval, feedback, true);
        assertTrue(lifecycle.permissionService().isPermitAll());

        final var sessionId = store.create();
        store.append(sessionId, SessionEntry.of(store.now(), "user", "hello", null, null));
        store.append(sessionId, SessionEntry.of(store.now(), "assistant", "hi there", null, null));
        assertFalse(store.isEmpty(sessionId), "precondition: session holds a real exchange");

        lifecycle.release(sessionId);
        assertFalse(store.isEmpty(sessionId), "non-empty session must survive");
        assertFalse(lifecycle.permissionService().isPermitAll());
    }
}
