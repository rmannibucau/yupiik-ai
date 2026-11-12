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
import java.util.Map;

/** Agent-produced result; semantic and head/diff validation remains host-owned. */
@JsonModel
public record AgentResult(
        @Property(documentation = "Structured result schema version.")
        Object schemaVersion,

        @Property(documentation = "Task correlation identifier.")
        String taskId,

        @Property(documentation = "Agent outcome summary.") String summary,

        @Property(documentation = "Failure explanation, when unsuccessful.")
        String failure,

        @JsonOthers @Property(documentation = "PR, native-review and test evidence validated before publication.")
        Map<String, Object> evidence) {
    public Map<String, Object> toMap() {
        final var result = new LinkedHashMap<>(evidence == null ? Map.<String, Object>of() : evidence);
        result.put("schemaVersion", schemaVersion);
        result.put("taskId", taskId);
        if (summary != null) {
            result.put("summary", summary);
        }
        if (failure != null) {
            result.put("failure", failure);
        }
        return result;
    }
}
