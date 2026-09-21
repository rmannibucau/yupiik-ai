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
public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Double topP,
        Boolean stream,
        List<Tool> tools,
        @JsonProperty("tool_choice") String toolChoice,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("reasoning_effort") String reasoningEffort,
        @JsonOthers Map<String, Object> others) {

    public ChatCompletionRequest(
            String model,
            List<Message> messages,
            Double temperature,
            Boolean stream,
            List<Tool> tools,
            String toolChoice,
            Integer maxTokens,
            String reasoningEffort,
            Map<String, Object> others) {
        this(model, messages, temperature, null, stream, tools, toolChoice, maxTokens, reasoningEffort, others);
    }
}
