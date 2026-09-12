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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/** Anthropic messages API response. */
@JsonModel
public record AnthropicMessagesResponse(
        String id,
        String type,
        String role,
        List<ContentBlock> content,
        String model,
        @JsonProperty("stop_reason") String stopReason,
        @JsonProperty("stop_sequence") String stopSequence,
        AnthropicUsage usage) {

    /** A text, thinking or tool use ({@code id}/{@code name}/{@code input}) block. */
    @JsonModel
    public record ContentBlock(String type, String text, String thinking, String id, String name, Object input) {}

    @JsonModel
    public record AnthropicUsage(
            @JsonProperty("input_tokens") Long inputTokens,
            @JsonProperty("output_tokens") Long outputTokens) {}
}
