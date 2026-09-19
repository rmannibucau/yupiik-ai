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

import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds {@link CodingAgentConfiguration} instances for tests, typically pointing a provider
 * at a local mock (e.g. the TUI {@code MockLlmServer}). The mock is passed through its base URL
 * so this helper does not depend on any concrete HTTP server.
 *
 * <pre>
 * YcaTestConfiguration.builder().baseUrl(baseUrl).storageHome(home).permissions(p).build()
 * </pre>
 */
public final class CodingAgentConfigurations {
    private CodingAgentConfigurations() {
        // no-op
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String storageHome;
        private PermissionsConfiguration permissions;
        private StructuralCodeGraphConfiguration codeGraph;

        private Builder() {
            // no-op
        }

        public Builder baseUrl(final String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder storageHome(final String storageHome) {
            this.storageHome = storageHome;
            return this;
        }

        public Builder permissions(final PermissionsConfiguration permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder codeGraph(final StructuralCodeGraphConfiguration codeGraph) {
            this.codeGraph = codeGraph;
            return this;
        }

        public CodingAgentConfiguration build() {
            final var map = new HashMap<String, String>();
            map.put("yca.storageHome", storageHome);
            map.put("yca.provider.providers.length", "1");
            map.put("yca.provider.providers.0.id", "default");
            map.put("yca.provider.providers.0.baseUrl", baseUrl);
            map.put("yca.provider.providers.0.apiKey", "test-key");
            map.put("yca.provider.providers.0.model", "test-model");
            map.put("yca.provider.providers.0.api", "auto");
            map.put("yca.provider.providers.0.contextWindow", "128000");
            map.put("yca.provider.providers.0.timeoutSeconds", "5");
            map.put("yca.llm.retriesMaxAttempts", "1");
            map.put("yca.llm.retriesBaseDelayMs", "1");
            map.put("yca.llm.rateLimitRequestsPerMinute", "0");
            map.put("yca.llm.maxConcurrent", "1");
            map.put("yca.llm.retryOnContextOverflow", "true");
            map.put("yca.llm.maxIterations", "250");
            if (permissions != null) {
                map.put("yca.permissions.mode", permissions.mode().name().toLowerCase(Locale.ROOT));
                map.put("yca.permissions.allow", String.join(",", permissions.allow()));
                map.put("yca.permissions.deny", String.join(",", permissions.deny()));
                map.put("yca.permissions.planBash", String.join(",", permissions.planBash()));
            }
            if (codeGraph != null) {
                map.put("yca.structuralCodeGraph.enabled", String.valueOf(codeGraph.enabled()));
                map.put("yca.structuralCodeGraph.enrich", String.valueOf(codeGraph.enrich()));
                if (codeGraph.baseUrl() != null) {
                    map.put("yca.structuralCodeGraph.baseUrl", codeGraph.baseUrl());
                }
                if (codeGraph.apiKey() != null) {
                    map.put("yca.structuralCodeGraph.apiKey", codeGraph.apiKey());
                }
                if (codeGraph.model() != null) {
                    map.put("yca.structuralCodeGraph.model", codeGraph.model());
                }
                map.put("yca.structuralCodeGraph.maxFileBytes", String.valueOf(codeGraph.maxFileBytes()));
                map.put("yca.structuralCodeGraph.languageExtensions", String.join(",", codeGraph.languageExtensions()));
                map.put("yca.structuralCodeGraph.storeDir", codeGraph.storeDir());
                map.put("yca.structuralCodeGraph.docs", String.valueOf(codeGraph.docs()));
                map.put("yca.structuralCodeGraph.deep", String.valueOf(codeGraph.deep()));
                map.put("yca.structuralCodeGraph.noRefresh", String.valueOf(codeGraph.noRefresh()));
            }
            return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(map.get(key)))
                    .get();
        }
    }
}
