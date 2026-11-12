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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A task at a persistence or command-output boundary, including durable recovery extensions. */
@JsonModel
public record TaskDocument(
        @Property(documentation = "Durable task identifier.")
        String id,

        @Property(documentation = "Upstream owner/repository.")
        String repository,

        @Property(documentation = "Issue or pull request number.")
        Object number,

        @Property(documentation = "Task kind: solve, review or help.")
        String kind,

        @Property(documentation = "Untrusted request text.") String request,
        @Property(documentation = "Creation instant.") String createdAt,

        @Property(documentation = "Canonical invocation reference.")
        String reference,

        @Property(documentation = "Queue lifecycle state.") String state,

        @Property(documentation = "Number of execution attempts.")
        Object attempts,

        @JsonOthers @Property(documentation = "Durable authorization, execution and publication recovery fields.")
        Map<String, Object> recovery) {
    public static TaskDocument from(final Map<String, Object> source) {
        final var remaining = new LinkedHashMap<>(source);
        return new TaskDocument(
                text(remaining.remove("id")),
                text(remaining.remove("repository")),
                remaining.remove("number"),
                text(remaining.remove("kind")),
                text(remaining.remove("request")),
                text(remaining.remove("createdAt")),
                text(remaining.remove("reference")),
                text(remaining.remove("state")),
                remaining.remove("attempts"),
                remaining);
    }

    public Map<String, Object> toMap() {
        final var result = new LinkedHashMap<>(recovery == null ? Map.<String, Object>of() : recovery);
        put(result, "id", id);
        put(result, "repository", repository);
        put(result, "number", number);
        put(result, "kind", kind);
        put(result, "request", request);
        put(result, "createdAt", createdAt);
        put(result, "reference", reference);
        put(result, "state", state);
        put(result, "attempts", attempts);
        return result;
    }

    private static String text(final Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Invalid task text field");
        }
        return text;
    }

    private static void put(final Map<String, Object> target, final String key, final Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    @JsonModel
    public record TaskList(
            @Property(documentation = "Tasks matching the requested filters.")
            List<TaskDocument> tasks) {}
}
