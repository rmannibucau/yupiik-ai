package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.List;

public record StructuralCodeGraphConfiguration(
        @Property(defaultValue = "true", documentation = "Enable the structural code graph (symbol/call index of the project). Requires a JDK (not a plain JRE) when the Java parser is in use.") boolean enabled,
        @Property(defaultValue = "false", documentation = "Enrich the structural graph with LLM generated conceptual summaries (concept nodes). Uses the main provider by default, or the provider/model/baseUrl/apiKey overrides below when set.") boolean enrich,
        @Property(documentation = "Provider base URL for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.") String baseUrl,
        @Property(documentation = "API key for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.") String apiKey,
        @Property(documentation = "Model for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.") String model,
        @Property(defaultValue = "5242880L", documentation = "Maximum file size in bytes parsed into the graph. Larger files are skipped.") long maxFileBytes,
        @Property(defaultValue = "java.util.List.of(\"java\")", documentation = "File extensions (without dot) parsed into the graph. Only extensions handled by a registered parser are processed.") List<String> languageExtensions,
        @Property(defaultValue = "\".yca/generated/graph\"", documentation = "Directory, relative to the working directory, where the graph cache is stored. It is regenerable and never committed.") String storeDir) {

    public String resolvedStoreDir(final String workingDir) {
        final var value = storeDir == null || storeDir.isBlank() ? ".yca/generated/graph" : storeDir;
        return workingDir + "/" + value;
    }
}