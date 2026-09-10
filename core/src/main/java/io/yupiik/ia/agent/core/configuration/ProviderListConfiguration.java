package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.ia.agent.core.llm.ApiFlavor;

import java.util.List;

public record ProviderListConfiguration(
        @Property(defaultValue = "java.util.List.of()", documentation = "List of OpenAI compatible providers. Each provider has an id used to reference it as $<id>/<model> in model properties.") List<ProviderConfiguration> providers,
        @Property(defaultValue = "\"\"", documentation = "Id of the default provider. When blank or unknown, the first provider in the list wins.") String defaultProvider,

        // for convenience: when 'providers' is empty these legacy flat scalars (previously at yca.provider.*)
        // are synthesized into a single implicit provider with id "default". They let existing
        // --yca-provider-model / --yca-provider-baseUrl / YCA_PROVIDER_MODEL ... keep working unchanged.
        @Property(defaultValue = "\"https://api.openai.com/v1\"", documentation = "Convenience shorthand for the default provider base URL (used only when no providers are listed).") String baseUrl,
        @Property(defaultValue = "\"\"", documentation = "Convenience shorthand for the default provider API key (used only when no providers are listed).") String apiKey,
        @Property(defaultValue = "\"\"", documentation = "Convenience shorthand for the default provider model (used only when no providers are listed).") String model,
        @Property(defaultValue = "io.yupiik.ia.agent.core.llm.ApiFlavor.AUTO", documentation = "Convenience shorthand for the default provider API flavor (used only when no providers are listed).") ApiFlavor api,
        @Property(defaultValue = "io.yupiik.ia.agent.core.configuration.ReasoningField.AUTO", documentation = "Convenience shorthand for the default provider reasoning field (used only when no providers are listed).") ReasoningField reasoningField,
        @Property(defaultValue = "-1", documentation = "Convenience shorthand for the default provider context window (used only when no providers are listed). Zero or negative auto-detects it from the provider /v1/models vendor extensions, caches it one day per provider and model and falls back to 128000.") int contextWindow,
        @Property(defaultValue = "-1", documentation = "Convenience shorthand for the default provider output token budget (used only when no providers are listed). Zero or negative auto-detects it from the provider /v1/models max_tokens and falls back to 32000.") int outputTokens,
        @Property(defaultValue = "java.util.List.of()", documentation = "Convenience shorthand for the default provider per-model output token overrides (used only when no providers are listed).") List<ProviderConfiguration.OutputLimit> outputs,
        @Property(defaultValue = "120", documentation = "Convenience shorthand for the default provider timeout (used only when no providers are listed).") int timeoutSeconds) {

    public String resolvedDefaultProvider() {
        final var explicit = defaultProvider;
        if (explicit != null && !explicit.isBlank() && providers != null
                && providers.stream().anyMatch(p -> explicit.equals(p.id()))) {
            return explicit;
        }
        if (providers != null && !providers.isEmpty()) {
            final var first = providers.get(0).id();
            return first == null || first.isBlank() ? "default" : first;
        }
        return "default";
    }

    public ProviderConfiguration asSingleProvider() {
        // for convenience, present the legacy flat scalars as an implicit provider named "default"
        return new ProviderConfiguration("default", baseUrl == null ? "https://api.openai.com/v1" : baseUrl,
                apiKey == null ? "" : apiKey, model == null ? "" : model,
                api, reasoningField, contextWindow, outputTokens,
                outputs == null ? List.of() : outputs,
                timeoutSeconds, "", new PromptConfiguration("auto"));
    }
}