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
package io.yupiik.ia.agent.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.SessionConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {
    @TempDir
    Path dir;

    private SessionStore newStore() {
        return newStore(new SessionConfiguration(30, 15));
    }

    private SessionStore newStore(final SessionConfiguration sessions) {
        final JsonMapper mapper = new JsonMapperImpl(
                List.of(
                        new SessionEntry$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.Message$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.ToolCall$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.ToolCall$FunctionCall$FusionJsonCodec(),
                        new io.yupiik.ia.agent.core.llm.Usage$FusionJsonCodec()),
                key -> Optional.empty());
        return new SessionStore(
                io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.of(
                        "yca.storageHome", dir.toString(),
                        "yca.sessions.retentionDays", String.valueOf(sessions.retentionDays()),
                        "yca.sessions.compressDays", String.valueOf(sessions.compressDays()))),
                mapper,
                Clock.systemUTC());
    }

    private static void setAge(final Path file, final long daysOld) throws Exception {
        Files.setLastModifiedTime(
                file, FileTime.fromMillis(Instant.now().toEpochMilli() - daysOld * 24L * 60L * 60L * 1000L));
    }

    @Test
    void cleanupAndListToleratePristineStorage() {
        final var store = newStore();
        assertFalse(Files.exists(store.dir()), "precondition: nothing stored yet");

        store.cleanup();

        assertEquals(List.of(), store.list());
        assertNull(store.lastSessionId());
        assertFalse(Files.exists(store.dir()), "a read must not create the storage directory");
    }

    @Test
    void cleanupDropsExpiredAndCompressesAged() throws Exception {
        final var store = newStore();
        final var recent = store.create();
        final var aged = store.create();
        final var expired = store.create();

        store.append(recent, SessionEntry.of(1, "user", "recent", null, null));
        store.append(aged, SessionEntry.of(1, "user", "aged", null, null));
        store.append(expired, SessionEntry.of(1, "user", "expired", null, null));

        setAge(store.path(aged), 20);
        setAge(store.path(expired), 40);

        store.cleanup();

        assertTrue(Files.exists(store.path(recent)));
        assertFalse(Files.exists(store.path(expired)));
        assertFalse(Files.exists(store.path(aged)));
        assertTrue(Files.exists(store.dir().resolve(aged + ".jsonl.gz")));

        final var ids = store.list().stream().map(SessionStore.SessionInfo::id).toList();
        assertTrue(ids.contains(recent));
        assertTrue(ids.contains(aged));
        assertFalse(ids.contains(expired));
    }

    @Test
    void cleanupWithDefaultsDisabled() throws Exception {
        final var store = newStore(new SessionConfiguration(0, 0));
        final var first = store.create();
        final var second = store.create();
        setAge(store.path(first), 40);
        setAge(store.path(second), 20);

        store.cleanup();

        assertTrue(Files.exists(store.path(first)));
        assertTrue(Files.exists(store.path(second)));
    }

    @Test
    void loadDecompressesCompressedSessionAndDropsGz() throws Exception {
        final var store = newStore();
        final var id = store.create();
        store.append(id, SessionEntry.of(1, "user", "hi", null, null));
        store.append(id, SessionEntry.of(2, "assistant", "there", null, null));

        setAge(store.path(id), 20);
        store.cleanup();
        assertFalse(Files.exists(store.path(id)));
        final var gz = store.dir().resolve(id + ".jsonl.gz");
        assertTrue(Files.exists(gz));

        final var loaded = store.load(id);
        assertEquals(2, loaded.size());
        assertEquals("user", loaded.get(0).role());
        assertTrue(Files.exists(store.path(id)));
        assertFalse(Files.exists(gz));
    }

    @Test
    void createAppendLoad() {
        final var store = newStore();
        final var id = store.create();
        assertTrue(Files.exists(store.path(id)));

        store.append(id, SessionEntry.of(1, "user", "hi", null, null));
        store.append(
                id,
                SessionEntry.of(
                        2,
                        "assistant",
                        "reading",
                        List.of(new ToolCall(
                                "c1", "function", new FunctionCall("read_file", "{\"path\":\"a\"}"), null)),
                        "c1"));

        final var loaded = store.load(id);
        assertEquals(2, loaded.size());
        assertEquals("user", loaded.get(0).role());
        assertEquals("hi", loaded.get(0).content());
        assertEquals("assistant", loaded.get(1).role());
        assertEquals("reading", loaded.get(1).content());
        assertEquals("read_file", loaded.get(1).toolCalls().get(0).function().name());
        assertEquals("c1", loaded.get(1).toolCallId());
    }

    @Test
    void replaceRewritesFile() {
        final var store = newStore();
        final var id = store.create();
        store.append(id, SessionEntry.of(1, "user", "old", null, null));
        store.replace(id, List.of(Message.system("summary"), Message.user("new")));

        final var loaded = store.load(id);
        assertEquals(2, loaded.size());
        assertEquals("system", loaded.get(0).role());
        assertEquals("new", loaded.get(1).content());
    }

    @Test
    void listAndLastSession() {
        final var store = newStore();
        final var first = store.create();
        final var second = store.create();
        store.append(first, SessionEntry.of(1, "user", "x", null, null));

        final var infos = store.list();
        assertEquals(2, infos.size());
        final var ids = infos.stream().map(SessionStore.SessionInfo::id).toList();
        assertTrue(ids.contains(first));
        assertTrue(ids.contains(second));
        assertTrue(ids.contains(store.lastSessionId()));
    }

    @Test
    void loadMissingSessionIsEmpty() {
        final var store = newStore();
        assertTrue(store.load("missing").isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void summaryReturnsFirstUserLine() {
        final var store = newStore();
        final var id = store.create();
        store.append(id, SessionEntry.of(1, "assistant", "ignored", null, null));
        store.append(id, SessionEntry.of(2, "user", "first line\nsecond", null, null));
        store.append(id, SessionEntry.of(3, "user", "another", null, null));
        assertEquals("first line", store.summary(id));
    }

    @Test
    void summarySkipsBlankUserAndMissingSession() {
        final var store = newStore();
        assertEquals("", store.summary("missing"));

        final var blank = store.create();
        store.append(blank, SessionEntry.of(1, "user", "   ", null, null));
        store.append(blank, SessionEntry.of(2, "user", "hello there", null, null));
        assertEquals("hello there", store.summary(blank));

        final var noUser = store.create();
        store.append(noUser, SessionEntry.of(1, "assistant", "only assistant", null, null));
        assertEquals("", store.summary(noUser));
    }

    @Test
    void isEmptyDetectsInitialPromptOnly() {
        final var store = newStore();
        final var empty = store.create();
        assertTrue(store.isEmpty(empty), "an empty file is empty");

        final var onlyPrompt = store.create();
        store.append(onlyPrompt, SessionEntry.of(1, "user", "hi", null, null));
        assertTrue(store.isEmpty(onlyPrompt), "only the initial user prompt is empty");

        final var hasReply = store.create();
        store.append(hasReply, SessionEntry.of(1, "user", "hi", null, null));
        store.append(hasReply, SessionEntry.of(2, "assistant", "hello", null, null));
        assertFalse(store.isEmpty(hasReply));

        final var toolWithoutAudio = store.create();
        store.append(toolWithoutAudio, SessionEntry.of(1, "user", "hi", null, null));
        store.append(toolWithoutAudio, SessionEntry.of(2, "tool", "out", null, "ls"));
        assertFalse(store.isEmpty(toolWithoutAudio));

        final var missing = store.create();
        store.delete(missing);
        assertTrue(store.isEmpty("missing"), "a missing session is empty");
    }

    @Test
    void housekeepingDeletesEmptySessions() throws Exception {
        final var store = newStore(new SessionConfiguration(0, 0));
        final var onlyPrompt = store.create();
        store.append(onlyPrompt, SessionEntry.of(1, "user", "hi", null, null));

        final var withReply = store.create();
        store.append(withReply, SessionEntry.of(1, "user", "hi", null, null));
        store.append(withReply, SessionEntry.of(2, "assistant", "hello", null, null));

        final var report = store.housekeeping(true);

        assertFalse(Files.exists(store.path(onlyPrompt)), "empty session removed");
        assertTrue(Files.exists(store.path(withReply)), "non-empty session kept");
        assertEquals(1, report.deleted());
        assertEquals(1, report.kept());

        final var withoutEmptyDeletion = store.housekeeping(false);
        assertTrue(Files.exists(store.path(withReply)));
        assertEquals(0, withoutEmptyDeletion.deleted());
    }
}
