package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.ia.agent.core.llm.ApiFlavor;

import java.util.List;
import java.util.Map;

public record ProviderConfiguration(
        @Property(defaultValue = "\"default\"", documentation = "Unique identifier of this provider, referenced in model properties as $<id>/<model>.") String id,
        @Property(defaultValue = "\"https://api.openai.com/v1\"", documentation = "Base URL of the LLM endpoint. This is the full endpoint including the API route: it may end with /chat/completions (OpenAI chat), /responses (OpenAI responses) or /messages (Anthropic messages API). The models endpoint is derived by dropping that route suffix and appending /models. When the base URL ends in /v1 with no route, the models endpoint is a best effort {baseUrl}/models and a warning is logged because the flavor cannot be guessed reliably.") String baseUrl,
        @Property(defaultValue = "\"\"", documentation = "API key. When empty, falls back to the OPENAI_API_KEY environment variable.") String apiKey,
        @Property(defaultValue = "\"\"", documentation = "Default chat model (eg. gpt-5-mini). Validated at runtime, not at startup, so `help` works without configuration.") String model,
        @Property(defaultValue = "io.yupiik.ia.agent.core.llm.ApiFlavor.AUTO", documentation = "API flavor: auto (detect from the base URL route suffix, falling back to completions then responses for OpenAI), completions, responses or anthropic.") ApiFlavor api,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.ReasoningField.AUTO", documentation = "Reasoning content flavor of the provider: auto, reasoning, reasoning_content or none.") ReasoningField reasoningField,
        @Property(defaultValue = "-1", documentation = "Context window size in tokens, used by the usage gauges and thresholds. Zero or negative (the default) auto-detects it from the provider /v1/models vendor extensions (context_window, model_context_window, context_length, max_model_len, max_input_tokens, llama.cpp meta.n_ctx, or meta.n_ctx_train when smaller than the default), caches it one day per provider and model under the storage home and falls back to 128000. Detected values are totals (prompt + completion combined); the pruning and compaction thresholds keep headroom for completions.") int contextWindow,
        @Property(defaultValue = "-1", documentation = "Default output token budget (the max_tokens parameter for providers that require it, such as Anthropic). Zero or negative (the default) auto-detects it from the provider /v1/models max_tokens, cached one day per provider and model under the storage home, and falls back to 32000. This is the per-provider default; a per-model value configured in the outputs field wins over it.") int outputTokens,
        @Property(defaultValue = "java.util.List.of()", documentation = "Per-model output token budget overrides for this provider. Each entry applies to a model id matched exactly or, when the model starts with a glob like claude-*, by pattern. The strongest override is an exact id match, then the first matching glob, then the provider outputTokens default. This lets a provider serve models with different output budgets (eg. claude-sonnet-5 with 128000 and claude-haiku-4-5 with 64000).") List<OutputLimit> outputs,
        @Property(defaultValue = "120", documentation = "HTTP request timeout in seconds.") int timeoutSeconds,
        @Property(defaultValue = "\"\"", documentation = "Optional JSON object merged into the payload of every API call for this provider (eg. {\"reasoning_effort\":\"high\"} or a variant). Empty is ignored.") String providerSpecificOptions,
        @Property(defaultValue = "java.util.Map.of()", documentation = "Extra HTTP headers added to every request to this provider (eg. beta headers, org identifiers or service tiers). Values are empty-string ignored.") Map<String, String> headers,
        @Property(documentation = "Provider-tunable system prompt sections (todo/plan guidance).") PromptConfiguration prompt) {

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

    /** A per-model output token budget override: {@code model} is matched exactly or as a glob (eg. {@code claude-*}). */
    public record OutputLimit(String model, int tokens) {
    }
}