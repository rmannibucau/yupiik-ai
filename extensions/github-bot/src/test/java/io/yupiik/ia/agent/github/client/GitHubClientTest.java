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
package io.yupiik.ia.agent.github.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitHubClientTest {
    @Test
    void graphqlBindsVariablesRejectsPartialDataAndNeverReplays() throws Exception {
        final var requests = new AtomicInteger();
        final var received = new AtomicReference<String>();
        final var response = new AtomicReference<>("{\"data\":{\"node\":{\"id\":\"D_1\"}}}");
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            requests.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final var bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "secret")) {
            final var query = "query($id:ID!){node(id:$id){id}}";
            assertEquals(
                    Map.of("data", Map.of("node", Map.of("id", "D_1"))), client.graphql(query, Map.of("id", "D_1")));
            final var wire = json.fromString(GitHubClient.GraphqlRequest.class, received.get());
            assertEquals(query, wire.query());
            assertEquals(Map.of("id", "D_1"), wire.variables());
            response.set("{\"data\":{\"node\":null},\"errors\":[{\"message\":\"private detail\"}]}");
            final var failure =
                    assertThrows(IllegalStateException.class, () -> client.graphql(query, Map.of("id", "D_1")));
            assertFalse(failure.getMessage().contains("private detail"));
            final var variables = new DiscussionClient.Variables(null, null, null, "D_1", null);
            for (final var invalid : List.of(
                    "{\"data\":{\"node\":null},\"errors\":null}",
                    "{\"data\":{\"node\":null},\"errors\":[]}",
                    "{\"data\":null}",
                    "{}")) {
                response.set(invalid);
                assertThrows(IllegalStateException.class, () -> client.discussionGraphql(query, variables));
            }
            assertEquals(6, requests.get());

        } finally {
            server.stop(0);
        }
    }

    @Test
    void closingClientDoesNotCloseInjectedMapper() {
        final var closed = new AtomicInteger();
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty()) {
            @Override
            public void close() {
                closed.incrementAndGet();
                super.close();
            }
        }) {
            final var client = new GitHubClient(URI.create("https://api.github.com"), json, () -> "unused");
            client.close();
            client.close();
            assertEquals(0, closed.get());
            assertEquals(Map.of("still", "usable"), json.fromString(Object.class, "{\"still\":\"usable\"}"));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void rejectsOversizedFixedAndChunkedResponsesWithoutRetry() throws Exception {
        final var requests = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            final var block = new byte[64 * 1024];
            Arrays.fill(block, (byte) ' ');
            exchange.sendResponseHeaders(
                    200, exchange.getRequestURI().getPath().equals("/fixed") ? 129L * block.length : 0);
            try (exchange) {
                for (int i = 0; i < 129; i++) {
                    exchange.getResponseBody().write(block);
                }
            } catch (final IOException ignored) {
                // Cancellation can close the socket before the stub finishes sending.
            }
        });
        server.start();
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "secret")) {
            for (final var path : List.of("/fixed", "/chunked")) {
                final var failure = assertThrows(IllegalStateException.class, () -> client.get(path));
                assertTrue(failure.getMessage().contains("transport byte limit"));
            }
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheEvictsByAggregateBytes() throws Exception {
        final var conditional = new AtomicInteger();
        final var body = ("{\"value\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                conditional.incrementAndGet();
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("ETag", "etag");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "secret")) {
            client.get("/first");
            client.get("/second");
            client.get("/second");
            assertEquals(1, conditional.get());
            client.get("/first");
            assertEquals(1, conditional.get(), "First large page must have been evicted");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void genericResponsesPreserveWireShapesAndExactNumbers() {
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
            for (final var source : List.of(
                    "{}",
                    "[]",
                    "null",
                    "true",
                    "false",
                    "\"escaped\\nvalue\"",
                    "9223372036854775808",
                    "{\"items\":[null,true,false,1.5,{\"body\":\"text\"}],\"empty\":{}}")) {
                final var value = json.fromString(Object.class, source);
                assertEquals(json.fromString(Object.class, source), value);
            }
            final var value = json.fromString(Object.class, "{\"id\":9223372036854775808,\"items\":[null,true]}");
            final var object = (Map<?, ?>) value;
            assertEquals(new BigDecimal("9223372036854775808"), object.get("id"));
            assertEquals(Arrays.asList(null, true), object.get("items"));
            assertThrows(RuntimeException.class, () -> json.fromString(Object.class, "{\"a\":"));
        }
    }

    @Test
    void commentRequestUsesGitHubWireShape() throws Exception {
        final var received = new AtomicReference<String>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/comments", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(
                        List.of(new GitHubClient$CommentRequest$FusionJsonCodec()), key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "secret")) {
            assertEquals(Map.of(), client.post("/comments", new GitHubClient.CommentRequest("line\n\"quoted\"")));
            assertEquals("{\"body\":\"line\\n\\\"quoted\\\"\"}", received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pacesReadsAndWritesAndValidatesInterval() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (final var json = new JsonMapperImpl(
                        List.of(
                                new GitHubClient$CommentRequest$FusionJsonCodec(),
                                new GitHubClient$ForkRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewFinding$FusionJsonCodec(),
                                new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec(),
                                new AuthTokenProvider$JwtHeader$FusionJsonCodec(),
                                new AuthTokenProvider$JwtClaims$FusionJsonCodec()),
                        key -> Optional.empty());
                final var client = new GitHubClient(base, json, () -> "secret", Duration.ofMillis(100))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GitHubClient(base, json, () -> "secret", Duration.ofMillis(-1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GitHubClient(base, json, () -> "secret", Duration.ofSeconds(61)));
            final long start = System.nanoTime();
            client.get("/read");
            client.post("/write", new GitHubClient.CommentRequest("write"));
            client.get("/read-again");
            assertTrue(System.nanoTime() - start >= Duration.ofMillis(200).toNanos());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesPlaintextRemoteCredentialTransportBeforeReadingToken() {
        try (final var json = new JsonMapperImpl(
                List.of(
                        new GitHubClient$CommentRequest$FusionJsonCodec(),
                        new GitHubClient$ForkRequest$FusionJsonCodec(),
                        new GitHubClient$ReviewRequest$FusionJsonCodec(),
                        new GitHubClient$ReviewFinding$FusionJsonCodec(),
                        new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec(),
                        new AuthTokenProvider$JwtHeader$FusionJsonCodec(),
                        new AuthTokenProvider$JwtClaims$FusionJsonCodec()),
                key -> Optional.empty())) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GitHubClient(URI.create("http://github.example/api"), json, () -> {
                        throw new AssertionError("must not read token");
                    }));
        }
    }

    @Test
    void exposesActualHttpStatusWithoutParsingResponseBody() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var bytes = "not JSON: GitHub GET returned 404".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(
                    exchange.getRequestURI().getPath().equals("/missing") ? 404 : 403, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(
                        List.of(
                                new GitHubClient$CommentRequest$FusionJsonCodec(),
                                new GitHubClient$ForkRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewFinding$FusionJsonCodec(),
                                new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec(),
                                new AuthTokenProvider$JwtHeader$FusionJsonCodec(),
                                new AuthTokenProvider$JwtClaims$FusionJsonCodec()),
                        key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "secret")) {
            assertEquals(
                    404,
                    assertThrows(GitHubClient.HttpException.class, () -> client.get("/missing"))
                            .status());
            assertEquals(
                    403,
                    assertThrows(GitHubClient.HttpException.class, () -> client.get("/forbidden"))
                            .status());
            assertEquals(
                    403,
                    assertThrows(
                                    GitHubClient.HttpException.class,
                                    () -> client.post("/forbidden", new GitHubClient.CommentRequest("forbidden")))
                            .status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheDoesNotAliasEscapedUrlsOrCredentials() throws Exception {
        final var credential = new AtomicReference<>("first");
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var seen = new CopyOnWriteArrayList<String>();
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestURI().toString() + ":"
                    + exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().add("ETag", "\"cached\"");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes());
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(
                        List.of(
                                new GitHubClient$CommentRequest$FusionJsonCodec(),
                                new GitHubClient$ForkRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewFinding$FusionJsonCodec(),
                                new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec(),
                                new AuthTokenProvider$JwtHeader$FusionJsonCodec(),
                                new AuthTokenProvider$JwtClaims$FusionJsonCodec()),
                        key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> credential.get())) {
            client.get("/items?q=%2f");
            client.get("/items?q=%2F");
            client.get("/items?q=%2f");
            credential.set("rotated");
            client.get("/items?q=%2f");
            assertEquals(
                    List.of("/items?q=%2f:null", "/items?q=%2F:null", "/items?q=%2f:\"cached\"", "/items?q=%2f:null"),
                    seen);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void etagIsPerExactUrlAndRetainsPagination() throws Exception {
        final var calls = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/items", exchange -> {
            calls.incrementAndGet();
            final var page = exchange.getRequestURI().getQuery();
            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                assertEquals("\"" + page + "\"", exchange.getRequestHeaders().getFirst("If-None-Match"));
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().add("ETag", "\"" + page + "\"");
                exchange.getResponseHeaders().add("Link", "<" + base + "/items?page=2>; rel=\"next\"");
                final var bytes = ("{\"page\":\"" + page + "\"}").getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(
                        List.of(
                                new GitHubClient$CommentRequest$FusionJsonCodec(),
                                new GitHubClient$ForkRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewFinding$FusionJsonCodec(),
                                new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec(),
                                new AuthTokenProvider$JwtHeader$FusionJsonCodec(),
                                new AuthTokenProvider$JwtClaims$FusionJsonCodec()),
                        key -> Optional.empty());
                final var client = new GitHubClient(base, json, () -> "secret")) {
            final var first = client.page("/items?page=1");
            assertEquals(first, client.page("/items?page=1"));
            assertEquals(base + "/items?page=2", first.next());
            assertEquals(Map.of("page", "page=2"), client.get(first.next()));
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundedGetRetriesButNeverPostOrRedirects() throws Exception {
        final var gets = new AtomicInteger();
        final var posts = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/retry", exchange -> {
            final boolean get = exchange.getRequestMethod().equals("GET");
            final int count = (get ? gets : posts).incrementAndGet();
            final int status = get && count == 3 ? 200 : 503;
            exchange.sendResponseHeaders(status, 2);
            exchange.getResponseBody().write("{}".getBytes());
            exchange.close();
        });
        server.createContext("/limit", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "3600");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.createContext("/reset", exchange -> {
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
            exchange.getResponseHeaders()
                    .add(
                            "X-RateLimit-Reset",
                            Long.toString(Instant.now().plusSeconds(1800).getEpochSecond()));
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/retry");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/link", exchange -> {
            exchange.getResponseHeaders().add("Link", "<https://example.com/leak>; rel=\"next\"");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes());
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(
                        List.of(
                                new GitHubClient$CommentRequest$FusionJsonCodec(),
                                new GitHubClient$ForkRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewRequest$FusionJsonCodec(),
                                new GitHubClient$ReviewFinding$FusionJsonCodec(),
                                new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec(),
                                new AuthTokenProvider$JwtHeader$FusionJsonCodec(),
                                new AuthTokenProvider$JwtClaims$FusionJsonCodec()),
                        key -> Optional.empty());
                final var client = new GitHubClient(base, json, () -> "secret")) {
            assertEquals(Map.of(), client.get("/retry"));
            assertEquals(3, gets.get());
            assertThrows(IllegalStateException.class, () -> client.get("/redirect"));
            assertEquals(3, gets.get());
            assertThrows(
                    IllegalStateException.class, () -> client.post("/retry", new GitHubClient.CommentRequest("retry")));
            assertEquals(1, posts.get());
            final var limit = assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/limit"));
            assertEquals(429, limit.status());
            assertTrue(limit.retryAt().isAfter(Instant.now().plusSeconds(3500)));
            assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/reset"));
            assertThrows(IllegalArgumentException.class, () -> client.get("https://example.com/leak"));
            assertThrows(IllegalArgumentException.class, () -> client.get("//example.com/leak"));
            assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/link"));
            assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/retry"));
            assertEquals(3, gets.get());
        } finally {
            server.stop(0);
        }
    }
}
