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
package io.yupiik.llm.client.spi;

import java.time.Duration;
import java.util.Map;

/**
 * Resolved provider connection settings. {@code baseUrl} is the full chat endpoint (e.g.
 * {@code https://api.anthropic.com/v1/messages}); the models endpoint is derived from it.
 *
 * @param baseUrl chat endpoint.
 * @param apiKey secret, resolved (e.g. from env) by the caller.
 * @param timeout per-request timeout.
 * @param headers extra HTTP headers.
 * @param options extra fields merged into every wire payload.
 */
public record ProviderSpec(
        String baseUrl, String apiKey, Duration timeout, Map<String, String> headers, Map<String, Object> options) {

    /** Defaults {@code timeout} to 120s and {@code headers}/{@code options} to empty maps. */
    public ProviderSpec {
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        headers = headers == null ? Map.of() : headers;
        options = options == null ? Map.of() : options;
    }

    /**
     * A spec with a 120s timeout and no extra headers/options.
     *
     * @param baseUrl the full chat endpoint.
     * @param apiKey the provider secret.
     * @return the spec.
     */
    public static ProviderSpec of(final String baseUrl, final String apiKey) {
        return new ProviderSpec(baseUrl, apiKey, Duration.ofSeconds(120), Map.of(), Map.of());
    }
}
