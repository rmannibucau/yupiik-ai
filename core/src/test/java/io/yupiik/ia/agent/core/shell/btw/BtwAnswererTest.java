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
package io.yupiik.ia.agent.core.shell.btw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Message$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$Model$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FunctionCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.Usage$FusionJsonCodec;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BtwAnswererTest {
    @TempDir
    Path storageHome;

    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;
    private InMemorySessionStore sessionStore;
    private UsageTracker usageTracker;
    private BtwAnswerer answerer;
    private final List<ChatClient> clients = new ArrayList<>();
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            respond(
                    exchange,
                    200,
                    """
                    {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""".formatted(body.contains("EMPTY PLEASE") ? "" : "hello there"),
                    "application/json");
        });
        serverExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        sessionStore = new InMemorySessionStore();
        usageTracker = new UsageTracker(null, null, null, Clock.systemUTC());
        answerer = answerer(Map.of());
    }

    @AfterEach
    void stop() {
        clients.forEach(ChatClient::close);
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    private BtwAnswerer answerer(final Map<String, String> extra) {
        final var configuration = TestConfigs.codingAgent(config(extra));
        final var mapper = mapper();
        final var providerManager = new ProviderManager(configuration, mapper);
        final var client = new ChatClient(configuration, mapper, providerManager, Clock.systemUTC());
        clients.add(client);
        return new BtwAnswerer(
                client,
                sessionStore,
                usageTracker,
                configuration,
                new VirtualThreadExecutor(),
                mapper,
                providerManager,
                Clock.systemUTC());
    }

    private Map<String, String> config(final Map<String, String> extra) {
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
        entries.putAll(extra);
        return entries;
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

    @Test
    void answersOutOfBandWithoutPersisting() throws Exception {
        sessionStore.replace("s1", List.of(Message.user("hi"), Message.assistant("hello", List.of())));
        final var answer = answerer.ask("what did I say?", "s1", () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals("hello there", answer);
        final var body = bodies.getLast();
        assertTrue(body.contains("\"model\":\"test-model\""), body);
        assertTrue(body.contains("\"max_tokens\":2000"), body);
        assertTrue(!body.contains("\"tools\""), body);
        assertTrue(body.contains("Side question: what did I say?"), body);
        assertTrue(body.contains("hello"), body);
        assertEquals(2, sessionStore.size("s1"));
        assertEquals(1L, usageTracker.auxiliaryRequests());
        assertEquals(3L, usageTracker.auxiliaryPromptTokens());
    }

    @Test
    void boundsInheritedHistory() throws Exception {
        final var messages = new ArrayList<Message>();
        for (var i = 0; i < 250; i++) {
            messages.add(Message.user("m" + i));
        }
        sessionStore.replace("s1", messages);
        answerer(Map.of("yca.slashcommands.btw.maxHistoryMessages", "200"))
                .ask("q", "s1", () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = bodies.getLast();
        assertTrue(body.contains("\"m50\""), body);
        assertTrue(!body.contains("\"m49\""), body);
        assertEquals(202, body.split("\"role\"", -1).length - 1, body);
    }

    @Test
    void replaysPriorExchangesAndHonorsRecallZero() throws Exception {
        sessionStore.replace("s1", List.of());
        final var replaying = answerer(Map.of());
        replaying.ask("first question", "s1", () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        replaying
                .ask("second question", "s1", () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = bodies.getLast();
        assertTrue(body.contains("Q: first question"), body);
        assertTrue(body.contains("A: hello there"), body);
        assertTrue(body.contains("Side question: second question"), body);

        bodies.clear();
        final var noRecall = answerer(Map.of("yca.slashcommands.btw.recall", "0"));
        noRecall.ask("first question", "s1", () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        noRecall.ask("second question", "s1", () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(!bodies.getLast().contains("Earlier side questions"), bodies.getLast());
    }

    @Test
    void modelOverrideApplies() throws Exception {
        answerer(Map.of("yca.slashcommands.btw.model", "btw-model"))
                .ask("q", "s1", () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(bodies.getLast().contains("\"model\":\"btw-model\""), bodies.getLast());
    }

    @Test
    void emptyAnswerFails() throws Exception {
        final var error = assertThrows(
                ExecutionException.class,
                () -> answerer.ask("EMPTY PLEASE", "s1", () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IOException, String.valueOf(error.getCause()));
    }
}
