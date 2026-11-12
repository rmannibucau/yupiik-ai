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
package io.yupiik.ia.agent.github.execution;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.LlmRelay;
import io.yupiik.ia.agent.github.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Owns a per-attempt relay; only the ephemeral access token is written to agent configuration. */
public final class AttemptRelay implements AutoCloseable {
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern NUMERIC_ADDRESS = Pattern.compile("[0-9.]+|[0-9a-fA-F:]+");

    private final LlmRelay relay;

    public AttemptRelay(final Path configuration, final JsonMapper json, final String bind, final String advertised)
            throws IOException {
        final var address = URI.create(advertised);
        if (!"http".equals(address.getScheme())
                || address.getHost() == null
                || address.getPort() != -1
                || address.getRawUserInfo() != null
                || address.getRawQuery() != null
                || address.getRawFragment() != null
                || !(address.getRawPath().isEmpty() || "/".equals(address.getRawPath()))) {
            throw new IllegalArgumentException("Relay advertised address must be an HTTP origin without port");
        }
        if (!NUMERIC_ADDRESS.matcher(bind).matches()) {
            throw new IllegalArgumentException("Relay bind must be a numeric private address");
        }
        final var root = Map.<String, Object>of(
                "yca",
                json.fromString(AgentConfigurationDocument.class, Files.readString(configuration))
                        .configuration());
        final var yca = new LinkedHashMap<>(TaskStore.map(root.get("yca")));
        final var provider = new LinkedHashMap<>(TaskStore.map(yca.get("provider")));
        if (!(provider.get("apiKey") instanceof String secret)
                || secret.isBlank()
                || !(provider.get("baseUrl") instanceof String base)) {
            throw new IllegalArgumentException("Relay requires explicit OpenAI-compatible provider baseUrl and apiKey");
        }
        if (provider.containsKey("type") && !"openai".equalsIgnoreCase(String.valueOf(provider.get("type")))) {
            throw new IllegalArgumentException("Relay supports OpenAI-compatible providers only");
        }
        final var endpoint = TRAILING_SLASHES.matcher(base).replaceAll("");
        if (endpoint.endsWith("/messages") || endpoint.endsWith("/responses")) {
            throw new IllegalArgumentException("Relay supports chat-completions endpoints only");
        }
        final var started = new LlmRelay(
                new InetSocketAddress(bind, 0),
                URI.create(endpoint.endsWith("/chat/completions") ? endpoint : endpoint + "/chat/completions"),
                secret,
                4);
        try {
            // Rebuild rather than copying headers/auth fields which might contain another credential.
            final var safe = new LinkedHashMap<String, Object>();
            for (final var key : List.of("model", "contextWindow", "outputTokens", "timeoutSeconds")) {
                if (provider.containsKey(key)) {
                    safe.put(key, provider.get(key));
                }
            }
            safe.put(
                    "baseUrl",
                    TRAILING_SLASHES.matcher(advertised).replaceAll("") + ":"
                            + started.address().getPort());
            safe.put("apiKey", started.accessToken());
            yca.put("provider", safe);
            // Alternate model definitions can contain direct credentials. Relay mode permits one provider only.
            yca.remove("model");
            Files.writeString(configuration, json.toString(new AgentConfigurationDocument(yca, Map.of())));
            this.relay = started;
        } catch (final IOException | RuntimeException e) {
            started.close();
            throw e;
        }
    }

    @Override
    public void close() {
        relay.close();
    }
}
