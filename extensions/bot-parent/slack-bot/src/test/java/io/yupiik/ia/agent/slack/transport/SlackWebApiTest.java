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
package io.yupiik.ia.agent.slack.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class SlackWebApiTest {
    @Test
    void routesEachMethodToTheRightCredentialAndNeverUsesGet() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.socketDiscovery();
            final var api = fixture.api(4096, 4096);
            assertEquals(
                    URI.create(fixture.socketUrl()),
                    api.openConnection().toCompletableFuture().join());
            var request = fixture.take(fixture.requests);
            assertEquals("/api/apps.connections.open", request.path());
            assertEquals("Bearer xapp-secret", request.headers().get("authorization"));
            assertEquals("POST", request.method());
            assertEquals("{}", request.body());

            fixture.respond(
                    ignored -> new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true,\"ts\":\"1.2\"}"));
            assertEquals(
                    "1.2",
                    api.postMessage(Map.of("channel", "C1", "text", "hello"))
                            .toCompletableFuture()
                            .join()
                            .get("ts"));
            request = fixture.take(fixture.requests);
            assertEquals("/api/chat.postMessage", request.path());
            assertEquals("Bearer xoxb-secret", request.headers().get("authorization"));
            assertEquals("POST", request.method());
            assertEquals(
                    Map.of("channel", "C1", "text", "hello"), fixture.json.fromString(Object.class, request.body()));
            api.openView(Map.of("trigger_id", "trigger", "view", Map.of("type", "modal")))
                    .toCompletableFuture()
                    .join();
            request = fixture.take(fixture.requests);
            assertEquals("/api/views.open", request.path());
            assertEquals("Bearer xoxb-secret", request.headers().get("authorization"));
            assertEquals("POST", request.method());
        }
    }

    @Test
    void returnsRetryAfterWithoutReplayingPublication() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(
                    ignored -> new SlackTransportFixture.Response(429, Map.of("Retry-After", "37"), "xoxb-secret"));
            final var error = failure(fixture.api(100, 100).postMessage(Map.of()));
            assertEquals(429, error.status());
            assertEquals("ratelimited", error.error());
            assertEquals(Duration.ofSeconds(37), error.retryAfter().orElseThrow());
            fixture.take(fixture.requests);
            assertTrue(fixture.requests.isEmpty());
            assertEquals(1, fixture.extended.getRequestCount());
        }
    }

    @Test
    void doesNotFollowSameOriginOrCrossOriginRedirects() throws Exception {
        try (final var fixture = new SlackTransportFixture();
                final var target = new SlackTransportFixture()) {
            final var api = fixture.api(100, 100);
            for (final var location : List.of(fixture.base().resolve("other"), target.base())) {
                fixture.respond(ignored ->
                        new SlackTransportFixture.Response(302, Map.of("Location", location.toString()), ""));
                assertEquals(302, failure(api.postMessage(Map.of())).status());
                fixture.take(fixture.requests);
                assertTrue(fixture.requests.isEmpty());
                assertTrue(target.requests.isEmpty(), "Credentials were forwarded to redirect target");
            }
            assertEquals(2, fixture.extended.getRequestCount());
        }
    }

    @Test
    void boundsRequestsBeforeSendingAndBoundsResponseBytes() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = fixture.api(16, 16);
            assertEquals(
                    "request_too_large",
                    failure(api.postMessage(Map.of("text", "x".repeat(32)))).error());
            assertTrue(fixture.requests.isEmpty());
            fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), "x".repeat(1024)));
            assertEquals(
                    "response_too_large", failure(api.postMessage(Map.of())).error());
            assertEquals(1, fixture.extended.getRequestCount());
        }
    }

    @Test
    void oversized429StillProvidesRetryMetadata() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            fixture.respond(
                    ignored -> new SlackTransportFixture.Response(429, Map.of("Retry-After", "37"), "x".repeat(1024)));
            final var error = failure(fixture.api(16, 16).postMessage(Map.of()));
            assertEquals(429, error.status());
            assertEquals(Duration.ofSeconds(37), error.retryAfter().orElseThrow());
            assertEquals(1, fixture.extended.getRequestCount());
        }
    }

    @Test
    void sanitizesRemoteErrorsMalformedJsonAndThrownCredentialSupplier() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = fixture.api(4096, 4096);
            for (final var body : List.of("{\"ok\":false,\"error\":\"xoxb-secret\"}", "xapp-secret", "[]", "{}")) {
                fixture.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), body));
                final var error = failure(api.postMessage(Map.of()));
                assertFalse(error.toString().contains("secret"));
                assertNull(error.getCause());
                assertEquals(0, error.getSuppressed().length);
            }
            final var bad = new SlackWebApi(
                    fixture.json,
                    fixture.extended,
                    new SlackWebApi.Settings(fixture.base(), Duration.ofMillis(100), 100, 100),
                    () -> {
                        throw new IllegalStateException("xapp-secret");
                    },
                    () -> "xapp-wrong-kind");
            assertEquals("request_error", failure(bad.openConnection()).error());
            assertEquals("invalid_token", failure(bad.postMessage(Map.of())).error());
        }
    }

    @Test
    void returnsSafeApiFailureAndRejectsUnsafeSocketUrls() throws Exception {
        try (final var fixture = new SlackTransportFixture()) {
            final var api = fixture.api(1024, 1024);
            fixture.respond(ignored ->
                    new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":false,\"error\":\"invalid_auth\"}"));
            assertEquals("invalid_auth", failure(api.openConnection()).error());
            for (final var url : List.of(
                    "https://slack.com/secret",
                    "ws://example.com/secret",
                    "wss://user:secret@slack.com/",
                    "%%%secret")) {
                fixture.respond(ignored ->
                        new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true,\"url\":\"" + url + "\"}"));
                final var error = failure(api.openConnection());
                assertEquals("invalid_response", error.error());
                assertFalse(error.toString().contains("secret"));
            }
        }
    }

    @Test
    void rejectsUnsafeBaseAndRedirectingClient() throws Exception {
        try (final var fixture = new SlackTransportFixture();
                final var delegate = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();
                final var redirecting =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate))) {
            for (final var base : List.of(
                    "http://example.com/api/",
                    "https://secret@slack.com/api/",
                    "https://slack.com/api/?token=secret",
                    "https://slack.com/api")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SlackWebApi.Settings(URI.create(base), Duration.ofSeconds(1), 10, 10));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackWebApi(
                            fixture.json,
                            redirecting,
                            new SlackWebApi.Settings(fixture.base(), Duration.ofSeconds(1), 10, 10),
                            () -> "xapp-secret",
                            () -> "xoxb-secret"));
        }
    }

    private SlackWebApi.SlackApiException failure(final CompletionStage<?> stage) {
        final var error = assertThrows(
                CompletionException.class, () -> stage.toCompletableFuture().join());
        return assertInstanceOf(SlackWebApi.SlackApiException.class, error.getCause());
    }
}
