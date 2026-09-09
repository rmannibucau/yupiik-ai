package io.yupiik.ia.agent.core.llm;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.configuration.ReasoningField;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatClientFlavorTest {
    private HttpServer server;
    private ChatClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"stream\":true")) {
                final var sse = String.join("\n",
                        "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"he\"},\"finish_reason\":null}]}",
                        "",
                        "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"llo\"},\"finish_reason\":null}]}",
                        "",
                        "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "",
                        "data: [DONE]",
                        "");
                respond(exchange, 200, sse, "text/event-stream");
            } else {
                respond(exchange, 200, """
                        {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
                        "application/json");
            }
        });
        server.createContext("/v1/models", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"model-a\",\"owned_by\":\"x\"},{\"id\":\"model-b\",\"owned_by\":\"y\"}]}", "application/json"));
        server.start();
        client = new ChatClient(configuration(), mapper(), providerManager(), Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private CodingAgentConfiguration configuration() {
        final var provider = new ProviderConfiguration(
                "default",
                "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key", "test-model",
                ApiFlavor.AUTO, ReasoningField.AUTO, 128000, 5, "");
        final var llm = new LlmConfiguration(1, 1L, 0, 1, true, 250);
        return new CodingAgentConfiguration("/tmp/yca-test", null, null, new ProviderListConfiguration(List.of(provider), "", null, null, null, null, null, 128000, 120),
                null, llm, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ProviderManager providerManager() {
        return new ProviderManager(configuration(), mapper());
    }

    private JsonMapper mapper() {
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

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest("test-model", List.of(Message.user("hi")), null, null, null, null, null, null, null);
    }

    @Test
    void autoFlavorFallsBackToCompletions() throws Exception {
        final var result = client.chat(request());
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
        assertEquals("hello there", result.message().content());
        assertEquals(3, result.usage().promptTokens());
        assertEquals(5, result.usage().totalTokens());
    }

    @Test
    void streamParsesDeltas() throws Exception {
        final var deltas = new CopyOnWriteArrayList<StreamDelta>();
        final var message = client.stream(request(), deltas::add);
        assertEquals("hello", message.content());
        assertEquals(2, deltas.stream().filter(d -> d.type() == StreamDelta.Type.TEXT).count());
        assertTrue(deltas.stream().anyMatch(d -> d.type() == StreamDelta.Type.USAGE && d.usage().totalTokens() == 5));
        assertTrue(deltas.stream().anyMatch(d -> d.type() == StreamDelta.Type.FINISH && "stop".equals(d.finishReason())));
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
    }

    @Test
    void modelsListsIds() throws Exception {
        assertEquals(List.of("model-a", "model-b"), client.models().ids());
    }

    @Test
    void explicitCompletionsFlavorDoesNotProbeResponses() throws Exception {
        stop();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            throw new IllegalStateException("responses endpoint must not be called");
        });
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, """
                {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                "application/json"));
        server.start();
        final var provider = new ProviderConfiguration(
                "default",
                "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key", "test-model",
                ApiFlavor.COMPLETIONS, ReasoningField.AUTO, 128000, 5, "");
        final var cfg = new CodingAgentConfiguration("/tmp/yca-test", null, null, new ProviderListConfiguration(List.of(provider), "", null, null, null, null, null, 128000, 120), null,
                new LlmConfiguration(1, 1L, 0, 1, true, 250), null, null, null, null, null, null, null, null, null, null, null);
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("ok", client.chat(request()).message().content());
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
    }
}
