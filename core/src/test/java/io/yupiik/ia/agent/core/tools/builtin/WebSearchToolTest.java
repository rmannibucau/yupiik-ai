package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.WebSearchConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    @Test
    void formatsExaResults() throws Exception {
        final var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"contents\":[" +
                    "{\"title\":\"First\",\"url\":\"https://example.com\",\"text\":\"snippet here\"}" +
                    "]}}");
        });
        server.start();
        try {
            final var config = new CodingAgentConfiguration(
                    "/tmp/yca-websearch", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                    new io.yupiik.ia.agent.core.configuration.ToolsConfiguration(
                            io.yupiik.ia.agent.core.configuration.BuiltinToolsConfiguration.defaultConfig(),
                            new WebSearchConfiguration("exa", "", 8,
                                    "http://localhost:" + server.getAddress().getPort() + "/mcp",
                                    "https://search.parallel.ai/mcp")));
            final var client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                    .setDelegate(java.net.http.HttpClient.newHttpClient()));
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
            respond(exchange, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"responses\":[{\"message\":{\"content\":[" +
                    "{\"type\":\"text\",\"text\":\"parallel answer\"}" +
                    "]}}]}}");
        });
        server.start();
        try {
            final var config = new CodingAgentConfiguration(
                    "/tmp/yca-websearch", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                    new io.yupiik.ia.agent.core.configuration.ToolsConfiguration(
                            io.yupiik.ia.agent.core.configuration.BuiltinToolsConfiguration.defaultConfig(),
                            new WebSearchConfiguration("parallel", "", 8,
                                    "https://mcp.exa.ai/mcp",
                                    "http://localhost:" + server.getAddress().getPort() + "/mcp")));
            final var client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                    .setDelegate(java.net.http.HttpClient.newHttpClient()));
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