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
import java.util.List;

public record StructuralCodeGraphConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Enable the structural code graph (symbol/call index of the project). Requires a JDK (not a plain JRE) when the Java parser is in use.")
        boolean enabled,

        @Property(
                defaultValue = "false",
                documentation =
                        "Enrich the structural graph with LLM generated conceptual summaries (concept nodes). Uses the main provider by default, or the provider/model/baseUrl/apiKey overrides below when set.")
        boolean enrich,

        @Property(
                documentation =
                        "Provider base URL for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.")
        String baseUrl,

        @Property(
                documentation =
                        "API key for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.")
        String apiKey,

        @Property(
                documentation =
                        "Model for the optional concept-node enrichment pass, when it differs from the main provider. Empty falls back to the main provider.")
        String model,

        @Property(
                defaultValue = "5242880L",
                documentation = "Maximum file size in bytes parsed into the graph. Larger files are skipped.")
        long maxFileBytes,

        @Property(
                defaultValue = "java.util.List.of(\"java\")",
                documentation =
                        "File extensions (without dot) parsed into the graph. Only extensions handled by a registered parser are processed.")
        List<String> languageExtensions,

        @Property(
                defaultValue = "\".yca/generated/graph\"",
                documentation =
                        "Directory, relative to the working directory, where the graph cache is stored. It is regenerable and never committed.")
        String storeDir,

        @Property(
                defaultValue = "true",
                documentation =
                        "Emit a per-source-file markdown doc tree under the graph store, mirroring the project source layout. The docs are deterministic structural extracts (symbols and edges) that agents can grep instead of reading the graph. When 'enrich' is enabled, each doc also gains an async LLM summary.")
        boolean docs,

        @Property(
                defaultValue = "\"docs\"",
                documentation =
                        "Sub-directory (under the graph store dir) where the mirrored per-file markdown doc tree is written.")
        String docStoreDir,

        @Property(
                defaultValue = "false",
                documentation =
                        "Deep enrichment: additionally ask the LLM for a one-line summary and a crux excerpt per symbol, cached by body content hash so only changed symbols are re-sent. Requires 'enrich'.")
        boolean deep,

        @Property(
                defaultValue = "false",
                documentation =
                        "Disable the automatic per-query graph refresh that stats the tree against the last build fingerprint. Equivalent to Graft's GRAFT_NO_REFRESH=1.")
        boolean noRefresh) {

    public String resolvedStoreDir(final String workingDir) {
        final var value = storeDir == null || storeDir.isBlank() ? ".yca/generated/graph" : storeDir;
        return workingDir + "/" + value;
    }

    public String resolvedDocStoreDir(final String workingDir) {
        return resolvedStoreDir(workingDir);
    }
}
