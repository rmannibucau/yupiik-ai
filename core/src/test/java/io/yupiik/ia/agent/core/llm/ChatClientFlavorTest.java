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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ChatClientFlavorTest {
    private HttpServer server;
    private ChatClient client;
    private JsonMapper mapper;

    @TempDir
    Path storageHome;

    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger modelsHits = new AtomicInteger();

    @BeforeEach
    void start(final JsonMapper mapper) throws IOException {
        this.mapper = mapper;
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
        client = new ChatClient(configuration(), mapper, providerManager(), Clock.systemUTC());
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
        final var entries = new HashMap<String, String>();
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
        return new ProviderManager(configuration(), mapper);
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
        return request("test-model");
    }

    private ChatCompletionRequest request(final String model) {
        return new ChatCompletionRequest(
                model, List.of(Message.user("hi")), null, null, null, null, null, null, null, null);
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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

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
        final var result = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
        assertTrue(result.structured());
        assertEquals("hello there", result.result().message().content());
    }

    @Test
    void chatStructuredDegradesOnceOnSchemaRejectionAndRemembersItPerModel() throws Exception {
        stop();
        final var structuredHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("response_format") || body.contains("json_schema")) {
                structuredHits.incrementAndGet();
                respond(
                        exchange,
                        400,
                        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"json was malformed\"}}",
                        "application/json");
            } else {
                respond(exchange, 200, """
                        {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"plain answer"},"finish_reason":"stop"}]}""", "application/json");
            }
        });
        server.start();
        final var cfg = configuration();
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        // first call: the structured attempt is rejected (one request), then the plain fallback succeeds
        final var first = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
        assertFalse(first.structured(), "the schema rejection must degrade to the plain request");
        assertEquals("plain answer", first.result().message().content());
        assertEquals(1, structuredHits.get(), "the rejection must not be retried within the call");

        // second call on the same (provider, model, schema): the rejection is remembered, no structured request
        final var second = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
        assertFalse(second.structured());
        assertEquals(1, structuredHits.get(), "the rejection must be memoized per (provider, model, schema)");

        // a different schema is not poisoned by that verdict
        final var other = client.chatStructured(request(), "memory_facts", Map.of("type", "object"));
        assertFalse(other.structured());
        assertEquals(2, structuredHits.get(), "another schema must still try the structured request once");
    }

    @Test
    void chatStructuredSurfacesNonSchema400WithoutFallingBack() throws Exception {
        stop();
        final var hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            respond(
                    exchange,
                    400,
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: 4096 > 1024, which is the maximum allowed\"}}",
                    "application/json");
        });
        server.start();
        final var cfg = configuration();
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        // a non-schema 400 (thinking/max_tokens config) must surface as-is, not be hidden by a fallback
        final var failure = assertThrows(
                IOException.class, () -> client.chatStructured(request(), "todo_steps", Map.of("type", "object")));
        assertEquals(1, hits.get(), "a non-schema 400 must not trigger a fallback request");
        assertTrue(failure.getMessage().contains("LLM request failed (400)"), failure.getMessage());
    }

    @Test
    void chatStructuredBodyParse400IsNotTreatedAsSchemaRejection() throws Exception {
        // freeinference rejects a request body it cannot validate (eg. max_tokens:-1) with exactly
        // "Invalid JSON or schema in request body" - the word 'schema' is a false positive for the
        // structured-output JSON-Schema rejection markers. It must NOT degrade to a plain fallback:
        // the plain request reuses the same invalid body and would fail identically, wasting a round-trip.
        // The 400 surfaces directly.
        stop();
        final var hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            respond(
                    exchange,
                    400,
                    "{\"error\":{\"type\":\"unknown\",\"message\":\"Invalid JSON or schema in request body\",\"code\":400}}",
                    "application/json");
        });
        server.start();
        final var cfg = configuration();
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        final var failure = assertThrows(
                IOException.class, () -> client.chatStructured(request(), "memory_facts", Map.of("type", "object")));
        assertEquals(1, hits.get(), "a body-JSON-parse 400 must not trigger a plain fallback request");
        assertTrue(failure.getMessage().contains("LLM request failed (400)"), failure.getMessage());
    }

    @Test
    void reservedProviderOptionsNeverReachTheWire() throws Exception {
        // a free-form option (providerSpecificOptions/model tuning/variant extras) must not duplicate
        // a protocol field: the encoder flattens extras last, so a 'stream' key would flip every call
        final var cfg = configuration(Map.of(
                "yca.provider.providers.0.providerSpecificOptions",
                "{\"stream\":true,\"model\":\"hijacked\",\"temperature\":0.5}"));
        client.close();
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        final var result = client.chat(request());
        assertEquals("hello there", result.message().content());
        final var body = bodies.get(bodies.size() - 1);
        assertEquals(1, occurrences(body, "\"stream\""), "exactly one stream field expected: " + body);
        assertTrue(body.contains("\"stream\":false"), "the typed stream value must win: " + body);
        assertEquals(0, occurrences(body, "\"hijacked\""), "the reserved model key must be dropped: " + body);
        assertTrue(body.contains("\"temperature\":0.5"), "non-reserved options still flow through: " + body);
    }

    @Test
    void providerReasoningConfigFullyOverridesTheVariantExtras() throws Exception {
        // an explicitly configured reasoning shape (thinking/output_config) must win verbatim over the
        // selected variant extras: the variant must not fill in missing sub-keys (e.g. a 'display')
        final var cfg = configuration(
                Map.of(
                        "yca.provider.providers.0.providerSpecificOptions",
                        "{\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"max\"}}",
                        "yca.provider.providers.0.tuning.variants.length",
                        "1",
                        "yca.provider.providers.0.tuning.variants.0.name",
                        "adaptive-high",
                        "yca.provider.providers.0.tuning.variants.0.extra",
                        "{\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"},\"output_config\":{\"effort\":\"high\"}}"));
        client.close();
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        final var settings = ChatSettings.EMPTY.withVariant("adaptive-high");
        assertEquals(
                "hello there",
                client.chat(request().withSettings(settings), () -> false)
                        .message()
                        .content());
        final var body = bodies.get(bodies.size() - 1);
        assertTrue(body.contains("\"type\":\"adaptive\""), body);
        assertTrue(body.contains("\"output_config\""), body);
        assertTrue(body.contains("\"effort\":\"max\""), "the user effort must win over the variant's high: " + body);
        assertFalse(body.contains("\"display\":\"summarized\""), "the variant's display must not leak in: " + body);
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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        assertEquals("ok", client.chat(request()).message().content());
        assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
    }

    @Test
    void configuredOutputTokensIsSentAsMaxTokens() throws Exception {
        final var cfg = configuration(Map.of("yca.provider.providers.0.outputTokens", "1234"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(ApiFlavor.RESPONSES, client.flavor());
        assertTrue(bodies.getLast().contains("\"max_output_tokens\":1234"), bodies.getLast());
    }

    @Test
    void providerSpecificTemperatureIsPromotedAndSentExactlyOnce() throws Exception {
        final var cfg =
                configuration(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
    }

    @Test
    void providerSpecificMaxTokensIsPromotedWithoutDuplicateWireKeys() throws Exception {
        final var cfg =
                configuration(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"max_tokens\":777}"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(1, occurrences(bodies.getLast(), "\"max_tokens\":777"), bodies.getLast());
    }

    @Test
    void extrasTemperatureIsPromotedOnTheStreamPath() throws Exception {
        final var cfg =
                configuration(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        assertEquals("hello there", client.chat(request()).message().content());
        assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
    }

    @Test
    void providerModelLeadingSpaceIsTrimmedOffTheWire() throws Exception {
        final var cfg = configuration(Map.of("yca.provider.providers.0.model", " deepseek-v4-flash"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        assertEquals("hello there", client.chat(request(null)).message().content());
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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());
        final var settings = ChatSettings.EMPTY.withVariant("balanced");
        assertEquals(1, client.variants(settings).size());

        assertEquals(
                "hello there",
                client.chat(request().withSettings(settings), () -> false)
                        .message()
                        .content());
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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());
        final var settings = ChatSettings.EMPTY.withVariant("missing");
        assertEquals(
                "hello there",
                client.chat(request().withSettings(settings), () -> false)
                        .message()
                        .content());
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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());
        final var settings = ChatSettings.EMPTY.withProviderId("other").withVariant("balanced");
        assertEquals("balanced", client.variant(settings));
        assertEquals("other", client.providerId(settings), "the per-session provider override must be effective");
        // a second session without an override falls back to the process default provider
        final var otherSession = ChatSettings.EMPTY;
        assertEquals("default", client.providerId(otherSession));
    }

    @Test
    void twoSessionsResolveTheirOwnProviderModelAndFlavor() throws Exception {
        final var cfg = configuration(Map.ofEntries(
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.1.id", "other"),
                Map.entry(
                        "yca.provider.providers.1.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1"),
                Map.entry("yca.provider.providers.1.apiKey", "test-key"),
                Map.entry("yca.provider.providers.1.model", "tab-b-model"),
                Map.entry("yca.provider.providers.1.api", "auto")));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        // tab A: no override -> process default provider "default" and its model
        final var tabA = ChatSettings.EMPTY;
        assertEquals("test-model", client.model(tabA), "no override uses the process default provider model");
        // tab B: explicit provider override -> its model, not the process default's
        final var tabB = ChatSettings.EMPTY.withProviderId("other");
        assertEquals("tab-b-model", client.model(tabB), "the override selects the other provider's model");
        assertEquals(
                "other",
                client.currentProvider(tabB).id(),
                "the effective provider of tab B is its override, not the process default");

        // both requests hit the shared wire endpoint with their own model/body: a null request model
        // is resolved from the session's provider, so tab A carries the default model and tab B the
        // overridden provider's model
        assertEquals(
                "hello there",
                client.chat(request(null).withSettings(tabA), () -> false)
                        .message()
                        .content());
        assertEquals(
                "hello there",
                client.chat(request(null).withSettings(tabB), () -> false)
                        .message()
                        .content());
        final var bodiesSnapshot = bodies.stream().toList();
        assertTrue(
                bodiesSnapshot.stream().anyMatch(b -> b.contains("\"model\":\"test-model\"")),
                "tab A's request carries the default provider model");
        assertTrue(
                bodiesSnapshot.stream().anyMatch(b -> b.contains("\"model\":\"tab-b-model\"")),
                "tab B's request carries the overridden provider model");
    }

    @Test
    void budgetAndFlavorArePerSessionProvider() throws Exception {
        // provider "default" resolves to completions via AUTO probing of /v1/responses (404),
        // and has no explicit output budget -> no budget is force-sent for OpenAI flavors
        final var cfg = configuration(Map.ofEntries(
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.1.id", "other"),
                Map.entry(
                        "yca.provider.providers.1.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1"),
                Map.entry("yca.provider.providers.1.apiKey", "test-key"),
                Map.entry("yca.provider.providers.1.model", "tab-b-model"),
                Map.entry("yca.provider.providers.1.api", "auto"),
                Map.entry("yca.provider.providers.1.outputTokens", "4321")));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        // tab A (process default, no outputTokens) -> no max_tokens forced on the wire
        final var tabA = ChatSettings.EMPTY;
        assertEquals(
                "hello there",
                client.chat(request().withSettings(tabA), () -> false).message().content());
        final var bodyA = bodies.getLast();
        assertTrue(!bodyA.contains("\"max_tokens\""), bodyA);

        // tab B (override with outputTokens=4321) -> 4321 is injected for its requests only
        final var tabB = ChatSettings.EMPTY.withProviderId("other");
        assertEquals(
                "hello there",
                client.chat(request().withSettings(tabB), () -> false).message().content());
        final var bodyB = bodies.getLast();
        assertTrue(bodyB.contains("\"max_tokens\":4321"), bodyB);
    }

    @Test
    void inMemorySamplingOverrideSetsTemperatureTopPAndTopK() throws Exception {
        client = new ChatClient(
                configuration(), mapper, new ProviderManager(configuration(), mapper), Clock.systemUTC());
        final var settings = ChatSettings.EMPTY.withSampling(ChatClient.SamplingOverride.of(Map.of(
                ChatClient.SamplingField.TEMPERATURE, Optional.of(0.3d),
                ChatClient.SamplingField.TOP_P, Optional.of(0.85d),
                ChatClient.SamplingField.TOP_K, Optional.of(32d))));

        assertEquals(
                "hello there",
                client.chat(request().withSettings(settings), () -> false)
                        .message()
                        .content());
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
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());
        final var settings = ChatSettings.EMPTY
                .withVariant("balanced")
                .withSampling(
                        ChatClient.SamplingOverride.of(Map.of(ChatClient.SamplingField.TEMPERATURE, Optional.empty())));

        assertEquals(
                "hello there",
                client.chat(request().withSettings(settings), () -> false)
                        .message()
                        .content());
        final var body = bodies.getLast();
        assertTrue(!body.contains("\"temperature\""), body);
    }

    @Test
    void implicitVariantReflectsInlineSamplingOverrides() throws Exception {
        client = new ChatClient(
                configuration(), mapper, new ProviderManager(configuration(), mapper), Clock.systemUTC());
        final var settings = ChatSettings.EMPTY.withSampling(
                ChatClient.SamplingOverride.of(Map.of(ChatClient.SamplingField.TEMPERATURE, Optional.of(0.25d))));

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
                configuration(), mapper, new ProviderManager(configuration(), mapper), Clock.systemUTC());
        final var settings = ChatSettings.EMPTY.withSampling(
                ChatClient.SamplingOverride.of(Map.of(ChatClient.SamplingField.TOP_K, Optional.of(16d))));
        assertNotNull(client.sampling(settings));
        assertNull(
                client.sampling(settings.withModel("other-model")),
                "model change must clear the session's sampling overrides");
    }

    @Test
    void gpt5DoesNotForceAnImplicitReasoningEffortOnTheWire() throws Exception {
        final var cfg = configuration(Map.of("yca.provider.providers.0.model", "gpt-5"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        assertEquals("hello there", client.chat(request(null)).message().content());
        assertEquals(
                0,
                occurrences(bodies.getLast(), "\"reasoning_effort\""),
                "no implicit reasoning effort must reach the wire (provider default applies, opencode parity): "
                        + bodies.getLast());
    }

    @Test
    void explicitRequestModelWinsOverTheSessionSelection() throws Exception {
        final var cfg = configuration(Map.of(
                "yca.provider.providers.0.model", "default-model",
                "yca.provider.providers.1.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1",
                "yca.provider.providers.1.apiKey", "test-key",
                "yca.provider.providers.1.model", "other-model"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        // an explicit request model wins over both the settings selection and the provider model
        final var settings = ChatSettings.EMPTY.withProviderId("other").withModel("settings-model");
        assertEquals(
                "hello there",
                client.chat(request("request-model").withSettings(settings), () -> false)
                        .message()
                        .content());
        assertTrue(bodies.getLast().contains("\"model\":\"request-model\""), bodies.getLast());
    }

    @Test
    void dollarRefInRequestModelRoutesToThatProvider() throws Exception {
        final var cfg = configuration(Map.of(
                "yca.provider.providers.0.model", "default-model",
                "yca.provider.providers.1.baseUrl",
                        "http://localhost:" + server.getAddress().getPort() + "/v1",
                "yca.provider.providers.1.apiKey", "test-key",
                "yca.provider.providers.1.model", "other-model"));
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());

        // a $provider/model reference in the request model routes the request to that provider
        assertEquals(
                "hello there",
                client.chat(request("$other/model-x"), () -> false).message().content());
        assertTrue(bodies.getLast().contains("\"model\":\"model-x\""), bodies.getLast());
        assertTrue(!bodies.getLast().contains("default-model"), bodies.getLast());
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

    @Test
    void chatStructuredRejectionMemoExpiresAndIsRetried() throws Exception {
        // the structured-output rejection memoization must be time-bounded: a provider/model that
        // failed once (a transient 400, or a short outage) must be retried after the TTL instead of
        // being permanently degraded to the plain path - the memory extraction structured call is
        // the main consumer and would otherwise silently lose its JSON mode forever.
        stop();
        final var clock = new MutableClock(1_000_000_000L);
        final var structHits = new AtomicInteger();
        final var plainHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("response_format") || body.contains("json_schema")) {
                structHits.incrementAndGet();
                respond(exchange, 400, "{\"error\":{\"message\":\"json was malformed\"}}", "application/json");
            } else {
                plainHits.incrementAndGet();
                respond(exchange, 200, """
                        {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"plain"},"finish_reason":"stop"}]}""", "application/json");
            }
        });
        server.start();
        final var cfg = configuration();
        client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), clock);

        // first call: structured rejected -> degrades once to plain, remembered
        final var first = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
        assertEquals(false, first.structured());
        assertEquals(1, structHits.get());
        assertEquals(1, plainHits.get());

        // while the memo is fresh, the structured path is skipped (straight to plain)
        final var second = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
        assertEquals(false, second.structured());
        assertEquals(1, structHits.get(), "memoized rejection must skip the structured request");
        assertEquals(2, plainHits.get());

        // after the TTL the structured path is retried (and rejected again, degrading once more)
        clock.advance(20 * 60 * 1000L);
        final var third = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
        assertEquals(false, third.structured());
        assertEquals(2, structHits.get(), "the expiration must re-enable the structured attempt");
        assertEquals(3, plainHits.get());
    }

    /** A tiny controllable clock so the structured-rejection TTL can be advanced in tests. */
    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(final long millis) {
            this.millis = millis;
        }

        void advance(final long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
