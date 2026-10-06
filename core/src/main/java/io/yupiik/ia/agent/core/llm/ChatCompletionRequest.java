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

import java.util.List;
import java.util.Map;

/**
 * Core chat request: wire fields plus the sender's per-session selection
 * ({@link ChatSettings}). The selection is resolved by {@link ChatClient} into
 * the concrete wire values (model, sampling, extras) before translation.
 *
 * <p>The {@code settings} component is core-internal state: it never crosses
 * into {@code llm-client} and is never serialized — only {@code LlmWire}
 * maps this type to the wire request, field by field, and deliberately skips
 * {@code settings}. Keep it that way: a plain record, deliberately NOT a
 * fusion JSON model (no {@code @JsonModel}/{@code @JsonProperty}), so the
 * fusion works records-only and the selection can never leak to the wire.</p>
 *
 * @param model    explicit model reference (plain name, or {@code $provider/model}); when blank the
 *                 settings model applies, then the effective provider's configured model. An
 *                 explicit value always wins over the settings selection.
 * @param settings the sender's session selection; {@link ChatSettings#EMPTY} (the default) means
 *                 the process default provider and model apply
 */
public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Double topP,
        Boolean stream,
        List<Tool> tools,
        String toolChoice,
        Integer maxTokens,
        String reasoningEffort,
        Map<String, Object> others,
        ChatSettings settings) {

    /** Process-default selection ({@link ChatSettings#EMPTY}). */
    public ChatCompletionRequest(
            String model,
            List<Message> messages,
            Double temperature,
            Double topP,
            Boolean stream,
            List<Tool> tools,
            String toolChoice,
            Integer maxTokens,
            String reasoningEffort,
            Map<String, Object> others) {
        this(
                model,
                messages,
                temperature,
                topP,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                others,
                ChatSettings.EMPTY);
    }

    /** Process-default selection, {@code topP = null}. */
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
        this(
                model,
                messages,
                temperature,
                null,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                others,
                ChatSettings.EMPTY);
    }

    /** A copy carrying this sender's session selection (replacing the previous one). */
    public ChatCompletionRequest withSettings(final ChatSettings value) {
        return new ChatCompletionRequest(
                model,
                messages,
                temperature,
                topP,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                others,
                value);
    }
}
