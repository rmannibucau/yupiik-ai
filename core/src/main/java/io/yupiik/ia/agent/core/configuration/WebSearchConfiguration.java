package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.Locale;

public record WebSearchConfiguration(
        @Property(defaultValue = "\"exa\"", documentation = "Web search provider: exa or parallel. Both default to anonymously callable public MCP endpoints; an apiKey is optional.") String provider,
        @Property(defaultValue = "\"\"", documentation = "Optional API key. Falls back to the EXA_API_KEY (provider=exa) or PARALLEL_API_KEY (provider=parallel) environment variable when empty.") String apiKey,
        @Property(defaultValue = "8", documentation = "Number of results to return.") int numResults,
        @Property(defaultValue = "\"https://mcp.exa.ai/mcp\"", documentation = "Exa MCP endpoint URL.") String exaUrl,
        @Property(defaultValue = "\"https://search.parallel.ai/mcp\"", documentation = "Parallel search MCP endpoint URL.") String parallelUrl) {

    public enum Provider {
        EXA,
        PARALLEL;

        public static Provider of(final String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return EXA;
            }
        }
    }

    public static WebSearchConfiguration defaultConfig() {
        return new WebSearchConfiguration("exa", "", 8, "https://mcp.exa.ai/mcp", "https://search.parallel.ai/mcp");
    }
}