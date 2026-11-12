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
package io.yupiik.ia.agent.github;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.store.StateDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskDocumentTest {
    @Test
    void roundTripsTaskRecoveryFieldsWithoutChangingDurableShape() {
        try (final var json = BotJsonMapper.create(List.of(), ignored -> Optional.empty())) {
            final var source = Map.<String, Object>of(
                    "id",
                    "task-1",
                    "repository",
                    "acme/repo",
                    "kind",
                    "review",
                    "state",
                    "publishing",
                    "attempts",
                    2,
                    "reviewIntent",
                    "marker",
                    "runtimeId",
                    "yca-runtime",
                    "result",
                    Map.of("summary", "done"));
            final var document = TaskDocument.from(source);
            final var encoded = json.toString(document);
            assertFalse(encoded.contains("\"recovery\""));
            final var restored = json.fromString(TaskDocument.class, encoded).toMap();
            assertEquals(source.keySet(), restored.keySet());
            assertEquals("marker", restored.get("reviewIntent"));
            assertEquals("yca-runtime", restored.get("runtimeId"));
            assertEquals(Map.of("summary", "done"), restored.get("result"));
            assertEquals(2, ((Number) restored.get("attempts")).intValue());
            final var state = new StateDocument(
                    1, Map.of("task-1", document), Map.of("cursor", "next"), Map.of("invocation", "task-1"));
            final var decoded = json.fromString(StateDocument.class, json.toString(state));
            assertEquals("marker", decoded.tasks().get("task-1").recovery().get("reviewIntent"));
            assertEquals(state.receipts(), decoded.receipts());
        }
    }

    @Test
    void commandListHasDomainRootAndTypedTaskElements() {
        try (final var json = BotJsonMapper.create(List.of(), ignored -> Optional.empty())) {
            final var list =
                    new TaskDocument.TaskList(List.of(TaskDocument.from(Map.of("id", "task-1", "kind", "help"))));
            final var encoded = json.toString(list);
            assertTrue(encoded.startsWith("{\"tasks\":[{"));
            final var decoded = json.fromString(TaskDocument.TaskList.class, encoded);
            assertEquals("task-1", decoded.tasks().getFirst().id());
        }
    }
}
