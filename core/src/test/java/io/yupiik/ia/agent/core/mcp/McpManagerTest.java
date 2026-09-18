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
package io.yupiik.ia.agent.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.McpConfiguration;
import io.yupiik.ia.agent.core.configuration.McpServerConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpManagerTest {

    private com.sun.net.httpserver.HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startResetAndCall() throws Exception {
        final var initializations = new AtomicInteger();
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                initializations.incrementAndGet();
                respond(
                        exchange,
                        200,
                        "{\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}",
                        "application/json");
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else if (body.contains("\"method\":\"tools/list\"")) {
                respond(
                        exchange,
                        200,
                        "{\"result\":{\"tools\":[{\"name\":\"find_docs\",\"description\":\"find docs\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]},\"id\":2}",
                        "application/json");
            } else if (body.contains("\"method\":\"tools/call\"")) {
                respond(
                        exchange,
                        200,
                        "{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello from server\"}]},\"id\":3}",
                        "application/json");
            } else {
                respond(
                        exchange,
                        500,
                        "{\"error\":{\"code\":-32601,\"message\":\"method not found\"},\"id\":4}",
                        "application/json");
            }
        });
        server.start();

        final var url = "http://localhost:" + server.getAddress().getPort() + "/mcp";
        final var manager = new McpManager(
                configuration(List.of(
                        new McpServerConfiguration(
                                "test", null, List.of(), List.of(), url, "key", List.of(), true, List.of()),
                        new McpServerConfiguration(
                                "disabled",
                                null,
                                List.of(),
                                List.of(),
                                "http://localhost:1",
                                null,
                                List.of(),
                                false,
                                List.of()))),
                mapper(),
                List.of(),
                httpClient());

        manager.start();
        assertEquals(1, initializations.get());
        assertEquals(1, manager.tools().size());
        assertEquals("test_find_docs", manager.tools().get(0).name());
        assertEquals("hello from server", manager.call("test", "find_docs", Map.of("q", "x")));
        assertTrue(
                manager.serverStatuses().contains(new McpManager.ServerStatus("test", McpManager.ServerState.ENABLED)));
        assertTrue(manager.serverStatuses()
                .contains(new McpManager.ServerStatus("disabled", McpManager.ServerState.DISABLED)));

        manager.reset("test");
        assertEquals(2, initializations.get());
        assertEquals(1, manager.tools().size(), "reset should re-import the server tools");
        assertEquals("hello from server", manager.call("test", "find_docs", Map.of("q", "x")));
        assertTrue(
                manager.serverStatuses().contains(new McpManager.ServerStatus("test", McpManager.ServerState.ENABLED)));

        manager.reset();
        assertEquals(3, initializations.get());
        assertEquals(1, manager.tools().size());

        final var error = assertThrows(IllegalArgumentException.class, () -> manager.reset("does-not-exist"));
        assertTrue(error.getMessage().contains("does-not-exist"));
        final var disabled = assertThrows(IllegalArgumentException.class, () -> manager.reset("disabled"));
        assertTrue(disabled.getMessage().contains("disabled"));
    }

    @Test
    void builtinsAreServedInProcessWithoutMcpWrapping() {
        final var base = TestConfigs.codingAgent(Map.of());
        final var manager = new McpManager(
                base,
                mapper(),
                List.of(new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "probe";
                    }

                    @Override
                    public String description() {
                        return "probe tool";
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("type", "object");
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args) {
                        return ToolResult.error("File not found: nope.txt. Did you mean: nope.md?");
                    }
                }),
                httpClient());

        manager.start();
        final var tools = manager.tools();
        assertEquals(1, tools.size());
        assertEquals("probe", tools.get(0).name());
        final var result = tools.get(0).execute(Map.of());
        assertFalse(result.success());
        assertEquals("File not found: nope.txt. Did you mean: nope.md?", result.output());
        assertTrue(
                manager.serverStatuses().contains(new McpManager.ServerStatus("yca", McpManager.ServerState.ENABLED)));
        assertEquals(
                List.of("probe"),
                manager.toolsByServer().get("yca").stream()
                        .map(EmbeddedTool::name)
                        .toList());
    }

    private static CodingAgentConfiguration configuration(final List<McpServerConfiguration> servers) {
        final var base = TestConfigs.codingAgent(Map.of("yca.tools.builtin.enabled", "false"));
        return new CodingAgentConfiguration(
                base.storageHome(),
                base.home(),
                base.workingDir(),
                base.projectDir(),
                base.provider(),
                base.model(),
                base.llm(),
                base.compaction(),
                base.pruning(),
                base.memory(),
                base.optimize(),
                base.permissions(),
                new McpConfiguration(true, servers, 10),
                base.mavenRepositoryKnowledge(),
                base.structuralCodeGraph(),
                base.sessions(),
                base.skills(),
                base.agents(),
                base.mode(),
                base.httpClient(),
                base.tools(),
                base.maxLineLength(),
                base.resumeAliases());
    }

    private static JsonMapper mapper() {
        return new JsonMapperImpl(List.of(), key -> Optional.empty());
    }

    private static ExtendedHttpClient httpClient() {
        return new ExtendedHttpClient(
                new ExtendedHttpClientConfiguration().setDelegate(java.net.http.HttpClient.newHttpClient()));
    }

    private static void respond(
            final com.sun.net.httpserver.HttpExchange exchange,
            final int status,
            final String body,
            final String contentType) {
        try {
            exchange.getRequestBody().readAllBytes();
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
