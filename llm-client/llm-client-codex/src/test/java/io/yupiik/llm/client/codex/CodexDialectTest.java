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
package io.yupiik.llm.client.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class CodexDialectTest {
    private HttpServer server;
    private String base;
    private ExtendedHttpClient client;
    private HttpClient delegate;
    private final AtomicReference<String> reqPath = new AtomicReference<>();
    private final AtomicReference<String> reqAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (client != null) {
            client.close();
            delegate.shutdownNow();
            delegate.close();
            delegate = null;
            client = null;
        }
    }

    @Test
    void listsAndFiltersCodexModels(final JsonMapper mapper) throws Exception {
        server.createContext("/codex/models", exchange -> {
            reqPath.set(exchange.getRequestURI().toString());
            reqAuth.set(exchange.getRequestHeaders().getFirst("authorization"));
            respond(
                    exchange,
                    200,
                    "{\"models\":["
                            + "{\"slug\":\"gpt-5.3-codex\",\"display_name\":\"GPT-5.3 Codex\","
                            + "\"visibility\":\"list\",\"supported_in_api\":true,\"minimal_client_version\":[0,1,0],"
                            + "\"context_window\":400000,\"max_context_window\":400000},"
                            + "{\"slug\":\"gpt-reserve\",\"display_name\":\"Reserve\","
                            + "\"visibility\":\"hide\",\"supported_in_api\":true,\"minimal_client_version\":[0,1,0]},"
                            + "{\"slug\":\"codex-auto-review\",\"display_name\":\"Review\","
                            + "\"visibility\":\"none\",\"supported_in_api\":true,\"minimal_client_version\":[0,1,0]},"
                            + "{\"slug\":\"gpt-6-gated\",\"display_name\":\"Gated\","
                            + "\"visibility\":\"list\",\"supported_in_api\":true,"
                            + "\"minimal_client_version\":[200,0,0]}"
                            + "]}");
        });

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses",
                        "fallback-key",
                        null,
                        null,
                        // simulates the {{codexAuth}} dynamic header: the resolver injects the access token
                        builder -> builder.setHeader("authorization", "Bearer oauth-live-token")));
        final LlmModels models = dialect.models().toCompletableFuture().get();

        // the path requested the catalog with the client_version
        final String path = reqPath.get();
        assertTrue(path.startsWith("/codex/models?client_version="), "unexpected path " + path);

        // the OAuth bearer set by the headers customizer must be sent (the catalog is authenticated)
        assertEquals("Bearer oauth-live-token", reqAuth.get(), "the codexAuth token must reach the models request");

        // only the selectable, not version-gated model survives
        assertEquals(1, models.data().size());
        assertEquals("gpt-5.3-codex", models.data().get(0).id());
        assertEquals(400_000, models.data().get(0).contextWindow());
    }

    @Test
    void streamsSseWithoutEventStreamContentType(final JsonMapper mapper) throws Exception {
        // the Codex backend streams SSE but does NOT set a text/event-stream content-type header,
        // which used to make the 2xx body be buffered as an error ("LLM request failed (200)").
        server.createContext("/codex/responses", exchange -> {
            // deliberately no content-type header: mimics chatgpt.com/backend-api/codex
            final byte[] sse = ("event: response.created\n"
                            + "data: {\"type\":\"response.created\"}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\",\"output_index\":0}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\" world\",\"output_index\":0}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")));
        final var sb = new StringBuilder();
        final var latch = new java.util.concurrent.CountDownLatch(1);
        final var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        dialect.stream(request(), () -> false)
                .toCompletableFuture()
                .join()
                .subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(io.yupiik.llm.client.model.LlmStreamEvent e) {
                        if (e instanceof io.yupiik.llm.client.model.LlmStreamEvent.TextDelta t) {
                            sb.append(t.text());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        err.set(t);
                        latch.countDown();
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }
                });
        latch.await();
        assertEquals("hello world", sb.toString(), "the streamed text must be produced, not buffered as an error");
        org.junit.jupiter.api.Assertions.assertNull(err.get(), "no stream error expected: " + err.get());
    }

    private io.yupiik.llm.client.model.LlmRequest request() {
        return new io.yupiik.llm.client.model.LlmRequest(
                "gpt-6-luna",
                List.of(new io.yupiik.llm.client.model.LlmMessage("user", "hi", null, null, null, null)),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true,
                java.util.Map.of());
    }

    private static void respond(
            final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body) {
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } finally {
            exchange.close();
        }
    }
}
