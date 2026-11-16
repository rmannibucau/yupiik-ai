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
package io.yupiik.ia.agent.slack.service;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackAdmissionTest {
    @TempDir
    Path root;

    @Test
    void denyUnauthorizedAndNonUserEventsBeforeStateCreation() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var admission = new SlackAdmission(fixture.configuration, new SlackMentionParser(), fixture.store);
            for (final var field : new String[] {"team_id", "type", "event_id"}) {
                final var invalid = new HashMap<>(event("Ev1", "U1", "C1", "1.1"));
                invalid.put(field, "wrong");
                assertTrue(admission.admit(invalid).isEmpty(), field);
            }
            for (final var override : List.of(
                    Map.of("user", "U3"),
                    Map.of("user", "UBOT"),
                    Map.of("channel", "C3"),
                    Map.of("bot_id", "B1"),
                    Map.of("subtype", "message_changed"),
                    Map.of("type", "message"),
                    Map.of("thread_ts", "not-a-timestamp"),
                    Map.of("text", "no mention"),
                    Map.of("is_ext_shared_channel", true))) {
                final var invalid = new HashMap<>(event("Ev1", "U1", "C1", "1.1"));
                final Map<Object, Object> inner = new HashMap<>((Map<?, ?>) invalid.get("event"));
                inner.putAll(override);
                invalid.put("event", inner);
                assertTrue(admission.admit(invalid).isEmpty(), override.toString());
            }
            final var shared = new HashMap<>(event("Ev1", "U1", "C1", "1.1"));
            shared.put("is_ext_shared_channel", true);
            assertTrue(admission.admit(shared).isEmpty());
            shared.remove("is_ext_shared_channel");
            assertTrue(admission.admit(shared).isEmpty());
            shared.put("is_ext_shared_channel", "false");
            assertTrue(admission.admit(shared).isEmpty());
            assertFalse(Files.exists(fixture.configuration.stateRoot()));
        }
    }

    @Test
    void dedupePrecedesQuotaAndSurvivesNewStoreInstance() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 1, 1)) {
            final var admission = new SlackAdmission(fixture.configuration, new SlackMentionParser(), fixture.store);
            final var payload = event("Ev1", "U1", "C1", "1.1");
            final var id = admission.admit(payload).orElseThrow();
            assertEquals(id, admission.admit(payload).orElseThrow());
            assertTrue(admission.admit(event("Ev2", "U2", "C1", "1.1")).isEmpty());
            final var reopened = new SlackStore(fixture.configuration, fixture.json);
            assertEquals(1, reopened.list().size());
            assertEquals(
                    id,
                    new SlackAdmission(fixture.configuration, new SlackMentionParser(), reopened)
                            .admit(payload)
                            .orElseThrow());
        }
    }

    @Test
    void conversationIsOwnedByRequesterAndScopedToChannelAndThread() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 6, 4)) {
            final var admission = new SlackAdmission(fixture.configuration, new SlackMentionParser(), fixture.store);
            admission.admit(event("Ev1", "U1", "C1", "1.1")).orElseThrow();
            admission.admit(event("Ev2", "U1", "C1", "1.1")).orElseThrow();
            admission.admit(event("Ev3", "U2", "C1", "1.1")).orElseThrow();
            admission.admit(event("Ev4", "U1", "C2", "1.1")).orElseThrow();
            admission.admit(event("Ev5", "U1", "C1", "2.1")).orElseThrow();
            final var tasks = fixture.store.list();
            assertEquals(tasks.get(0).conversation(), tasks.get(1).conversation());
            assertEquals(
                    4,
                    tasks.stream().map(task -> task.conversation()).distinct().count());
            assertEquals(SlackStore.identity("T1:C1:1.1:U1"), tasks.getFirst().conversation());
        }
    }

    @Test
    void requesterQuotaDoesNotConsumeOtherRequestersCapacity() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 3, 1)) {
            final var admission = new SlackAdmission(fixture.configuration, new SlackMentionParser(), fixture.store);
            admission.admit(event("Ev1", "U1", "C1", "1.1")).orElseThrow();
            assertTrue(admission.admit(event("Ev2", "U1", "C1", "2.1")).isEmpty());
            assertTrue(admission.admit(event("Ev3", "U2", "C1", "1.1")).isPresent());
        }
    }

    private Map<String, Object> event(final String id, final String user, final String channel, final String thread) {
        return Map.of(
                "type",
                "event_callback",
                "team_id",
                "T1",
                "event_id",
                id,
                "is_ext_shared_channel",
                false,
                "event",
                Map.of(
                        "type",
                        "app_mention",
                        "user",
                        user,
                        "channel",
                        channel,
                        "ts",
                        "9.1",
                        "thread_ts",
                        thread,
                        "text",
                        "<@UBOT> Please explain this error."));
    }
}
