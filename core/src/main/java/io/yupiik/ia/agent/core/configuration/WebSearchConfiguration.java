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
import java.util.Locale;

@JsonModel
public record WebSearchConfiguration(
        @Property(
                defaultValue = "\"exa\"",
                documentation =
                        "Web search provider: exa or parallel. Both default to anonymously callable public MCP endpoints; an apiKey is optional.")
        String provider,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional API key. Falls back to the EXA_API_KEY (provider=exa) or PARALLEL_API_KEY (provider=parallel) environment variable when empty.")
        String apiKey,

        @Property(defaultValue = "8", documentation = "Number of results to return.")
        int numResults,

        @Property(defaultValue = "\"https://mcp.exa.ai/mcp\"", documentation = "Exa MCP endpoint URL.")
        String exaUrl,

        @Property(
                defaultValue = "\"https://search.parallel.ai/mcp\"",
                documentation = "Parallel search MCP endpoint URL.")
        String parallelUrl) {

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
}
