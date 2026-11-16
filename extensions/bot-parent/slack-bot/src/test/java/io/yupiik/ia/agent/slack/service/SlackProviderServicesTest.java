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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway;
import io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway$Settings$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.UnixProviderProtocol;
import io.yupiik.ia.agent.github.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.github.model.AgentConfigurationDocument$FusionJsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackProviderServicesTest {
    private static final String TARGET = "http://172.20.0.1:8080/v1";
    private static final String PASSWORD_ENV = "YCA_SERVICES_KEYSTORE_PASSWORD";

    @TempDir
    Path root;

    @Test
    void replacesSelectedInlineCredentialAndDropsAlternateRoutesForBothDocumentShapes() throws Exception {
        try (final var json = mapper()) {
            for (final var wrapped : List.of(false, true)) {
                final var directory = Files.createDirectory(root.resolve(wrapped ? "wrapped" : "plain"));
                final var selected = Map.<String, Object>of(
                        "id",
                        "selected",
                        "api",
                        "COMPLETIONS",
                        "model",
                        "trusted-model",
                        "baseUrl",
                        TARGET,
                        "apiKey",
                        "selected-inline-secret",
                        "headers",
                        Map.of("X-Secret", "selected-header-secret"),
                        "models",
                        Map.of("alias", Map.of("apiKey", "alias-secret")));
                final var trusted = Map.<String, Object>of(
                        "provider",
                                Map.of(
                                        "defaultProvider",
                                        "selected",
                                        "providers",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "alternate",
                                                        "baseUrl",
                                                        "https://alternate.invalid",
                                                        "apiKey",
                                                        "alternate-secret"),
                                                selected)),
                        "model", Map.of("alias", "alternate/model"));
                final var configuration = Files.writeString(
                        directory.resolve("configuration.json"),
                        json.toString(wrapped ? Map.of("yca", trusted) : trusted));
                final var privateRoot = Files.createDirectory(directory.resolve("private"));
                final var services = new SlackProviderServices(
                        configuration,
                        privateRoot,
                        json,
                        services(TARGET),
                        Configuration.of(
                                Map.of(PASSWORD_ENV, "private-tls-secret", "OPENAI_API_KEY", "fallback-secret")),
                        60);

                final var rewritten = Files.readString(configuration);
                final var manifestText = Files.readString(services.settings());
                final var document = json.fromString(AgentConfigurationDocument.class, rewritten);
                final var provider = (Map<?, ?>) document.configuration().get("provider");
                assertEquals("selected", provider.get("defaultProvider"));
                final var providers = (List<?>) provider.get("providers");
                assertEquals(1, providers.size());
                final var safe = (Map<?, ?>) providers.getFirst();
                final var capability = services.secrets().get("YCA_SERVICES_PROVIDER_TOKEN");
                assertNotNull(capability);
                assertFalse(capability.isBlank());
                assertEquals(capability, safe.get("apiKey"));
                assertEquals("trusted-model", safe.get("model"));
                assertEquals("COMPLETIONS", safe.get("api"));
                assertFalse(safe.containsKey("headers"));
                assertFalse(safe.containsKey("models"));
                assertFalse(document.configuration().containsKey("model"));
                assertEquals("selected-inline-secret", services.secrets().get("YCA_SERVICES_UPSTREAM_TOKEN"));
                assertEquals("private-tls-secret", services.secrets().get(PASSWORD_ENV));
                for (final var secret : List.of(
                        "selected-inline-secret",
                        "alternate-secret",
                        "selected-header-secret",
                        "alias-secret",
                        "private-tls-secret",
                        "fallback-secret")) {
                    assertFalse(rewritten.contains(secret), secret);
                    assertFalse(manifestText.contains(secret), secret);
                }
                assertFalse(manifestText.contains(capability));
                final var manifest = json.fromString(ProviderServicesGateway.Settings.class, manifestText);
                assertEquals(TARGET + "/chat/completions", manifest.inferenceTarget());
                assertEquals(
                        "https://attempt-services.yca.internal:8443" + manifest.inferencePath(), safe.get("baseUrl"));
            }
        }
    }

    @Test
    void rejectsMismatchedEndpointWithoutWritingManifestOrRewritingConfiguration() throws Exception {
        try (final var json = mapper()) {
            final var original = json.toString(Map.of(
                    "provider",
                    Map.of(
                            "api",
                            "COMPLETIONS",
                            "model",
                            "model",
                            "baseUrl",
                            "http://172.20.0.2:8080/v1",
                            "apiKey",
                            "inline-secret")));
            final var configuration = Files.writeString(root.resolve("configuration.json"), original);
            final var privateRoot = Files.createDirectory(root.resolve("private"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackProviderServices(
                            configuration,
                            privateRoot,
                            json,
                            services(TARGET),
                            Configuration.of(Map.of(PASSWORD_ENV, "tls-secret")),
                            60));
            assertEquals(original, Files.readString(configuration));
            assertFalse(Files.exists(privateRoot.resolve("services.json")));
        }
    }

    @Test
    void nativeHandoffUsesOnlyPinnedUnixTransportCapability() throws Exception {
        try (final var json = mapper()) {
            final var configuration = Files.writeString(
                    root.resolve("configuration.json"),
                    json.toString(Map.of(
                            "provider",
                            Map.of(
                                    "api",
                                    "COMPLETIONS",
                                    "model",
                                    "trusted-model",
                                    "baseUrl",
                                    "https://provider.example/v1",
                                    "apiKey",
                                    "upstream-private-secret"))));
            final var services = new SlackProviderServices(
                    configuration,
                    Files.createDirectory(root.resolve("private")),
                    json,
                    new SlackProviderServices.Routing(
                            "https://provider.example/v1",
                            UnixProviderProtocol.ENDPOINT.toString(),
                            "native.invalid",
                            0,
                            "/unused",
                            ""),
                    Configuration.of(Map.of()),
                    60);
            final var rewritten = Files.readString(configuration);
            assertTrue(rewritten.contains(UnixProviderProtocol.ENDPOINT.toString()));
            assertFalse(rewritten.contains("upstream-private-secret"));
            assertFalse(rewritten.contains("https://provider.example"));
            assertEquals(
                    "https://provider.example/v1/chat/completions",
                    services.upstream().toString());
            assertEquals("upstream-private-secret", services.secrets().get("YCA_SERVICES_UPSTREAM_TOKEN"));
            assertNull(services.manifest());
            assertFalse(Files.readString(services.settings()).contains("upstream-private-secret"));
        }
    }

    private JsonMapperImpl mapper() {
        return new JsonMapperImpl(
                List.of(
                        new AgentConfigurationDocument$FusionJsonCodec(),
                        new ProviderServicesGateway$Settings$FusionJsonCodec()),
                ignored -> Optional.empty());
    }

    private SlackProviderServices.Routing services(final String target) {
        return new SlackProviderServices.Routing(
                target,
                "https://attempt-services.yca.internal:8443",
                "attempt-services.yca.internal",
                8443,
                "/services/tls.p12",
                PASSWORD_ENV);
    }
}
