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
package io.yupiik.ia.agent.github;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttemptRelayTest {
    @TempDir
    Path home;

    @Test
    void removesProviderSecretsAndOnlyForwardsThroughAttemptToken() throws Exception {
        final var auth = new AtomicReference<String>();
        final var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        upstream.start();
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty());
                final var client = HttpClient.newHttpClient()) {
            final var configuration = home.resolve("configuration.json");
            Files.writeString(
                    configuration,
                    json.toString(Map.of(
                            "yca",
                            Map.of(
                                    "provider",
                                            Map.of(
                                                    "baseUrl",
                                                    "http://127.0.0.1:"
                                                            + upstream.getAddress()
                                                                    .getPort() + "/v1/chat/completions",
                                                    "apiKey",
                                                    "upstream-secret",
                                                    "headers",
                                                    Map.of("other", "secret"),
                                                    "model",
                                                    "test"),
                                    "model", Map.of("apiKey", "alternate-secret")))));
            String endpoint;
            try (final var relay = new AttemptRelay(configuration, json, "127.0.0.1", "http://127.0.0.1")) {
                final var text = Files.readString(configuration);
                assertFalse(text.contains("upstream-secret"));
                assertFalse(text.contains("alternate-secret"));
                assertFalse(text.contains("headers"));
                final var yca = TaskStore.map(
                        TaskStore.map(json.fromString(Object.class, text)).get("yca"));
                final var provider = TaskStore.map(yca.get("provider"));
                endpoint = provider.get("baseUrl") + "/chat/completions";
                assertEquals(
                        200,
                        client.send(
                                        HttpRequest.newBuilder(URI.create(endpoint))
                                                .header("Authorization", "Bearer " + provider.get("apiKey"))
                                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                assertEquals("Bearer upstream-secret", auth.get());
            }
            assertThrows(
                    IOException.class,
                    () -> client.send(
                            HttpRequest.newBuilder(URI.create(endpoint))
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()));
        } finally {
            upstream.stop(0);
        }
    }
}
