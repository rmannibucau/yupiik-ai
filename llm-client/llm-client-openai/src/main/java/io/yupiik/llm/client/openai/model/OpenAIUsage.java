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
package io.yupiik.llm.client.openai.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.llm.client.model.Usage;

/**
 * Token usage reported by the OpenAI endpoints.
 *
 * @param promptTokens prompt tokens consumed.
 * @param completionTokens completion tokens produced.
 * @param totalTokens total tokens consumed.
 * @param promptTokensDetails prompt token breakdown (cached tokens, ...).
 */
@JsonModel
public record OpenAIUsage(
        @JsonProperty("prompt_tokens") Long promptTokens,
        @JsonProperty("completion_tokens") Long completionTokens,
        @JsonProperty("total_tokens") Long totalTokens,
        @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails) {

    /**
     * Prompt token breakdown reported by OpenAI endpoints.
     *
     * @param cachedTokens prompt tokens served from the automatic prompt cache.
     */
    @JsonModel
    public record PromptTokensDetails(
            @JsonProperty("cached_tokens") Long cachedTokens) {}

    /**
     * Maps the wire usage to the neutral model, summing prompt and completion when the total is absent.
     *
     * @return the neutral usage, {@link Usage#EMPTY} when no data is present.
     */
    public Usage toNeutral() {
        if (this == null || (promptTokens == null && completionTokens == null && totalTokens == null)) {
            return Usage.EMPTY;
        }
        return new Usage(
                promptTokens == null ? 0 : promptTokens,
                completionTokens == null ? 0 : completionTokens,
                totalTokens == null
                        ? (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens)
                        : totalTokens,
                promptTokensDetails == null ? null : promptTokensDetails.cachedTokens(),
                null);
    }
}
