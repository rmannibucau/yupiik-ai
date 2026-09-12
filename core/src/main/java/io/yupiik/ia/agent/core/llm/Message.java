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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonModel
public record Message(
        String role,
        Object content,
        String name,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonOthers Map<String, Object> others) {

    public static Message system(final String content) {
        return new Message("system", content, null, null, null, null);
    }

    public static Message user(final String content) {
        return new Message("user", content, null, null, null, null);
    }

    public static Message assistant(final String content, final List<ToolCall> toolCalls) {
        return new Message("assistant", content, null, toolCalls, null, null);
    }

    public static Message tool(final String toolCallId, final String content) {
        return new Message("tool", content, null, null, toolCallId, null);
    }
}
