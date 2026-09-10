package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

/**
 * Provider-tunable system prompt sections. Unlike {@link ProviderConfiguration#providerSpecificOptions()},
 * which is merged raw into the API payload, these values shape the system prompt the agent is given.
 */
public record PromptConfiguration(
        @Property(defaultValue = "\"auto\"", documentation = "Todo and plan guidance mode. 'auto' (the default) injects the todo-guidance tuned for the provider family detected from the base URL, defaulting to OpenAI behavior when the family is unknown. Any other non-empty value is injected verbatim as a todo-guidance appendix (opencode-style). An empty string or the literal 'false' disables the extra guidance entirely.")
        String todo) {

    /** Whether this todo guidance is explicitly disabled (empty or the literal {@code false}, case-insensitive). */
    public boolean todoDisabled() {
        return todo == null || todo.isBlank() || todo.trim().equalsIgnoreCase("false");
    }
}