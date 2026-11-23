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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.configuration.WebSearchProvider;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.mcp.WebSearchManager;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class WebSearchToolTest {

    private static final String NO_RESULTS = "No search results found. Please try a different query.";

    @TempDir
    Path tempDir;

    private JsonMapper mapper;
    private HttpClient delegate;
    private ExtendedHttpClient client;

    @BeforeEach
    void boot(final JsonMapper mapper) {
        this.mapper = mapper;
        this.delegate = HttpClient.newBuilder().build();
        this.client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        client.close();
        // HttpResponse/completion is done at that point: the graceful shutdown can wait forever on a
        // lingering (SSE/keep-alive) connection of the test server, so abort the selector first.
        delegate.shutdownNow();
        delegate.close();
        client = null;
        delegate = null;
    }

    @Test
    void formatsResultsWithPublishDate() throws Exception {
        try (final var mock = Mock.start(mapper).payload(parallelPayload())) {
            final var config = TestConfigs.codingAgent(singleProvider(WebSearchProvider.PARALLEL, mock.url()));
            final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config);
            final var result = tool.execute(
                    Map.of("query", "apache maven build tool"), ToolContext.empty(config.resolveWorkingDir()));
            assertTrue(result.success());
            assertTrue(result.output().contains("- First (https://example.com/a, published 2026-06-30)"));
            assertTrue(result.output().contains("\n  part one\n\n  part two"));
            assertTrue(result.output().contains("- Second (https://example.com/b)"));
        }
    }

    @Test
    void noResultsMessageOnEmptyAnswer() throws Exception {
        try (final var mock = Mock.start(mapper).payload("{\"search_id\":\"s1\",\"results\":[]}")) {
            final var config = TestConfigs.codingAgent(singleProvider(WebSearchProvider.PARALLEL, mock.url()));
            final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config);
            final var result = tool.execute(
                    Map.of("query", "apache maven build tool"), ToolContext.empty(config.resolveWorkingDir()));
            assertTrue(result.success());
            assertEquals(NO_RESULTS, result.output());
        }
    }

    @Test
    void queryIsRequired() {
        final var config = TestConfigs.codingAgent(defaultConfig());
        final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config);
        final var result = tool.execute(Map.of(), ToolContext.empty(config.resolveWorkingDir()));
        assertFalse(result.success());
        assertEquals("query is required", result.output());
    }

    @Test
    void toolIsHiddenWhenNoProviderIsEnabled() {
        final var config = TestConfigs.codingAgent(
                Map.of("yca.storageHome", tempDir.toString(), "yca.tools.websearch.providers.length", "0"));
        final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config);
        assertFalse(tool.usable(config.resolveWorkingDir()));
        final var mcpManager = new McpManager(config, mapper, List.of(tool), null, new ProcessService());
        mcpManager.start();
        final var registry = new ToolRegistry(mcpManager);
        assertFalse(registry.describe(config.resolveWorkingDir()).contains("websearch"));
        assertTrue(registry.toWireTools(config.resolveWorkingDir()).stream()
                .noneMatch(t -> "websearch".equals(t.function().name())));
        final var executed = tool.execute(Map.of("query", "anything"), ToolContext.empty(config.resolveWorkingDir()));
        assertFalse(executed.success());
        assertTrue(executed.output().contains("disabled"));
    }

    @Test
    void toolIsVisibleWithDefaultProviders() {
        final var config = TestConfigs.codingAgent(defaultConfig());
        final var tool = new WebSearchTool(new WebSearchManager(mapper, config, client), config);
        assertTrue(tool.usable(config.resolveWorkingDir()));
        final var mcpManager = new McpManager(config, mapper, List.of(tool), null, new ProcessService());
        mcpManager.start();
        final var registry = new ToolRegistry(mcpManager);
        assertTrue(registry.describe(config.resolveWorkingDir()).contains("websearch"));
        assertTrue(registry.toWireTools(config.resolveWorkingDir()).stream()
                .anyMatch(t -> "websearch".equals(t.function().name())));
    }

    @Test
    void configDefaultsIncludeAllProviders() {
        final var websearch = TestConfigs.codingAgent(defaultConfig()).tools().websearch();
        assertEquals(4, websearch.providers().size());
        for (final var provider : websearch.providers()) {
            assertTrue(provider.enabled());
            assertNull(provider.url());
            assertNull(provider.apiKey());
        }
        assertEquals(WebSearchProvider.EXA, websearch.providers().get(0).provider());
        assertEquals(WebSearchProvider.PARALLEL, websearch.providers().get(1).provider());
        assertEquals(WebSearchProvider.FIRECRAWL, websearch.providers().get(2).provider());
        assertEquals(WebSearchProvider.TINYFISH, websearch.providers().get(3).provider());
        assertEquals(8, websearch.numResults());
        assertEquals(25, websearch.timeoutSeconds());
    }

    @Test
    void legacySingleProviderKeysAreInert() {
        final var overrides = new HashMap<>(defaultConfig());
        overrides.put("yca.tools.websearch.provider", "parallel");
        overrides.put("yca.tools.websearch.apiKey", "legacy");
        overrides.put("yca.tools.websearch.exaUrl", "https://legacy.example.com/mcp");
        overrides.put("yca.tools.websearch.parallelUrl", "https://legacy2.example.com/mcp");
        final var websearch = TestConfigs.codingAgent(overrides).tools().websearch();
        assertEquals(4, websearch.providers().size());
        assertEquals(WebSearchProvider.EXA, websearch.providers().get(0).provider());
        assertNull(websearch.providers().get(0).url());
    }

    private Map<String, String> defaultConfig() {
        return Map.of("yca.storageHome", tempDir.toString());
    }

    private Map<String, String> singleProvider(final WebSearchProvider provider, final String url) {
        return Map.of(
                "yca.storageHome",
                tempDir.toString(),
                "yca.tools.websearch.providers.length",
                "1",
                "yca.tools.websearch.providers.0.provider",
                provider.name(),
                "yca.tools.websearch.providers.0.url",
                url);
    }

    private static String parallelPayload() {
        return "{\"search_id\":\"s1\",\"results\":["
                + "{\"url\":\"https://example.com/a\",\"title\":\"First\",\"publish_date\":\"2026-06-30\",\"excerpts\":[\"part one\",\"part two\"]},"
                + "{\"url\":\"https://example.com/b\",\"title\":\"Second\",\"publish_date\":null,\"excerpts\":[]}"
                + "]}";
    }

    /** Minimal MCP endpoint: initialize + notifications succeed, tools/call returns a configurable text. */
    private static final class Mock implements AutoCloseable {
        private final HttpServer server;
        private final JsonMapper mapper;
        private volatile String payload = "{}";

        private Mock(final JsonMapper mapper) throws IOException {
            this.mapper = mapper;
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        static Mock start(final JsonMapper mapper) throws IOException {
            return new Mock(mapper);
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort() + "/mcp";
        }

        Mock payload(final String payload) {
            this.payload = payload;
            return this;
        }

        private void handle(final HttpExchange exchange) throws IOException {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"mock\",\"version\":\"1\"}}}");
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else { // tools/call
                respond(
                        exchange,
                        200,
                        mapper.toString(Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                ((Map<?, ?>) mapper.fromString(Object.class, body)).get("id"),
                                "result",
                                Map.of("content", List.of(Map.of("type", "text", "text", payload))))));
            }
        }

        private static void respond(final HttpExchange exchange, final int status, final String body)
                throws IOException {
            exchange.getResponseHeaders().set("content-type", "application/json");
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
