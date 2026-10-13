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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StaticAssetsExchangeHandlerTest {

    private static HttpClient client;

    private JreHttpServer server;

    @BeforeAll
    static void warm() {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private JreHttpServer start(final io.yupiik.fusion.http.server.spi.Endpoint... endpoints) {
        final var instance = new JreHttpServer(new JreHttpServerConfiguration(null, 0, true), List.of(endpoints));
        instance.start();
        server = instance;
        return instance;
    }

    private static String url(final JreHttpServer s, final String path) {
        return "http://127.0.0.1:" + s.port() + path;
    }

    private static HttpResponse<String> get(final String url, final String... headers) throws Exception {
        final var builder = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesAssetsWithImmutableCacheAndEtags() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var resp = get(url(s, "/static/index.a1b2c3d4e5.js"));
        assertEquals(200, resp.statusCode());
        assertEquals("js", resp.body());
        assertEquals(
                "public,max-age=31536000,immutable",
                resp.headers().firstValue("cache-control").orElse(""));
        assertTrue(resp.headers().firstValue("etag").isPresent());

        // conditional GET revalidates with 304
        final var etag = resp.headers().firstValue("etag").orElseThrow();
        final var conditional = get(url(s, "/static/index.a1b2c3d4e5.js"), "If-None-Match", etag);
        assertEquals(304, conditional.statusCode());
    }

    @Test
    void nonFingerprintedAssetsAreNoCache() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var resp = get(url(s, "/index.html"));
        // no index.html in test classpath -> SPA fallback returns the same as 503? actually serves it
        // we assert the cache-control on a served asset without a hash
        assertEquals(200, resp.statusCode());
        assertEquals("no-cache", resp.headers().firstValue("cache-control").orElse(""));
    }

    @Test
    void pathEscapeIsRejected() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var resp = get(url(s, "/../../etc/passwd"));
        assertEquals(403, resp.statusCode());
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        final var s = start(new StaticAssetsExchangeHandler());
        final var request = HttpRequest.newBuilder(URI.create(url(s, "/static/index.a1b2c3d4e5.js")))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        final var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("", resp.body());
    }
}
