package io.yupiik.ia.agent.core.mcp;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpMcpClientTest {

    @AfterEach
    void stop() {
        serverState = null;
    }

    private com.sun.net.httpserver.HttpServer server;
    private HttpMcpClient client;
    private Object serverState;

    private void start(final boolean sse) throws IOException, InterruptedException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final var contentType = sse ? "text/event-stream" : "application/json";
            if (body.contains("\"method\":\"initialize\"")) {
                respond(exchange, 200, wrap(sse,
                        "{\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}"),
                        contentType);
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else if (body.contains("\"method\":\"tools/list\"")) {
                respond(exchange, 200, wrap(sse,
                        "{\"result\":{\"tools\":[{\"name\":\"find_docs\",\"description\":\"find docs\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]},\"id\":2}"),
                        contentType);
            } else if (body.contains("\"method\":\"tools/call\"")) {
                respond(exchange, 200, wrap(sse,
                        "{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello from server\"}]},\"id\":3}"),
                        contentType);
            } else {
                respond(exchange, 500, wrap(sse, "{\"error\":{\"code\":-32601,\"message\":\"method not found\"},\"id\":4}"), contentType);
            }
        });
        server.start();
        client = new HttpMcpClient("test", "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key", List.of(), 10, mapper());
        client.initialize();
    }

    @Test
    void initListAndCallFromSse() throws Exception {
        start(true);
        final var tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("find_docs", tools.get(0).name());
        assertEquals("find docs", tools.get(0).description());
        assertEquals("string", ((Map<?, ?>) ((Map<?, ?>) tools.get(0).inputSchema().get("properties")).get("q")).get("type"));
        assertEquals("hello from server", client.callTool("find_docs", Map.of("q", "x")));
    }

    @Test
    void initListAndCallFromPlainJson() throws Exception {
        start(false);
        final var tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("find_docs", tools.get(0).name());
        assertEquals("hello from server", client.callTool("find_docs", Map.of()));
    }

    @Test
    void serverErrorIsReported() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500, "boom", "text/plain");
        });
        server.start();
        final var failing = new HttpMcpClient("test", "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key", List.of(), 10, mapper());
        final var error = assertThrows(IOException.class, failing::initialize);
        assertEquals(true, error.getMessage().contains("500"));
    }

    @Test
    void negotiatesOlderProtocolVersionWhenNewestRejected() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final var contentType = "application/json";
            if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else if (body.contains("\"protocolVersion\":\"2024-11-05\"")) {
                respond(exchange, 200, "{\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"t\",\"version\":\"1\"}},\"id\":1}", contentType);
            } else {
                respond(exchange, 400, "{\"error\":{\"code\":-32601,\"message\":\"unsupported protocol version\"},\"id\":1}", contentType);
            }
        });
        server.start();
        final var negotiating = new HttpMcpClient("test", "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key", List.of(), 10, mapper());
        negotiating.initialize();
    }

    private static String wrap(final boolean sse, final String json) {
        return sse ? "event: message\ndata: " + json + "\n\n" : json;
    }

    private JsonMapper mapper() {
        return new JsonMapperImpl(List.of(), key -> Optional.empty());
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status,
                                final String body, final String contentType) {
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