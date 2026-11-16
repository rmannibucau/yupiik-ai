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
package io.yupiik.ia.agent.slack.service;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway;
import io.yupiik.ia.agent.bot.runtime.JsonObjects;
import io.yupiik.ia.agent.bot.runtime.ProviderBudgetEnvironment;
import io.yupiik.ia.agent.bot.runtime.ResultReader;
import io.yupiik.ia.agent.core.llm.UnixProviderProtocol;
import io.yupiik.ia.agent.github.model.AgentConfigurationDocument;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Trusted provider-only handoff; there is no GitHub broker or Slack capability in this manifest. */
public final class SlackProviderServices {
    private final Path settings;
    private final Map<String, String> secrets;
    private final ProviderServicesGateway.Settings manifest;
    private final URI upstream;
    private final int timeoutSeconds;
    private final int maxConcurrent;

    public SlackProviderServices(
            final Path configuration,
            final Path privateRoot,
            final JsonMapper json,
            final Routing services,
            final Configuration sources,
            final int lifetimeSeconds)
            throws IOException {
        final var document =
                json.fromString(AgentConfigurationDocument.class, ResultReader.read(configuration, 1024 * 1024));
        final var trusted = document.configuration();
        final var provider = JsonObjects.map(trusted.get("provider"));
        final var selected = select(provider);
        final boolean nativeMode = services.agentOrigin().equals(UnixProviderProtocol.ENDPOINT.toString());
        if (!(selected.get("baseUrl") instanceof String base)
                || !endpoint(base, nativeMode).equals(endpoint(services.providerTarget(), nativeMode))
                || !List.of("AUTO", "COMPLETIONS")
                        .contains(String.valueOf(selected.getOrDefault("api", "AUTO"))
                                .toUpperCase(Locale.ROOT))
                || !(selected.get("model") instanceof String model)
                || model.isBlank()) {
            throw new IllegalArgumentException(
                    "Restricted Slack execution requires an explicitly matched chat-completions provider and model");
        }
        final var password = services.keyStorePasswordEnv().isBlank()
                ? ""
                : sources.get(services.keyStorePasswordEnv()).orElse("");
        if (!services.keyStorePasswordEnv().isBlank() && password.isBlank()) {
            throw new IllegalArgumentException("Missing private gateway TLS credential");
        }
        final var upstreamKey = selected.get("apiKey") instanceof String key && !key.isBlank()
                ? key
                : sources.get("OPENAI_API_KEY").orElse("");
        if (upstreamKey.length() > 8192 || upstreamKey.indexOf('\r') >= 0 || upstreamKey.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid provider credential");
        }
        final var random = new byte[32];
        new SecureRandom().nextBytes(random);
        final var capability = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        final var route = "/inference/" + UUID.randomUUID() + "/chat/completions";
        final var id =
                selected.get("id") instanceof String value && value.matches("[A-Za-z0-9_-]{1,64}") ? value : "default";
        final var safe = new LinkedHashMap<String, Object>();
        safe.put("id", id);
        safe.put("api", "COMPLETIONS");
        safe.put("model", model);
        safe.put("baseUrl", nativeMode ? services.agentOrigin() : services.agentOrigin() + route);
        safe.put("apiKey", capability);
        for (final var field : List.of("contextWindow", "outputTokens", "timeoutSeconds", "maxConcurrent")) {
            if (selected.get(field) instanceof Number value) {
                safe.put(field, value);
            }
        }
        final var rewritten = new LinkedHashMap<>(trusted);
        rewritten.put("provider", Map.of("defaultProvider", id, "providers", List.of(safe)));
        rewritten.remove("model");
        if (document.yca() == null) {
            rewritten.remove("run");
        }
        maxConcurrent = selected.get("maxConcurrent") instanceof Number value && value.intValue() > 0
                ? Math.min(64, value.intValue())
                : 1;
        timeoutSeconds = selected.get("timeoutSeconds") instanceof Number value
                ? Math.max(1, Math.min(nativeMode ? 600 : 900, value.intValue()))
                : 300;
        upstream = URI.create(endpoint(services.providerTarget(), nativeMode));
        manifest = nativeMode
                ? null
                : new ProviderServicesGateway.Settings(
                        services.serverName(),
                        services.port(),
                        services.keyStore(),
                        services.keyStorePasswordEnv(),
                        route,
                        upstream.toString(),
                        lifetimeSeconds,
                        timeoutSeconds,
                        4L * 1024 * 1024,
                        16L * 1024 * 1024,
                        maxConcurrent);
        settings = privateRoot.resolve("services.json");
        Files.createFile(settings, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(
                settings,
                json.toString(nativeMode ? Map.of("transport", "unix-v1") : manifest),
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        Files.writeString(
                configuration,
                json.toString(new AgentConfigurationDocument(rewritten, document.completionSettings())),
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
        final var privateEnvironment = new LinkedHashMap<String, String>();
        privateEnvironment.put("YCA_SERVICES_PROVIDER_TOKEN", capability);
        privateEnvironment.put("YCA_SERVICES_UPSTREAM_TOKEN", upstreamKey);
        if (!services.keyStorePasswordEnv().isBlank()) {
            privateEnvironment.put(services.keyStorePasswordEnv(), password);
        }
        privateEnvironment.putAll(ProviderBudgetEnvironment.select(sources, configuration.getParent(), privateRoot));
        secrets = Map.copyOf(privateEnvironment);
    }

    /** Backend-selected listener details, independent from the upstream provider selection. */
    public record Routing(
            String providerTarget,
            String agentOrigin,
            String serverName,
            int port,
            String keyStore,
            String keyStorePasswordEnv) {}

    public ProviderServicesGateway.Settings manifest() {
        return manifest;
    }

    public URI upstream() {
        return upstream;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** The connection comes from the owned gateway, never from a Slack message or result document. */
    public void retarget(final Path configuration, final JsonMapper json, final URI endpoint) throws IOException {
        final var document =
                json.fromString(AgentConfigurationDocument.class, ResultReader.read(configuration, 1024 * 1024));
        final var trusted = new LinkedHashMap<>(document.configuration());
        final var provider = JsonObjects.map(trusted.get("provider"));
        final var safe = new LinkedHashMap<>(select(provider));
        safe.put("baseUrl", endpoint.toASCIIString());
        trusted.put("provider", Map.of("defaultProvider", safe.get("id"), "providers", List.of(safe)));
        Files.writeString(
                configuration,
                json.toString(new AgentConfigurationDocument(trusted, document.completionSettings())),
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    public Path settings() {
        return settings;
    }

    public Map<String, String> secrets() {
        return secrets;
    }

    private Map<String, Object> select(final Map<String, Object> provider) {
        if (provider.get("providers") instanceof List<?> entries && !entries.isEmpty()) {
            if (entries.size() > 64 || entries.stream().anyMatch(entry -> !(entry instanceof Map<?, ?>))) {
                throw new IllegalArgumentException("Invalid trusted provider selection");
            }
            return entries.stream()
                    .map(JsonObjects::map)
                    .filter(entry -> provider.get("defaultProvider") != null
                            && provider.get("defaultProvider").equals(entry.get("id")))
                    .findFirst()
                    .orElseGet(() -> JsonObjects.map(entries.getFirst()));
        }
        return provider;
    }

    private String endpoint(final String base, final boolean nativeMode) {
        final var uri = URI.create(base);
        if (!(nativeMode ? "https" : "http").equals(uri.getScheme())
                || uri.getHost() == null
                || (!nativeMode && (!uri.getHost().matches("[0-9.]+") || uri.getPort() < 1))
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !uri.getRawPath().matches("(/[A-Za-z0-9_-]+)*")) {
            throw new IllegalArgumentException("Provider requires a fixed numeric HTTP control-network target");
        }
        final var target = uri.toASCIIString();
        return target.endsWith("/chat/completions") ? target : target + "/chat/completions";
    }
}
