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
package io.yupiik.llm.client.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.anthropic.model.AnthropicMessage$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesRequest$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesResponse$AnthropicUsage$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesResponse$ContentBlock$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicMessagesResponse$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicModels$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicModels$Model$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicStreamEvent$Delta$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicStreamEvent$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.AnthropicTool$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.ErrorResponse$Error$FusionJsonCodec;
import io.yupiik.llm.client.anthropic.model.ErrorResponse$FusionJsonCodec;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.Usage;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicDialectTest {
    private HttpServer server;
    private final AtomicReference<String> reqHeaders = new AtomicReference<>();
    private final AtomicReference<String> reqBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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
                        new AnthropicMessage$FusionJsonCodec(),
                        new AnthropicMessagesRequest$FusionJsonCodec(),
                        new AnthropicMessagesResponse$AnthropicUsage$FusionJsonCodec(),
                        new AnthropicMessagesResponse$ContentBlock$FusionJsonCodec(),
                        new AnthropicMessagesResponse$FusionJsonCodec(),
                        new AnthropicModels$FusionJsonCodec(),
                        new AnthropicModels$Model$FusionJsonCodec(),
                        new AnthropicStreamEvent$Delta$FusionJsonCodec(),
                        new AnthropicStreamEvent$FusionJsonCodec(),
                        new AnthropicTool$FusionJsonCodec(),
                        new ErrorResponse$Error$FusionJsonCodec(),
                        new ErrorResponse$FusionJsonCodec()),
                key -> Optional.empty());
    }

    private static final HttpClient WARM_HTTP_CLIENT =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static ExtendedHttpClient client() {
        return new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(WARM_HTTP_CLIENT));
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
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondSse(final com.sun.net.httpserver.HttpExchange exchange, final String body)
            throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String port() {
        return "http://localhost:" + server.getAddress().getPort();
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
    void chatPostsToMessagesWithXApiKeyAndVersion() throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqHeaders.set("x-api-key:"
                    + exchange.getRequestHeaders().getFirst("x-api-key") + "|anthropic-version:"
                    + exchange.getRequestHeaders().getFirst("anthropic-version"));
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2}}");
        });
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("x-api-key:sk-test|anthropic-version:2023-06-01", reqHeaders.get());
        assertEquals("hello", response.text());
        assertEquals(6, response.usage().totalTokens());
        assertEquals(3L, response.usage().cacheRead());
        assertEquals(2l, response.usage().cacheWrite());
        final var body = reqBody.get();
        assertTrue(body.contains("\"max_tokens\":64"));
        assertTrue(body.contains("be concise"), body);
        assertTrue(body.contains("cache_control"), "system must carry a cache breakpoint:\n" + body);
        assertTrue(body.contains("\"model\":\"m\""));
    }

    @Test
    void promptCacheCanBeDisabledViaExtensions() throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
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
                null,
                false,
                java.util.Map.of("promptCache", "false"));
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertTrue(body.contains("\"system\":\"be concise\""), "plain string system expected:\n" + body);
        assertFalse(body.contains("cache_control"), body);
        assertFalse(body.contains("promptCache"), "the kill-switch key must not leak to the payload:\n" + body);
    }

    @Test
    void volatileTailMessageSplitsIntoTwoBlocksWithSecondBreakpoint() throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.system("be concise"),
                        new LlmMessage(
                                "user",
                                "prompt\n\n<session-state>\nDate: x\n</session-state>",
                                null,
                                null,
                                null,
                                java.util.Map.of("volatileTail", true))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                java.util.Map.of("anthropicSplitBlocks", true));
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        // system breakpoint + stable prefix breakpoint, volatile tail block without one
        assertEquals(
                2,
                body.split("cache_control", -1).length - 1,
                "expected two cache breakpoints (system + stable prefix):\n" + body);
        assertTrue(body.contains("<session-state>\\nDate: x\\n</session-state>"), body);
        assertFalse(body.contains("volatileTail"), "the message flag must not leak to the payload:\n" + body);
    }

    @Test
    void autoSplitBlocksStaysInlineOnGatewayHosts() throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.system("be concise"),
                        new LlmMessage(
                                "user",
                                "prompt\n\n<session-state>\nDate: x\n</session-state>",
                                null,
                                null,
                                null,
                                java.util.Map.of("volatileTail", true))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        // auto: non-anthropic.com host -> no split, tail inline, single system breakpoint
        assertEquals(
                1, body.split("cache_control", -1).length - 1, "expected a single cache breakpoint (system):\n" + body);
        assertTrue(body.contains("\"content\":\"prompt\\n\\n<session-state>\\nDate: x\\n</session-state>\""), body);
    }

    @Test
    void autoSplitBlocksHostDetection() {
        assertTrue(AnthropicDialect.isAnthropicHost("https://api.anthropic.com/v1/messages"));
        assertTrue(AnthropicDialect.isAnthropicHost("https://anthropic.com/v1/messages"));
        assertTrue(AnthropicDialect.isAnthropicHost("https://foo.anthropic.com"));
        assertFalse(AnthropicDialect.isAnthropicHost("https://freeinference.org/anthropic/v1/messages"));
        assertFalse(AnthropicDialect.isAnthropicHost("http://127.0.0.1:8643/anthropic/v1"));
        assertFalse(AnthropicDialect.isAnthropicHost("https://anthropic.com.evil.io/v1/messages"));
        assertFalse(AnthropicDialect.isAnthropicHost(null));
    }

    @Test
    void anthropicSplitBlocksFalseKeepsInlineTailAndStripsTheFlag() throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.system("be concise"),
                        new LlmMessage(
                                "user",
                                "prompt\n\n<session-state>\nDate: x\n</session-state>",
                                null,
                                null,
                                null,
                                java.util.Map.of("volatileTail", true))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                java.util.Map.of("anthropicSplitBlocks", false));
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        // only the system keeps a breakpoint; the tail rides inline as a plain string
        assertEquals(
                1, body.split("cache_control", -1).length - 1, "expected a single cache breakpoint (system):\n" + body);
        assertTrue(body.contains("\"content\":\"prompt\\n\\n<session-state>\\nDate: x\\n</session-state>\""), body);
        assertFalse(body.contains("anthropicSplitBlocks"), "the flag must not leak to the payload:\n" + body);
    }

    @Test
    void streamEmitsTextDeltasAndEnd() throws Exception {
        final var sse = String.join(
                "\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", new Usage(5L, 2L, 7L))),
                events);
    }

    @Test
    void streamEmitsToolArgumentDeltasPerOfficialProtocol() throws Exception {
        // the real API streams tool JSON as content_block_delta with delta.type=input_json_delta
        final var sse = String.join(
                "\n",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"bash\",\"input\":{}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"ls\\\"}\"}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}",
                "",
                "");
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.ToolCallDelta(1, "t1", "bash", ""),
                        new LlmStreamEvent.ToolCallDelta(1, null, null, "{\"command\":"),
                        new LlmStreamEvent.ToolCallDelta(1, null, null, "\"ls\"}"),
                        new LlmStreamEvent.End("tool_calls", new Usage(5L, 3L, 8L))),
                events);
    }

    @Test
    void modelsListsIds() throws Exception {
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"data\":[{\"type\":\"model\",\"id\":\"a\",\"display_name\":\"A\"},{\"type\":\"model\",\"id\":\"b\",\"display_name\":\"B\"}]}"));
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b"), models.ids());
    }

    @Test
    void modelsMaps404ToDialectNotSupported() {
        server.createContext("/v1/models", exchange -> {
            try {
                respond(exchange, 404, "not found");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var completion = dialect.models().toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertTrue(
                failure instanceof DialectNotSupportedException,
                () -> "expected DialectNotSupportedException but got " + failure);
    }

    @Test
    void chatMaps404ToDialectNotSupported() {
        server.createContext("/v1/messages", exchange -> {
            try {
                respond(exchange, 404, "not found");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertTrue(
                failure instanceof DialectNotSupportedException,
                () -> "expected DialectNotSupportedException but got " + failure);
    }

    @Test
    void mapsToolUseToFunctionCall() throws Exception {
        server.createContext(
                "/v1/messages",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read\",\"input\":{\"path\":\"/a\"}}],"
                                + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}"));
        server.start();
        final var dialect =
                new AnthropicDialect(mapper(), client(), ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var calls = response.first().message().toolCalls();
        assertEquals(1, calls.size());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"/a\"}", calls.get(0).function().arguments());
    }

    @Test
    void exposesNameForRouting() {
        final Dialect dialect = new AnthropicDialect(mapper(), client(), ProviderSpec.of("http://x", "k"));
        assertEquals("anthropic-messages", dialect.name());
    }

    private static <T> Optional<Throwable> failureOf(final java.util.concurrent.CompletableFuture<T> future) {
        try {
            future.join();
            return Optional.empty();
        } catch (final java.util.concurrent.CompletionException e) {
            return Optional.of(e.getCause());
        }
    }
}
