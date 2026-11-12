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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.github.TaskDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Persisted queue schema; task keys and cursor endpoint keys are intentionally dynamic. */
@JsonModel
public record StateDocument(
        @Property(documentation = "Queue schema version; currently 1.")
        Object schemaVersion,

        @Property(documentation = "Durable tasks indexed by identifier.")
        Map<String, TaskDocument> tasks,

        @Property(documentation = "Polling watermarks and continuations indexed by endpoint.")
        Map<String, Object> cursors,

        @Property(documentation = "Invocation receipts retained independently from tasks.")
        Map<String, Object> receipts) {
    public Map<String, Object> toMap() {
        final var result = new LinkedHashMap<String, Object>();
        if (schemaVersion != null) {
            result.put("schemaVersion", schemaVersion);
        }
        if (tasks != null) {
            final var values = new LinkedHashMap<String, Object>();
            tasks.forEach((id, task) -> values.put(id, task.toMap()));
            result.put("tasks", values);
        }
        if (cursors != null) {
            result.put("cursors", new LinkedHashMap<>(cursors));
        }
        if (receipts != null) {
            result.put("receipts", new LinkedHashMap<>(receipts));
        }
        return result;
    }

    public static StateDocument from(final Map<String, Object> source) {
        final var tasks = source.get("tasks") == null
                ? null
                : TaskStore.map(source.get("tasks")).entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> TaskDocument.from(TaskStore.map(entry.getValue())),
                                (a, b) -> a,
                                LinkedHashMap::new));
        return new StateDocument(
                source.get("schemaVersion"),
                tasks,
                source.get("cursors") == null ? null : TaskStore.map(source.get("cursors")),
                source.get("receipts") == null ? null : TaskStore.map(source.get("receipts")));
    }
}
