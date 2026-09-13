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
package io.yupiik.llm.client.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.openai.model.ChatChunk$ChunkChoice$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatChunk$ChunkDelta$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatChunk$ChunkToolCall$Function$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatChunk$ChunkToolCall$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatChunk$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatCompletionRequest$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatCompletionResponse$Choice$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatCompletionResponse$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.CompletionsTool$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ErrorResponse$Error$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ErrorResponse$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ModelsResponse$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ModelsResponse$Model$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.OpenAIUsage$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesInput$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesRequest$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$IncompleteDetails$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$OutputContent$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$OutputItem$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$ResponsesUsage$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesStreamEvent$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesTool$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.WireMessage$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.WireMessage$WireFunction$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.WireMessage$WireToolCall$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.WireTool$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.WireTool$WireToolFunction$FusionJsonCodec;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAIDialectTest {
    private HttpServer server;
    private final AtomicReference<String> reqHeaders = new AtomicReference<>();
    private final AtomicReference<String> reqBody = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> streamTexts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static JsonMapper mapper() {
        return new JsonMapperImpl(
                List.of(
                        new ChatChunk$ChunkChoice$FusionJsonCodec(),
                        new ChatChunk$ChunkDelta$FusionJsonCodec(),
                        new ChatChunk$ChunkToolCall$Function$FusionJsonCodec(),
                        new ChatChunk$ChunkToolCall$FusionJsonCodec(),
                        new ChatChunk$FusionJsonCodec(),
                        new ChatCompletionRequest$FusionJsonCodec(),
                        new ChatCompletionResponse$Choice$FusionJsonCodec(),
                        new ChatCompletionResponse$FusionJsonCodec(),
                        new CompletionsTool$FusionJsonCodec(),
                        new ErrorResponse$Error$FusionJsonCodec(),
                        new ErrorResponse$FusionJsonCodec(),
                        new ModelsResponse$FusionJsonCodec(),
                        new ModelsResponse$Model$FusionJsonCodec(),
                        new OpenAIUsage$FusionJsonCodec(),
                        new ResponsesInput$FusionJsonCodec(),
                        new ResponsesRequest$FusionJsonCodec(),
                        new ResponsesResponse$FusionJsonCodec(),
                        new ResponsesResponse$IncompleteDetails$FusionJsonCodec(),
                        new ResponsesResponse$OutputContent$FusionJsonCodec(),
                        new ResponsesResponse$OutputItem$FusionJsonCodec(),
                        new ResponsesResponse$ResponsesUsage$FusionJsonCodec(),
                        new ResponsesStreamEvent$FusionJsonCodec(),
                        new ResponsesTool$FusionJsonCodec(),
                        new WireMessage$FusionJsonCodec(),
                        new WireMessage$WireFunction$FusionJsonCodec(),
                        new WireMessage$WireToolCall$FusionJsonCodec(),
                        new WireTool$FusionJsonCodec(),
                        new WireTool$WireToolFunction$FusionJsonCodec()),
                key -> Optional.empty());
    }

    private static ExtendedHttpClient client() {
        return new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(HttpClient.newHttpClient()));
    }

    private static LlmRequest request(final boolean stream) {
        return new LlmRequest(
                "m",
                List.of(LlmMessage.system("be concise"), LlmMessage.user("hi")),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                stream,
                java.util.Map.of());
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body)
            throws IOException {
        respond(exchange, status, "application/json", body);
    }

    private static void respond(
            final com.sun.net.httpserver.HttpExchange exchange,
            final int status,
            final String contentType,
            final String body)
            throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static List<LlmStreamEvent> collect(final Flow.Publisher<LlmStreamEvent> publisher) throws Exception {
        final var events = new CopyOnWriteArrayList<LlmStreamEvent>();
        final var done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final LlmStreamEvent item) {
                events.add(item);
            }

            @Override
            public void onError(final Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "stream did not complete in time");
        if (error.get() != null) {
            throw new AssertionError("stream errored", error.get());
        }
        return events;
    }

    @Test
    void completionsDialectPostsToChatCompletions() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            reqHeaders.set(exchange.getRequestHeaders().getFirst("authorization"));
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                            + "\"completion_tokens\":1,\"total_tokens\":4}}");
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper(),
                client(),
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals("Bearer sk-test", reqHeaders.get());
        assertEquals("hello", response.text());
        assertEquals(4, response.usage().totalTokens());
        final var body = reqBody.get();
        assertTrue(body.contains("\"model\":\"m\""));
    }

    @Test
    void completionsDialectStreamsTextDeltas() throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hel\"},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper(),
                client(),
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(30, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", null)),
                events);
    }

    @Test
    void completionsDialectListsModelsFromDerivedEndpoint() throws Exception {
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"data\":[{\"id\":\"a\",\"owned_by\":\"x\"},{\"id\":\"b\",\"owned_by\":\"y\"}]}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper(),
                client(),
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b"), models.ids());
    }

    @Test
    void completionsDialectMaps404ToDialectNotSupported() {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 404, "not found"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper(),
                client(),
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertTrue(
                failure instanceof DialectNotSupportedException,
                () -> "expected DialectNotSupportedException but got " + failure);
    }

    private static Optional<Throwable> failureOf(final java.util.concurrent.CompletableFuture<LlmResponse> future) {
        try {
            future.join();
            return Optional.empty();
        } catch (final java.util.concurrent.CompletionException e) {
            return Optional.of(e.getCause());
        }
    }

    @Test
    void responsesDialectPostsToResponses() throws Exception {
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"r1\",\"model\":\"m\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                            + "\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper(),
                client(),
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals("hello", response.text());
    }

    @Test
    void responsesDialectStreamsTextDeltas() throws Exception {
        final var sse = String.join(
                "\n",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"hel\"}",
                "",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"lo\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[]}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper(),
                client(),
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(30, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", Usage.EMPTY)),
                events);
    }

    @Test
    void namesMatchRoutingKeys() {
        final Dialect completions = new OpenAICompletionsDialect(mapper(), client(), ProviderSpec.of("http://x", "k"));
        final Dialect responses = new OpenAIResponsesDialect(mapper(), client(), ProviderSpec.of("http://x", "k"));
        assertEquals("openai-completions", completions.name());
        assertEquals("openai-responses", responses.name());
    }
}
