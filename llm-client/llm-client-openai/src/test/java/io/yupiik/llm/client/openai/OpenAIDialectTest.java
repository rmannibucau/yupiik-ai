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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class OpenAIDialectTest {
    private HttpServer server;
    private HttpClient delegate;
    private ExtendedHttpClient client;
    private final AtomicReference<String> reqHeaders = new AtomicReference<>();
    private final AtomicReference<String> reqBody = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> streamTexts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        if (server != null) { // close the server first so pooled keep-alive connections die before the client
            server.stop(0);
            server = null;
        }
        if (client != null) {
            client.close();
            // HttpResponse/completion is done at that point: the graceful shutdown can wait forever on a
            // lingering (SSE/keep-alive) connection of the test server, so abort the selector first.
            delegate.shutdownNow();
            delegate.close();
            client = null;
            delegate = null;
        }
    }

    private static LlmRequest request(final boolean stream) {
        return request(stream, java.util.Map.of());
    }

    private static LlmRequest request(final boolean stream, final java.util.Map<String, Object> extensions) {
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
                extensions);
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
        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete in time");
        if (error.get() != null) {
            throw new AssertionError("stream errored", error.get());
        }
        return events;
    }

    @Test
    void completionsDialectPostsToChatCompletions(final JsonMapper mapper) throws Exception {
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
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("Bearer sk-test", reqHeaders.get());
        assertEquals("hello", response.text());
        assertEquals(4, response.usage().totalTokens());
        final var body = reqBody.get();
        assertTrue(body.contains("\"model\":\"m\""));
        assertTrue(body.contains("\"max_tokens\":64"), body);
    }

    @Test
    void completionsDialectSendsRenderedDynamicHeaders(final JsonMapper mapper) throws Exception {
        // the dynamic header is a {{exec}} template: the wire must carry the rendered stdout
        // (with the exec_options cache warming so a second request reuses it)
        server.createContext("/v1/chat/completions", exchange -> {
            final var auth = exchange.getRequestHeaders().getFirst("authorization");
            if (reqHeaders.compareAndSet(null, auth)) {
                reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                            + "\"completion_tokens\":1,\"total_tokens\":4}}");
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                new ProviderSpec(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions",
                        "sk-test",
                        java.time.Duration.ofSeconds(5),
                        java.util.Map.of(),
                        builder -> builder.setHeader("authorization", "Bearer tok-abc")));
        // the customizer is applied on each request (chat POST here)
        dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("Bearer tok-abc", reqHeaders.get());
    }

    @Test
    void completionsDialectReadsTheProviderSideGenerationTime(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,\"generation_time\":2.5}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                2_500L, response.usage().generationTime(), "a gateway generation_time in seconds is normalized to ms");
    }

    @Test
    void completionsDialectDerivesTheGenerationTimeFromAReportedRate(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,\"tokens_per_second\":50}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(2_000L, response.usage().generationTime(), "100 tokens at 50 tok/s is 2s of generation");
    }

    @Test
    void completionsDialectHasNoGenerationTimeWhenTheProviderReportsNone(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":1,\"total_tokens\":4}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertNull(
                response.usage().generationTimeMs(),
                "a standard payload carries no timing: the client measures instead");
    }

    @Test
    void completionsDialectReadsTheReasoningTokensFromTheOutputBreakdown(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,"
                                + "\"completion_tokens_details\":{\"reasoning_tokens\":40}}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                40L,
                response.usage().reasoning(),
                "the completion_tokens_details.reasoning_tokens breakdown must reach the neutral usage");
        assertEquals(100, response.usage().completion(), "reasoning tokens stay inside the completion aggregate");
    }

    @Test
    void completionsDialectReadsTheReasoningTokensFromANonStandardTopLevelField(final JsonMapper mapper)
            throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,\"reasoning_tokens\":25}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                25L,
                response.usage().reasoning(),
                "a non-standard top-level reasoning_tokens field is the fallback when no breakdown object exists");
    }

    @Test
    void responsesDialectReadsTheReasoningTokensFromTheOutputBreakdown(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/responses",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"r1\",\"model\":\"m\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{\"input_tokens\":3,"
                                + "\"output_tokens\":100,\"total_tokens\":103,"
                                + "\"output_tokens_details\":{\"reasoning_tokens\":30}}}"));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                30L,
                response.usage().reasoning(),
                "the output_tokens_details.reasoning_tokens breakdown must reach the neutral usage");
    }

    @Test
    void completionsDialectStreamsTextDeltas(final JsonMapper mapper) throws Exception {
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
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", null)),
                events);
    }

    @Test
    void completionsStreamAsksForIncludedUsage(final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,"
                        + "\"completion_tokens\":1,\"total_tokens\":4}}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/event-stream", sse);
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertTrue(reqBody.get().contains("\"stream_options\""), reqBody.get());
        assertTrue(reqBody.get().contains("\"include_usage\":true"), reqBody.get());
        assertTrue(
                events.stream().anyMatch(e -> e instanceof LlmStreamEvent.End end && end.usage() != null),
                "usage chunk must surface an End with usage, got: " + events);
    }

    @Test
    void completionsStreamUsageCanBeDisabledOrOverridden(final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (reqBody.get().contains("\"stream\":true")) {
                respond(exchange, 200, "text/event-stream", sse);
            } else {
                respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,"
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
            }
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        dialect.stream(request(true, java.util.Map.of("includeUsage", false)), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(!reqBody.get().contains("stream_options"), "disabled by includeUsage=false: " + reqBody.get());
        assertTrue(!reqBody.get().contains("includeUsage"), "control key must not leak: " + reqBody.get());

        dialect.stream(
                        request(true, java.util.Map.of("stream_options", java.util.Map.of("include_usage", false))),
                        () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(
                reqBody.get().contains("\"include_usage\":false"),
                "explicit stream_options must win: " + reqBody.get());

        dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(!reqBody.get().contains("stream_options"), "non-stream must not ask for usage: " + reqBody.get());
    }

    @Test
    void completionsDialectListsModelsFromDerivedEndpoint(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"data\":[{\"id\":\"a\",\"owned_by\":\"x\"},{\"id\":\"b\",\"owned_by\":\"y\"}]}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b"), models.ids());
    }

    @Test
    void completionsDialectMaps404ToDialectNotSupported(final JsonMapper mapper) {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 404, "not found"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertTrue(
                failure instanceof DialectNotSupportedException,
                () -> "expected DialectNotSupportedException but got " + failure);
        assertTrue(
                failure.getMessage().contains("not found"),
                () -> "expected the server body to surface but got: " + failure.getMessage());
    }

    @Test
    void completionsDialectSurfacesProviderErrorBodyOnFlavorMissing(final JsonMapper mapper) {
        server.createContext(
                "/v1/chat/completions",
                exchange ->
                        respond(exchange, 404, "{\"error\":{\"message\":\"Model ' deepseek-v4-flash' not found\"}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertTrue(
                failure instanceof DialectNotSupportedException,
                () -> "expected DialectNotSupportedException but got " + failure);
        assertTrue(
                failure.getMessage().contains("Model ' deepseek-v4-flash' not found"),
                () -> "expected the provider error body to surface but got: " + failure.getMessage());
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
    void responsesDialectPostsToResponses(final JsonMapper mapper) throws Exception {
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
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("hello", response.text());
        assertTrue(reqBody.get().contains("\"max_output_tokens\":64"), reqBody.get());
    }

    @Test
    void responsesDialectRequestsReasoningSummary(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"r1\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        // reasoningEffort is the 10th component of LlmRequest (before stream/extensions)
        final var request = new LlmRequest(
                "m",
                List.of(LlmMessage.system("be concise"), LlmMessage.user("hi")),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                "low",
                false,
                java.util.Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // 'summary' is what makes official OpenAI stream reasoning deltas at all
        assertTrue(reqBody.get().contains("\"summary\":\"auto\""), reqBody.get());
        assertTrue(reqBody.get().contains("\"effort\":\"low\""), reqBody.get());
    }

    @Test
    void responsesDialectKeepsAllNonStreamToolCalls(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/responses",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"r1\",\"model\":\"m\",\"output\":["
                                + "{\"type\":\"function_call\",\"id\":\"f1\",\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"},"
                                + "{\"type\":\"function_call\",\"id\":\"f2\",\"name\":\"grep\",\"arguments\":\"{\\\"pattern\\\":\\\"x\\\"}\"}],"
                                + "\"usage\":{}}"));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var calls = response.first().message().toolCalls();
        assertEquals(2, calls.size(), () -> "both parallel calls must survive, got " + calls);
        assertEquals("read", calls.get(0).function().name());
        assertEquals("grep", calls.get(1).function().name());
        assertEquals("{\"path\":\"a\"}", calls.get(0).function().arguments());
        assertEquals("{\"pattern\":\"x\"}", calls.get(1).function().arguments());
    }

    @Test
    void responsesDialectStreamsTextDeltas(final JsonMapper mapper) throws Exception {
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
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", Usage.EMPTY)),
                events);
    }

    @Test
    void responsesDialectStreamsReasoningSummaryDeltas(final JsonMapper mapper) throws Exception {
        // gateways commonly stream only the reasoning summary, not the full reasoning text
        final var sse = String.join(
                "\n",
                "event: response.reasoning_summary_text.delta",
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"i\",\"delta\":\"let me \"}",
                "",
                "event: response.reasoning_summary_text.delta",
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"i\",\"delta\":\"think\"}",
                "",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"hi\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[]}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.ReasoningDelta("let me "),
                        new LlmStreamEvent.ReasoningDelta("think"),
                        new LlmStreamEvent.TextDelta("hi"),
                        new LlmStreamEvent.End("stop", Usage.EMPTY)),
                events);
    }

    @Test
    void namesMatchRoutingKeys(final JsonMapper mapper) {
        final Dialect completions = new OpenAICompletionsDialect(mapper, client, ProviderSpec.of("http://x", "k"));
        final Dialect responses = new OpenAIResponsesDialect(mapper, client, ProviderSpec.of("http://x", "k"));
        assertEquals("openai-completions", completions.name());
        assertEquals("openai-responses", responses.name());
    }
}
