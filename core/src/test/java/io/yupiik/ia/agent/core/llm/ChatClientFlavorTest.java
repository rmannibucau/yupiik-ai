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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatClientFlavorTest {
    private HttpServer server;
    private ChatClient client;

    @TempDir
    Path storageHome;

    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger modelsHits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        modelsHits.set(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            if (body.contains("\"stream\":true")) {
                final var sse = String.join(
                        "\n",
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
                        {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""", "application/json");
            }
        });
        server.createContext("/v1/models", exchange -> {
            modelsHits.incrementAndGet();
            respond(
                    exchange,
                    200,
                    "{\"data\":[{\"id\":\"model-a\",\"owned_by\":\"x\"},{\"id\":\"model-b\",\"owned_by\":\"y\"}]}",
                    "application/json");
        });
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
        return configuration(Map.of());
    }

    private CodingAgentConfiguration configuration(final Map<String, String> extra) {
        final var entries = new java.util.HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put(
                "yca.provider.providers.0.baseUrl",
                "http://localhost:" + server.getAddress().getPort() + "/v1");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.llm.retriesMaxAttempts", "1");
        entries.put("yca.llm.retriesBaseDelayMs", "1");
        entries.put("yca.llm.rateLimitRequestsPerMinute", "0");
        entries.put("yca.llm.maxConcurrent", "1");
        entries.put("yca.llm.retryOnContextOverflow", "true");
        entries.put("yca.llm.maxIterations", "250");
        entries.putAll(extra);
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries);
    }

    private ProviderManager providerManager() {
        return new ProviderManager(configuration(), mapper());
    }

    private JsonMapper mapper() {
        return new JsonMapperImpl(
                List.of(
                        new Message$FusionJsonCodec(),
                        new ToolCall$FusionJsonCodec(),
                        new ToolCall$FunctionCall$FusionJsonCodec(),
                        new Usage$FusionJsonCodec(),
                        new ChatCompletionRequest$FusionJsonCodec(),
                        new ModelsResponse$FusionJsonCodec(),
                        new ModelsResponse$Model$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatCompletionRequest$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireMessage$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireMessage$WireToolCall$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireMessage$WireFunction$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireTool$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.OpenAIUsage$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatCompletionResponse$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatCompletionResponse$Choice$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatChunk$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatChunk$ChunkChoice$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatChunk$ChunkDelta$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ResponsesRequest$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ResponsesInput$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ResponsesTool$FusionJsonCodec()),
                key -> Optional.empty());
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

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model", List.of(Message.user("hi")), null, null, null, null, null, null, null, null);
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
    void bareV1AutoPrefersCompletionsWithoutProbingResponses() throws Exception {
        stop();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            throw new IllegalStateException("responses endpoint must not be probed for a bare /v1 base URL");
        });
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, """
                {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""", "application/json"));
        server.start();
        final var cfg = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry(
                        "yca.provider.providers.0.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1"),
                Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                Map.entry("yca.provider.providers.0.model", "test-model"),
                Map.entry("yca.provider.providers.0.api", "auto")));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("ok", client.chat(request()).message().content());
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
    }

    @Test
    void streamParsesDeltas() throws Exception {
        final var deltas = new CopyOnWriteArrayList<LlmStreamEvent>();
        final var message = client.stream(request(), deltas::add);
        assertEquals("hello", message.content());
        assertEquals(2, deltas.stream().filter(TextDelta.class::isInstance).count());
        assertTrue(deltas.stream()
                .filter(End.class::isInstance)
                .map(End.class::cast)
                .anyMatch(d -> d.usage() != null && d.usage().totalTokens() == 5));
        assertTrue(deltas.stream()
                .filter(End.class::isInstance)
                .map(End.class::cast)
                .anyMatch(d -> "stop".equals(d.finishReason())));
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
    }

    @Test
    void chatStructuredWithNullOthersIgnoresMissingProviderOptions() throws Exception {
        final var result = client.chatStructured(request(), "todo_steps", java.util.Map.of("type", "object"));
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
        assertTrue(result.structured());
        assertEquals("hello there", result.result().message().content());
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
                {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""", "application/json"));
        server.start();
        final var cfg = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry(
                        "yca.provider.providers.0.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1"),
                Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                Map.entry("yca.provider.providers.0.model", "test-model"),
                Map.entry("yca.provider.providers.0.api", "completions"),
                Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "5"),
                Map.entry("yca.llm.retriesMaxAttempts", "1"),
                Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                Map.entry("yca.llm.maxConcurrent", "1"),
                Map.entry("yca.llm.retryOnContextOverflow", "true"),
                Map.entry("yca.llm.maxIterations", "250")));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("ok", client.chat(request()).message().content());
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
    }

    @Test
    void configuredOutputTokensIsSentAsMaxTokens() throws Exception {
        final var cfg = configuration(Map.of("yca.provider.providers.0.outputTokens", "1234"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertTrue(bodies.getLast().contains("\"max_tokens\":1234"), bodies.getLast());
    }

    @Test
    void unconfiguredBudgetIsNotSentForCompletions() throws Exception {
        assertEquals("hello there", client.chat(request()).message().content());
        assertTrue(bodies.getLast().contains("\"model\":\"test-model\""), bodies.getLast());
        assertTrue(!bodies.getLast().contains("\"max_tokens\""), bodies.getLast());
    }

    @Test
    void configuredOutputTokensIsSentAsMaxOutputTokensForResponses() throws Exception {
        stop();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"id":"r1","model":"test-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello there"}]}],"usage":{}}""", "application/json");
        });
        server.start();
        final var cfg = configuration(Map.of(
                "yca.provider.providers.0.api", "responses",
                "yca.provider.providers.0.outputTokens", "1234"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(ApiFlavor.RESPONSES, client.flavor());
        assertTrue(bodies.getLast().contains("\"max_output_tokens\":1234"), bodies.getLast());
    }

    @Test
    void providerSpecificTemperatureIsPromotedAndSentExactlyOnce() throws Exception {
        final var cfg =
                configuration(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
    }

    @Test
    void providerSpecificMaxTokensIsPromotedWithoutDuplicateWireKeys() throws Exception {
        final var cfg =
                configuration(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"max_tokens\":777}"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(1, occurrences(bodies.getLast(), "\"max_tokens\":777"), bodies.getLast());
    }

    @Test
    void extrasTemperatureIsPromotedOnTheStreamPath() throws Exception {
        final var cfg =
                configuration(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        client.stream(request(), delta -> {
            // no-op consumer: the assertion is on the wire body below
        });
        assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
    }

    @Test
    void providerSpecificTemperatureIsPromotedForResponsesFlavor() throws Exception {
        stop();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"id":"r1","model":"test-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello there"}]}],"usage":{}}""", "application/json");
        });
        server.start();
        final var cfg = configuration(Map.of(
                "yca.provider.providers.0.api", "responses",
                "yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
    }

    @Test
    void providerModelLeadingSpaceIsTrimmedOffTheWire() throws Exception {
        final var cfg = configuration(Map.of("yca.provider.providers.0.model", " deepseek-v4-flash"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertTrue(bodies.getLast().contains("\"model\":\"deepseek-v4-flash\""), bodies.getLast());
        assertTrue(!bodies.getLast().contains("\"model\":\" deepseek-v4-flash\""), bodies.getLast());
    }

    private static int occurrences(final String body, final String needle) {
        var count = 0;
        for (var idx = body.indexOf(needle); idx >= 0; idx = body.indexOf(needle, idx + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void selectedVariantAppliesTemperatureTopPAndTopK() throws Exception {
        final var cfg = configuration(Map.ofEntries(
                Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.top_p", "0.9"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.top_k", "40")));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());
        final var settings = new ChatSettings();
        assertEquals(1, client.variants(settings).size());
        settings.setVariant("balanced");

        assertEquals(
                "hello there",
                client.chat(request(), settings, () -> false).message().content());
        assertEquals("balanced", client.variant(settings));
        final var body = bodies.getLast();
        assertEquals(1, occurrences(body, "\"temperature\":0.7"), body);
        assertEquals(1, occurrences(body, "\"top_p\":0.9"), body);
        assertEquals(1, occurrences(body, "\"top_k\":40"), body);
    }

    @Test
    void unknownVariantNameIsIgnoredAndImplicitDefaultsApply() throws Exception {
        final var cfg = configuration(Map.ofEntries(
                Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7")));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());
        final var settings = new ChatSettings();
        settings.setVariant("missing");
        assertEquals(
                "hello there",
                client.chat(request(), settings, () -> false).message().content());
        assertEquals("missing", client.variant(settings));
        final var body = bodies.getLast();
        // no configured variant was applied and the model has no implicit default
        assertTrue(!body.contains("\"temperature\""), body);
    }

    @Test
    void setProviderKeepsThePerSessionVariantSelection() throws Exception {
        final var cfg = configuration(Map.ofEntries(
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.1.id", "other"),
                Map.entry(
                        "yca.provider.providers.1.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1"),
                Map.entry("yca.provider.providers.1.apiKey", "test-key"),
                Map.entry("yca.provider.providers.1.model", "test-model"),
                Map.entry("yca.provider.providers.1.api", "auto"),
                Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced")));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());
        final var settings = new ChatSettings();
        settings.setVariant("balanced");
        assertEquals("balanced", client.variant(settings));
        client.setProvider("other");
        assertEquals(
                "balanced",
                client.variant(settings),
                "a provider change must not touch the selection owned by a session");
    }

    @Test
    void inMemorySamplingOverrideSetsTemperatureTopPAndTopK() throws Exception {
        client = new ChatClient(
                configuration(), mapper(), new ProviderManager(configuration(), mapper()), Clock.systemUTC());
        final var settings = new ChatSettings();
        settings.setSampling(ChatClient.SamplingOverride.of(java.util.Map.of(
                ChatClient.SamplingField.TEMPERATURE, java.util.Optional.of(0.3d),
                ChatClient.SamplingField.TOP_P, java.util.Optional.of(0.85d),
                ChatClient.SamplingField.TOP_K, java.util.Optional.of(32d))));

        assertEquals(
                "hello there",
                client.chat(request(), settings, () -> false).message().content());
        final var body = bodies.getLast();
        assertEquals(1, occurrences(body, "\"temperature\":0.3"), body);
        assertEquals(1, occurrences(body, "\"top_p\":0.85"), body);
        assertEquals(1, occurrences(body, "\"top_k\":32"), body);
    }

    @Test
    void noneSamplingOverrideForcesOmissionFromTheWireEvenWithAVariant() throws Exception {
        final var cfg = configuration(Map.ofEntries(
                Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7")));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());
        final var settings = new ChatSettings();
        settings.setVariant("balanced");
        settings.setSampling(ChatClient.SamplingOverride.of(
                java.util.Map.of(ChatClient.SamplingField.TEMPERATURE, java.util.Optional.empty())));

        assertEquals(
                "hello there",
                client.chat(request(), settings, () -> false).message().content());
        final var body = bodies.getLast();
        assertTrue(!body.contains("\"temperature\""), body);
    }

    @Test
    void implicitVariantReflectsInlineSamplingOverrides() throws Exception {
        client = new ChatClient(
                configuration(), mapper(), new ProviderManager(configuration(), mapper()), Clock.systemUTC());
        final var settings = new ChatSettings();
        settings.setSampling(ChatClient.SamplingOverride.of(
                java.util.Map.of(ChatClient.SamplingField.TEMPERATURE, java.util.Optional.of(0.25d))));

        final var implicit = client.implicitVariant(settings);
        // test-model has no implicit default; the explicit override surfaces through it
        if (implicit != null) {
            assertEquals(0.25, implicit.temperature(), 0.0);
        }
        assertNotNull(client.sampling(settings));
        client.setProvider("default");
        assertNotNull(client.sampling(settings), "provider change must not clear a session's sampling overrides");
    }

    @Test
    void setModelClearsInMemorySamplingOverrides() throws Exception {
        client = new ChatClient(
                configuration(), mapper(), new ProviderManager(configuration(), mapper()), Clock.systemUTC());
        final var settings = new ChatSettings();
        settings.setSampling(ChatClient.SamplingOverride.of(
                java.util.Map.of(ChatClient.SamplingField.TOP_K, java.util.Optional.of(16d))));
        assertNotNull(client.sampling(settings));
        settings.setModel("other-model");
        assertNull(client.sampling(settings), "model change must clear the session's sampling overrides");
    }

    @Test
    void gpt5EmitsDefaultReasoningEffortOnTheWire() throws Exception {
        final var cfg = configuration(Map.of("yca.provider.providers.0.model", "gpt-5"));
        client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(1, occurrences(bodies.getLast(), "\"reasoning_effort\":\"medium\""), bodies.getLast());
    }

    @Test
    void modelsAreCachedAcrossCallsAndRefreshedOnProviderChange() throws Exception {
        assertEquals(List.of("model-a", "model-b"), client.models().ids());
        assertEquals(1, modelsHits.get(), "the /v1/models endpoint must be hit once");

        assertEquals(client.models().ids(), client.models().ids());
        assertEquals(1, modelsHits.get(), "repeated calls must reuse the cached model list");

        client.setProvider("default");
        assertEquals(List.of("model-a", "model-b"), client.models().ids());
        assertEquals(2, modelsHits.get(), "changing the provider must refresh the cached model list");
    }
}
