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
import java.util.Map;

@JsonModel
public record ResponsesInput(
        String type,
        String role,
        Object content,
        String name,
        @JsonProperty("call_id") String callId,
        @JsonProperty("output") String output,
        @JsonProperty("arguments") String arguments,
        @JsonProperty("id") String id,
        @JsonOthers Map<String, Object> others) {

    public static ResponsesInput message(final String role, final String text) {
        return new ResponsesInput("message", role, text, null, null, null, null, null, null);
    }

    public static ResponsesInput functionCall(final String callId, final String name, final String arguments) {
        return new ResponsesInput(
                "function_call", null, null, null, callId, null, arguments, null, Map.of("name", name));
    }

    public static ResponsesInput functionCallOutput(final String callId, final String output) {
        return new ResponsesInput("function_call_output", null, null, null, callId, output, null, null, null);
    }
}
