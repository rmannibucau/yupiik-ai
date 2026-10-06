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
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import java.util.List;
import java.util.Map;

@JsonModel
public record ModelsResponse(List<Model> data) {

    /** Context window used when neither the provider configuration nor the /v1/models vendor extensions provide one. */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /**
     * Conservative context window assumed when no configuration, no provider metadata and no
     * vendor extension provide a figure. Kept intentionally low (much lower than the permissive
     * {@link #DEFAULT_CONTEXT_WINDOW}) because guessing too high on a small-window model turns
     * every request into a {@code ContextWindowExceededException}. It is the "unknown window"
     * default, not a claim about any particular model.
     */
    public static final int AUTO_DETECTED_BASELINE = 32_000;

    public List<String> ids() {
        return data == null
                ? List.of()
                : data.stream()
                        .map(Model::id)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
    }

    @JsonModel
    public record Model(
            String id,
            @JsonProperty("owned_by") String ownedBy,
            @JsonOthers Map<String, Object> others) {

        /**
         * Best effort context window extraction from the vendor extensions: flat keys first, then
         * the llama.cpp effective {@code meta.n_ctx}, then {@code meta.n_ctx_train}. The runtime
         * {@code meta.n_ctx} is preferred over the training value because the effective KV cache
         * can be far smaller than {@code n_ctx_train}; when only the training window is present it
         * is still used as a best-effort upper bound rather than assuming a huge default.
         * Anthropic exposes it as {@code max_input_tokens}. Detected values are totals
         * (prompt + completion combined).
         */
        public Integer contextWindow() {
            final var primary = firstPositive(
                    "/context_window",
                    "/model_context_window",
                    "/context_length",
                    "/max_model_len",
                    "/max_input_tokens",
                    "/meta/n_ctx");
            if (primary != null) {
                return primary;
            }
            return firstPositive("/meta/n_ctx_train");
        }

        /**
         * Best effort max output tokens (the cap for the {@code max_tokens} parameter) extracted
         * from the vendor extensions, or null when unknown. Anthropic exposes it as
         * {@code max_tokens}; OpenAI models occasionally set {@code max_completion_tokens};
         * sglang gateways (and some vLLM deploys) use {@code max_output_length}. All are token counts.
         */
        public Integer maxTokens() {
            return firstPositive("/max_tokens", "/max_completion_tokens", "/output_token_limit", "/max_output_length");
        }

        private Integer firstPositive(final String... pointers) {
            for (final var pointer : pointers) {
                final var value = tryLookup(pointer);
                if (value instanceof final Number number && number.intValue() > 0) {
                    return number.intValue();
                }
            }
            return null;
        }

        private Object tryLookup(final String pointer) {
            if (others == null) {
                return null;
            }
            try {
                return new GenericJsonPointer(pointer).apply(others);
            } catch (final RuntimeException e) {
                return null;
            }
        }
    }
}
