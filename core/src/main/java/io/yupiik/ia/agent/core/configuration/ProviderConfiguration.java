package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.ia.agent.core.llm.ApiFlavor;

public record ProviderConfiguration(
        @Property(defaultValue = "\"https://api.openai.com/v1\"", documentation = "OpenAI compatible base URL.") String baseUrl,
        @Property(defaultValue = "\"\"", documentation = "API key. When empty, falls back to the OPENAI_API_KEY environment variable.") String apiKey,
        @Property(defaultValue = "\"\"", documentation = "Default chat model (eg. gpt-5-mini). Validated at runtime, not at startup, so `help` works without configuration.") String model,
        @Property(defaultValue = "io.yupiik.ia.agent.core.llm.ApiFlavor.AUTO", documentation = "API flavor: auto (detect, starts with responses then falls back to completions), completions or responses.") ApiFlavor api,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.ReasoningField.AUTO", documentation = "Reasoning content flavor of the provider: auto, reasoning, reasoning_content or none.") ReasoningField reasoningField,
        @Property(defaultValue = "128000", documentation = "Context window size in tokens, used by the usage gauges and thresholds.") int contextWindow,
        @Property(defaultValue = "120", documentation = "HTTP request timeout in seconds.") int timeoutSeconds) {

    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        final var env = System.getenv("OPENAI_API_KEY");
        return env == null ? "" : env;
    }
}
