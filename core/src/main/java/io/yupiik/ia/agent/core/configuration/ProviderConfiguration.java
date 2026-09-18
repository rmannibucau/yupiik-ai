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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import java.util.List;
import java.util.Map;

@JsonModel
public record ProviderConfiguration(
        @Property(
                defaultValue = "\"default\"",
                documentation = "Unique identifier of this provider, referenced in model properties as $<id>/<model>.")
        String id,

        @Property(
                defaultValue = "\"https://api.openai.com/v1\"",
                documentation =
                        "Base URL of the LLM endpoint. This is the full endpoint including the API route: it may end with /chat/completions (OpenAI chat), /responses (OpenAI responses) or /messages (Anthropic messages API). The models endpoint is derived by dropping that route suffix and appending /models. When the base URL ends in /v1 with no route, the models endpoint is a best effort {baseUrl}/models and a warning is logged because the flavor cannot be guessed reliably.")
        String baseUrl,

        @Property(
                defaultValue = "\"\"",
                documentation = "API key. When empty, falls back to the OPENAI_API_KEY environment variable.")
        String apiKey,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Default chat model (eg. gpt-5-mini). Validated at runtime, not at startup, so `help` works without configuration.")
        String model,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.llm.ApiFlavor.AUTO",
                documentation =
                        "API flavor: auto (detect from the base URL route suffix, falling back to completions then responses for OpenAI), completions, responses or anthropic.")
        ApiFlavor api,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Context window size in tokens, used by the usage gauges and thresholds. Zero or negative (the default) auto-detects it from the provider /v1/models vendor extensions (context_window, model_context_window, context_length, max_model_len, max_input_tokens, llama.cpp meta.n_ctx, or meta.n_ctx_train when smaller than the default), caches it one day per provider and model under the storage home and falls back to 128000. Detected values are totals (prompt + completion combined); the pruning and compaction thresholds keep headroom for completions.")
        int contextWindow,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Default output token budget (the max_tokens parameter for providers that require it, such as Anthropic). Zero or negative (the default) auto-detects it from the provider /v1/models max_tokens, cached one day per provider and model under the storage home, and falls back to 32000. This is the per-provider default; a per-model value configured in the models behavior field wins over it.")
        int outputTokens,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Per-model entries for this provider (display style and behavior). Each entry applies to a model id matched exactly or, when the configured id is a substring of the actual model name, by containment. The strongest override is an exact id match, then the first substring match, then the provider defaults (color, outputTokens and the implicit scheme). The list order also defines the implicit display scheme within this provider: the first two listed models are bold.")
        List<ProviderModelConfiguration> models,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Maximum number of concurrent in-flight LLM requests for this provider. 0 or negative (the default) falls back to the global yca.llm.maxConcurrent; when that is also unset the concurrency is unlimited and adapted from the provider 429 concurrency_limit_exceeded responses.")
        int maxConcurrent,

        @Property(defaultValue = "300", documentation = "HTTP request timeout in seconds (5 minutes).")
        int timeoutSeconds,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional JSON object merged into the payload of every API call for this provider (eg. {\"reasoning_effort\":\"high\"} or a variant). Empty is ignored.")
        String providerSpecificOptions,

        @Property(
                defaultValue = "java.util.Map.of()",
                documentation =
                        "Extra HTTP headers added to every request to this provider (eg. beta headers, org identifiers or service tiers). Values are empty-string ignored.")
        Map<String, String> headers,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Base display tone (hex, eg. #e0a458) for every model of this provider. When blank the provider keeps the position-based tone. Per-model styles in the models field may override this tone for individual models.")
        String color,

        @Property(documentation = "Provider-tunable system prompt sections (todo/plan guidance).")
        PromptConfiguration prompt) {

    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        final var env = System.getenv("OPENAI_API_KEY");
        return env == null ? "" : env;
    }

    /** The todo-guidance mode for this provider: 'auto' when the prompt section is absent or unset. */
    public String todoPrompt() {
        final var p = prompt;
        return p == null || p.todo() == null || p.todo().isBlank() ? "auto" : p.todo();
    }
}
