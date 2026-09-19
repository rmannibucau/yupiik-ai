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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebSearchToolTest {

    private static final java.net.http.HttpClient WARM_HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();

    @TempDir
    Path storageHome;

    @Test
    void formatsExaResults() throws Exception {
        final var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(
                    exchange,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"contents\":["
                            + "{\"title\":\"First\",\"url\":\"https://example.com\",\"text\":\"snippet here\"}"
                            + "]}}");
        });
        server.start();
        try {
            final var config = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.of(
                    "yca.storageHome",
                    storageHome.toString(),
                    "yca.tools.websearch.provider",
                    "exa",
                    "yca.tools.websearch.exaUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/mcp"));
            final var client =
                    new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(WARM_HTTP_CLIENT));
            final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
            final var tool = new WebSearchTool(client, mapper, config);
            final var result = tool.execute(Map.of("query", "hello"));
            assertTrue(result.success(), () -> result.output());
            assertTrue(result.output().contains("First"));
            assertTrue(result.output().contains("https://example.com"));
            assertTrue(result.output().contains("snippet here"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void formatsParallelResults() throws Exception {
        final var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(
                    exchange,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"responses\":[{\"message\":{\"content\":["
                            + "{\"type\":\"text\",\"text\":\"parallel answer\"}"
                            + "]}}]}}");
        });
        server.start();
        try {
            final var config = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.of(
                    "yca.storageHome",
                    storageHome.toString(),
                    "yca.tools.websearch.provider",
                    "parallel",
                    "yca.tools.websearch.parallelUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/mcp"));
            final var client =
                    new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(WARM_HTTP_CLIENT));
            final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
            final var tool = new WebSearchTool(client, mapper, config);
            final var result = tool.execute(Map.of("query", "hello"));
            assertTrue(result.success(), () -> result.output());
            assertTrue(result.output().contains("parallel answer"));
        } finally {
            server.stop(0);
        }
    }

    static void respond(final com.sun.net.httpserver.HttpExchange exchange, final String body) {
        try {
            exchange.getResponseHeaders().set("content-type", "application/json");
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
