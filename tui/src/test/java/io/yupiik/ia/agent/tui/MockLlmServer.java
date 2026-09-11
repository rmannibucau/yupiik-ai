package io.yupiik.ia.agent.tui;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.configuration.ReasoningField;
import io.yupiik.ia.agent.core.configuration.StructuralCodeGraphConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatChunk$ChunkChoice$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatChunk$ChunkDelta$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatChunk$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatCompletionResponse$Choice$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatCompletionResponse$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.Message$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$Model$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ResponsesInput$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ResponsesRequest$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ResponsesTool$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FunctionCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.Usage$FusionJsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal OpenAI flavored LLM double: serves /v1/chat/completions (SSE when the request
 * streams, plain JSON otherwise), /v1/models and a 404 /v1/responses.
 */
public final class MockLlmServer implements AutoCloseable {
    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String answer = "hello from mock";

    public MockLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            if (body.contains("\"stream\":true")) {
                final var text = answer();
                final var cut = Math.max(1, text.length() / 2);
                final var sse = String.join("\n",
                        chunk(text.substring(0, cut)),
                        "",
                        chunk(text.substring(cut)),
                        "",
                        "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "",
                        "data: [DONE]",
                        "",
                        "");
                respond(exchange, 200, sse, "text/event-stream");
            } else {
                respond(exchange, 200,
                        "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                                + answer() + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "application/json");
            }
        });
        server.createContext("/v1/models", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"model-a\",\"owned_by\":\"x\"},{\"id\":\"model-b\",\"owned_by\":\"y\"}]}", "application/json"));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port() + "/v1";
    }

    public String answer() {
        return answer;
    }

    public void answer(final String value) {
        this.answer = value;
    }

    public List<String> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String chunk(final String content) {
        return "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + content + "\"},\"finish_reason\":null}]}";
    }

    public static JsonMapper mapper() {
        return new JsonMapperImpl(List.of(
                new Message$FusionJsonCodec(),
                new ToolCall$FusionJsonCodec(),
                new ToolCall$FunctionCall$FusionJsonCodec(),
                new Usage$FusionJsonCodec(),
                new ChatCompletionRequest$FusionJsonCodec(),
                new ChatCompletionResponse$FusionJsonCodec(),
                new ChatCompletionResponse$Choice$FusionJsonCodec(),
                new ChatChunk$FusionJsonCodec(),
                new ChatChunk$ChunkChoice$FusionJsonCodec(),
                new ChatChunk$ChunkDelta$FusionJsonCodec(),
                new ResponsesRequest$FusionJsonCodec(),
                new ResponsesInput$FusionJsonCodec(),
                new ResponsesTool$FusionJsonCodec(),
                new ModelsResponse$FusionJsonCodec(),
                new ModelsResponse$Model$FusionJsonCodec()), key -> Optional.empty());
    }

    public static CodingAgentConfiguration configuration(final String baseUrl, final String storageHome) {
        return configuration(baseUrl, storageHome, null, null);
    }

    public static CodingAgentConfiguration configuration(final String baseUrl, final String storageHome,
                                                        final PermissionsConfiguration permissions) {
        return configuration(baseUrl, storageHome, permissions, null);
    }

    public static CodingAgentConfiguration configuration(final String baseUrl, final String storageHome,
                                                        final PermissionsConfiguration permissions,
                                                        final StructuralCodeGraphConfiguration codeGraph) {
        final var provider = new ProviderConfiguration("default", baseUrl, "test-key", "test-model",
                ApiFlavor.AUTO, ReasoningField.AUTO, 128000, -1, List.of(), 5, "",
                java.util.Map.of(), null);
        return new CodingAgentConfiguration(storageHome, null, null,
                new ProviderListConfiguration(List.of(provider), "", null, null, null, null, null, 128000, -1, List.of(), 120),
                null, new LlmConfiguration(1, 1L, 0, 1, true, 250),
                null, null, null, null, permissions, null, codeGraph, null, null, null, null,
                io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig());
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
