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
package io.yupiik.ia.agent.core.wiring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.mcp.SessionMcpHttpClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** This class mutates the JVM-wide {@code java.util.logging} hierarchy, see {@link #setUp()}. */
@ResourceLock("java.util.logging")
class HttpClientProducerTest {

    private HttpServer server;
    private ExecutorService executor;
    private ByteArrayOutputStream logs;
    private Logger logger;
    private Handler handler;
    private boolean disabled;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.setExecutor(executor);
        server.start();
        logs = new ByteArrayOutputStream();
        logger = Logger.getLogger("yca.http");
        // the handler is asserted on, so the logger must not leak records to the console: tests
        // run in parallel and the JVM-wide hierarchy is not exclusive unless we ask for it
        handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                try {
                    logs.write(record.getMessage().getBytes(StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        disabled = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(disabled);
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void sessionHeadersOverrideGlobalsWithoutChangingApplicationPrecedence() throws Exception {
        server.createContext("/headers", exchange -> {
            try (exchange) {
                final var bytes = (exchange.getRequestHeaders().getFirst("Authorization") + "/"
                                + exchange.getRequestHeaders().getFirst("X-Default") + "/"
                                + exchange.getRequestHeaders().getFirst("User-Agent"))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.headers", "authorization=global\nx-default=default\nuser-agent=configured-agent"));
        final var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/headers"))
                .header("aUtHoRiZaTiOn", "ide-secret")
                .header("USER-AGENT", "ide-agent")
                .build();
        try (final var shared =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            try (final var view = shared.openSession(Set.of("aUtHoRiZaTiOn", "USER-AGENT"))) {
                Assertions.assertEquals(
                        "ide-secret/default/ide-agent", view.send(request).body());
                Assertions.assertEquals(
                        "global/default/configured-agent", shared.send(request).body());
            }
            Assertions.assertEquals(
                    "global/default/configured-agent", shared.send(request).body());
        }
    }

    @Test
    void sessionBlocksRedirectsBeforeForwardingSyncAndAsync() throws Exception {
        final var reached = new AtomicInteger();
        final var destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", exchange -> {
            try (exchange) {
                reached.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        destination.start();
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders()
                        .set(
                                "Location",
                                "http://127.0.0.1:" + destination.getAddress().getPort() + "/");
                exchange.sendResponseHeaders(307, -1);
            }
        });
        try (final var shared = new HttpClientProducer()
                        .httpClient(TestConfigs.codingAgent(Map.of()), new NativeProviderConfiguration(null, null));
                final var view = shared.openSession(Set.of("X-Arbitrary"))) {
            final var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"))
                    .header("X-Arbitrary", "secret")
                    .build();
            Assertions.assertEquals(HttpClient.Redirect.NORMAL, shared.followRedirects());
            Assertions.assertEquals(HttpClient.Redirect.NEVER, view.followRedirects());
            Assertions.assertEquals(307, view.send(request).statusCode());
            Assertions.assertEquals(
                    307,
                    view.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                            .join()
                            .statusCode());
            Assertions.assertEquals(
                    307,
                    view.sendAsync(
                                    request,
                                    HttpResponse.BodyHandlers.discarding(),
                                    (initial, push, acceptor) -> Assertions.fail("Unexpected push"))
                            .join()
                            .statusCode());
            Assertions.assertEquals(0, reached.get());
            Assertions.assertEquals(200, shared.send(request).statusCode());
            Assertions.assertEquals(1, reached.get());
        } finally {
            destination.stop(0);
        }
    }

    @Test
    void sessionDebugNeverLogsHeaderUrlOrEchoedSecrets() {
        server.createContext("/secret-path", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "https://example.test/?token=response-secret");
                exchange.getResponseHeaders().set("X-Arbitrary", "response-header-secret");
                final var body = "echoed-secret".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        final var config = TestConfigs.codingAgent(
                Map.of("yca.httpClient.debug", "true", "yca.httpClient.headers", "X-Global=global-secret"));
        try (final var shared =
                        new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
                final var view = shared.openSession(Set.of("X-Arbitrary", "Authorization", "Cookie"))) {
            view.send(HttpRequest.newBuilder(URI.create("http://user:url-secret@127.0.0.1:"
                            + server.getAddress().getPort() + "/secret-path?token=query-secret"))
                    .header("x-arbitrary", "arbitrary-secret")
                    .header("Authorization", "Bearer auth-secret")
                    .header("Cookie", "cookie-secret")
                    .POST(HttpRequest.BodyPublishers.ofString("payload-secret"))
                    .build());
        }
        final var trace = logs.toString(StandardCharsets.UTF_8);
        assertTrue(trace.contains("Session MCP HTTP POST"), trace);
        assertTrue(trace.contains("HTTP status 200"), trace);
        assertTrue(trace.contains("******"), trace);
        assertFalse(trace.contains("secret"), trace);
    }

    @Test
    void sessionInheritsCookiesAndReusesOneApplicationOwnedTransport() {
        server.createContext("/set-cookie", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Set-Cookie", "route=shared; Path=/");
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.createContext("/cookie", exchange -> {
            try (exchange) {
                final var cookie = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
                final var bytes = cookie.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        final var config = TestConfigs.codingAgent(Map.of("yca.httpClient.enableCookies", "true"));
        final var shared = new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
        final HttpClient sessionTransport;
        try (shared) {
            final var root = "http://127.0.0.1:" + server.getAddress().getPort();
            shared.send(HttpRequest.newBuilder(URI.create(root + "/set-cookie")).build());
            final var first = shared.openSession(Set.of());
            sessionTransport = first.delegate();
            try (first;
                    final var second = shared.openSession(Set.of())) {
                Assertions.assertSame(first.delegate(), second.delegate());
                Assertions.assertSame(
                        shared.cookieHandler().orElseThrow(),
                        first.cookieHandler().orElseThrow());
                assertTrue(first.send(HttpRequest.newBuilder(URI.create(root + "/cookie"))
                                .build())
                        .body()
                        .contains("route=shared"));
            }
            try (final var third = shared.openSession(Set.of())) {
                Assertions.assertSame(first.delegate(), third.delegate());
                Assertions.assertEquals(
                        200,
                        third.send(HttpRequest.newBuilder(URI.create(root + "/"))
                                        .build())
                                .statusCode());
            }
            Assertions.assertEquals(
                    200,
                    shared.send(HttpRequest.newBuilder(URI.create(root + "/")).build())
                            .statusCode());
        }
        assertTrue(sessionTransport.isTerminated(), "application close must close the lazy session transport");
        assertThrows(IllegalStateException.class, () -> shared.openSession(Set.of()));
    }

    @Test
    void sessionCanReuseAnAlreadyNoRedirectTransportWithoutOwningIt() {
        try (final var transport = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            final var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                    .build();
            try (final var view = new SessionMcpHttpClient(transport, List.of(), false, Set.of())) {
                Assertions.assertSame(transport, view.delegate());
                Assertions.assertEquals(200, view.send(request).statusCode());
            }
            assertFalse(transport.isTerminated());
            try (final var view = new SessionMcpHttpClient(transport, List.of(), false, Set.of())) {
                Assertions.assertEquals(200, view.send(request).statusCode());
            }
        }
    }

    @Test
    void sessionRejectsRedirectingTransports() {
        try (final var transport = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SessionMcpHttpClient(transport, List.of(), false, Set.of()));
        }
    }

    @Test
    void applicationCloseAbortsUnconsumedSessionBodies() throws Exception {
        final var bodyOpen = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        server.createContext("/open-stream", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": open\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                bodyOpen.countDown();
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        final var shared = new HttpClientProducer()
                .httpClient(TestConfigs.codingAgent(Map.of()), new NativeProviderConfiguration(null, null));
        final var workers = Executors.newVirtualThreadPerTaskExecutor();
        try (final var view = shared.openSession(Set.of())) {
            final var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/open-stream"))
                    .build();
            try (final var body = view.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    .body()) {
                assertTrue(body != null);
                assertTrue(bodyOpen.await(5, TimeUnit.SECONDS));
                workers.submit(shared::close).get(5, TimeUnit.SECONDS);
                assertTrue(view.delegate().isTerminated());
                assertThrows(IllegalStateException.class, () -> shared.openSession(Set.of()));
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
            shared.close();
        }
    }

    @Test
    void sessionCustomCertificateIsTrusted() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.certificates.importGlobalCertificate", "false",
                "yca.httpClient.certificates.custom.length", "1",
                "yca.httpClient.certificates.custom.0.pem", pem()));
        final var https = httpsServer();
        try (final var shared =
                        new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
                final var view = shared.openSession(Set.of())) {
            final var response = view.send(HttpRequest.newBuilder(
                            URI.create("https://localhost:" + https.getAddress().getPort() + "/"))
                    .build());
            Assertions.assertEquals(200, response.statusCode());
        } finally {
            https.stop(0);
        }
    }

    @Test
    void sessionPreservesProxyTlsAndTimeoutSettings() {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host",
                "proxy.example.test",
                "yca.httpClient.proxy.port",
                "3128",
                "yca.httpClient.proxy.user",
                "user",
                "yca.httpClient.proxy.password",
                "secret",
                "yca.httpClient.connectTimeoutSeconds",
                "17"));
        try (final var shared =
                        new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
                final var view = shared.openSession(Set.of())) {
            Assertions.assertSame(shared.proxy().orElseThrow(), view.proxy().orElseThrow());
            Assertions.assertSame(
                    shared.authenticator().orElseThrow(), view.authenticator().orElseThrow());
            Assertions.assertSame(shared.sslContext(), view.sslContext());
            Assertions.assertEquals(shared.connectTimeout(), view.connectTimeout());
            Assertions.assertEquals(shared.version(), view.version());
            Assertions.assertArrayEquals(
                    shared.sslParameters().getProtocols(), view.sslParameters().getProtocols());
            Assertions.assertArrayEquals(
                    shared.sslParameters().getCipherSuites(),
                    view.sslParameters().getCipherSuites());
        }
    }

    @Test
    void sessionDebugDisabledProducesNoLogs() {
        try (final var shared = new HttpClientProducer()
                        .httpClient(TestConfigs.codingAgent(Map.of()), new NativeProviderConfiguration(null, null));
                final var view = shared.openSession(Set.of())) {
            view.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                    .build());
        }
        Assertions.assertEquals(0, logs.size());
    }

    @Test
    void nativeSocketComesFromExplicitConfiguration() {
        final var config = TestConfigs.codingAgent(Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new HttpClientProducer().httpClient(config, new NativeProviderConfiguration("relative", null)));
    }

    @Test
    void debugEnablesCurlLoggerWithPayload() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of("yca.httpClient.debug", "true"));
        final var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer secret-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}"))
                    .build());
        }
        final var trace = logs.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(trace.contains("curl -i -X POST"), () -> "missing curl post in: " + trace);
        Assertions.assertTrue(trace.contains(url), () -> "missing url in: " + trace);
        Assertions.assertTrue(trace.contains("--data-raw '{\"a\":1}'"), () -> "missing payload in: " + trace);
        assertFalse(trace.contains("secret-token"), () -> "authorization must be redacted in: " + trace);
        assertTrue(trace.contains("HTTP/1.1 200"), () -> "missing response line in: " + trace);
    }

    @Test
    void debugDisabledSkipsCurlLogger() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of("yca.httpClient.debug", "false"));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
            client.send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}"))
                    .build());
        }
        Assertions.assertEquals(0, logs.size(), () -> "no curl trace expected when debug disabled, got: " + logs);
    }

    @Test
    void proxyHostConfiguresSelector() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host", "proxy.example.com",
                "yca.httpClient.proxy.port", "3128"));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var selector = client.proxy().orElseThrow(() -> new AssertionError("proxy selector expected"));
            final var proxies = selector.select(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertFalse(proxies.isEmpty(), "select() must return the configured proxy");
            final var address = proxies.getFirst().address();
            Assertions.assertEquals("proxy.example.com", ((InetSocketAddress) address).getHostString());
            Assertions.assertEquals(3128, ((InetSocketAddress) address).getPort());
        }
    }

    @Test
    void noProxyKeepsJdkDefaultSelection() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of());
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            Assertions.assertTrue(client.proxy().isEmpty(), "no explicit proxy selector expected without proxy config");
        }
    }

    @Test
    void proxyCredentialsWireProxyAuthenticator() throws Exception {
        // a local fake HTTP proxy: challenges with 407 + Basic realm, accepts once the
        // client retries with the expected Proxy-Authorization header (JDK AuthenticationFilter)
        final var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var challenged = new AtomicInteger();
        final var expected =
                "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
        proxy.createContext("/", exchange -> {
            final var auth = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
            if (expected.equals(auth)) {
                exchange.sendResponseHeaders(200, 2);
                try (final var out = exchange.getResponseBody()) {
                    out.write("ok".getBytes(StandardCharsets.UTF_8));
                }
            } else {
                challenged.incrementAndGet();
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"test\"");
                exchange.sendResponseHeaders(407, -1);
                exchange.close();
            }
        });
        proxy.start();
        try {
            final var config = TestConfigs.codingAgent(Map.of(
                    "yca.httpClient.proxy.host", "127.0.0.1",
                    "yca.httpClient.proxy.port",
                            String.valueOf(proxy.getAddress().getPort()),
                    "yca.httpClient.proxy.user", "alice",
                    "yca.httpClient.proxy.password", "s3cret"));
            try (final var client =
                    new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
                final var resp = client.send(HttpRequest.newBuilder(URI.create("http://test.invalid/"))
                        .build());
                Assertions.assertEquals(200, resp.statusCode(), "the proxy must accept the authenticated retry");
                Assertions.assertTrue(challenged.get() > 0, "the proxy must have challenged the client first");
            }
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void proxyWithoutUserLeavesAuthenticatorUnset() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host", "proxy.example.com",
                "yca.httpClient.proxy.port", "3128"));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            Assertions.assertTrue(
                    client.authenticator().isEmpty(), "no authenticator expected without proxy credentials");
        }
    }

    @Test
    void noProxyHostsBypassConfiguredProxy() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host",
                "proxy.example.com",
                "yca.httpClient.proxy.port",
                "3128",
                "yca.httpClient.proxy.noProxyHosts",
                "127.0.0.1,.internal.corp,*.wild.corp,foo.*.corp"));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var selector = client.proxy().orElseThrow(() -> new AssertionError("proxy selector expected"));
            assertTrue(isNoProxy(selector, "http://127.0.0.1:8080/path"), "exact host must bypass");
            assertTrue(
                    isNoProxy(selector, "http://api.internal.corp/"),
                    "leading-dot suffix must match the domain itself");
            assertTrue(isNoProxy(selector, "http://x.internal.corp/"), "leading-dot suffix must match sub-domains");
            assertTrue(
                    isNoProxy(selector, "https://a.b.wild.corp:8443/"), "wildcard must match sub-domains over https");
            assertTrue(isNoProxy(selector, "http://foo.bar.corp/"), "multi-level wildcard must match");
            assertFalse(isNoProxy(selector, "http://example.com/"), "unlisted host must go through the proxy");
            assertFalse(isNoProxy(selector, "http://wild.corp/"), "*.wild.corp must not match the apex");
            assertFalse(isNoProxy(selector, "http://corp/"), "unrelated host must go through the proxy");
        }
    }

    @Test
    void noProxyHelperPreservesLiteralGlobDotAndCaseSemantics() {
        assertTrue(HttpClientProducer.matchesNoProxy("API.EXAMPLE", List.of(" api.example ")));
        assertFalse(HttpClientProducer.matchesNoProxy("x.api.example", List.of("api.example")));
        assertTrue(HttpClientProducer.matchesNoProxy("example", List.of(".EXAMPLE")));
        assertTrue(HttpClientProducer.matchesNoProxy("a.b.example", List.of(".example")));
        assertFalse(HttpClientProducer.matchesNoProxy("notexample", List.of(".example")));
        assertFalse(HttpClientProducer.matchesNoProxy("example", List.of("*.example")));
        assertTrue(HttpClientProducer.matchesNoProxy("a.b.example", List.of("*.example")));
        assertTrue(HttpClientProducer.matchesNoProxy("myCORPservice", List.of("*corp*")));
        assertTrue(HttpClientProducer.matchesNoProxy("a[1].example", List.of("a[1].*")));
        assertFalse(HttpClientProducer.matchesNoProxy("a1.example", List.of("a[1].*")));
        assertFalse(HttpClientProducer.matchesNoProxy("example", Arrays.asList(null, "", "  ")));
        assertTrue(HttpClientProducer.matchesNoProxy("anything", List.of("*")));
    }

    @Test
    void noProxySelectorRepeatedSelectionsKeepMatchingSemantics() throws Exception {
        final var entries = List.of("API.EXAMPLE", ".internal.corp", "*.wild.corp", "foo.*.corp", "*corp*");
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host", "proxy.example.com",
                "yca.httpClient.proxy.port", "3128",
                "yca.httpClient.proxy.noProxyHosts", String.join(",", entries)));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var selector = client.proxy().orElseThrow();
            for (int i = 0; i < 3; i++) {
                for (final var host : List.of(
                        "API.EXAMPLE",
                        "x.api.example",
                        "internal.corp",
                        "a.internal.corp",
                        "a.b.wild.corp",
                        "foo.bar.corp",
                        "mycorpservice",
                        "unlisted.example")) {
                    Assertions.assertEquals(
                            HttpClientProducer.matchesNoProxy(host, entries),
                            isNoProxy(selector, "https://" + host + "/"),
                            host);
                }
            }
            assertTrue(isNoProxy(selector, URI.create("file:///tmp/no-host")));
        }
    }

    @Test
    void noProxyHostsCaseInsensitive() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host", "proxy.example.com",
                "yca.httpClient.proxy.port", "3128",
                "yca.httpClient.proxy.noProxyHosts", "API.INTERNAL.CORP"));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var selector = client.proxy().orElseThrow(() -> new AssertionError("proxy selector expected"));
            assertTrue(isNoProxy(selector, "http://api.internal.corp/"), "host matching must be case-insensitive");
        }
    }

    @Test
    void noProxyHostsWildcardOnlyBypassesEverything() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host", "proxy.example.com",
                "yca.httpClient.proxy.port", "3128",
                "yca.httpClient.proxy.noProxyHosts", "*"));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var selector = client.proxy().orElseThrow(() -> new AssertionError("proxy selector expected"));
            assertTrue(isNoProxy(selector, "http://anything.example.com/"), "a lone * must bypass every host");
        }
    }

    @Test
    void noProxyHostsBlankHostUriIsNoProxy() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.proxy.host", "proxy.example.com",
                "yca.httpClient.proxy.port", "3128"));
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var selector = client.proxy().orElseThrow(() -> new AssertionError("proxy selector expected"));
            assertTrue(isNoProxy(selector, URI.create("file:///tmp/x")), "a URI without host cannot be proxied");
        }
    }

    @Test
    void noProxyHostsEndToEndBypassNeverReachesProxy() throws Exception {
        // a local fake proxy that counts every request it receives
        final var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var proxied = new AtomicInteger();
        proxy.createContext("/", exchange -> {
            proxied.incrementAndGet();
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        proxy.start();
        try {
            final var config = TestConfigs.codingAgent(Map.of(
                    "yca.httpClient.proxy.host", "127.0.0.1",
                    "yca.httpClient.proxy.port",
                            String.valueOf(proxy.getAddress().getPort()),
                    "yca.httpClient.proxy.noProxyHosts", "127.0.0.1"));
            final var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/direct";
            try (final var client =
                    new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
                final var resp =
                        client.send(HttpRequest.newBuilder(URI.create(url)).build());
                Assertions.assertEquals(200, resp.statusCode(), "the request must reach the real server directly");
            }
            Assertions.assertEquals(0, proxied.get(), "the proxy must never see a bypassed request");
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void customCertificateIsTrusted() throws Exception {
        final var pem = pem();
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.certificates.importGlobalCertificate", "false",
                "yca.httpClient.certificates.custom.length", "1",
                "yca.httpClient.certificates.custom.0.pem", pem));
        final var https = httpsServer();
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var resp = client.send(HttpRequest.newBuilder(
                            URI.create("https://localhost:" + https.getAddress().getPort() + "/"))
                    .build());
            Assertions.assertEquals(200, resp.statusCode(), "the custom CA must be trusted");
        } finally {
            https.stop(0);
        }
    }

    @Test
    void customCertificateMalformedFailsOnClientCreation() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.certificates.importGlobalCertificate", "false",
                "yca.httpClient.certificates.custom.length", "1",
                "yca.httpClient.certificates.custom.0.pem", "not-a-pem"));
        assertThrows(
                IllegalStateException.class,
                () -> new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null)),
                "a malformed PEM must fail the client creation loudly");
    }

    @Test
    void importGlobalCertificateWithCustomPemIsTrusted() throws Exception {
        final var pem = pem();
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.certificates.importGlobalCertificate", "true",
                "yca.httpClient.certificates.custom.length", "1",
                "yca.httpClient.certificates.custom.0.pem", pem));
        final var https = httpsServer();
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var resp = client.send(HttpRequest.newBuilder(
                            URI.create("https://localhost:" + https.getAddress().getPort() + "/"))
                    .build());
            Assertions.assertEquals(200, resp.statusCode(), "global store + custom cert must be trusted");
        } finally {
            https.stop(0);
        }
    }

    @Test
    void disableTlsCheckOverridesCertificateConfig() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.httpClient.disableTlsCheck", "true",
                "yca.httpClient.certificates.importGlobalCertificate", "false",
                "yca.httpClient.certificates.custom.length", "0"));
        final var https = httpsServer();
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var resp = client.send(HttpRequest.newBuilder(
                            URI.create("https://localhost:" + https.getAddress().getPort() + "/"))
                    .build());
            Assertions.assertEquals(200, resp.statusCode(), "disableTlsCheck must win over the certificates group");
        } finally {
            https.stop(0);
        }
    }

    @Test
    void withoutCertificatesSelfSignedIsRejected() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of());
        final var https = httpsServer();
        try (final var client =
                new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null))) {
            final var resp = client.send(HttpRequest.newBuilder(
                            URI.create("https://localhost:" + https.getAddress().getPort() + "/"))
                    .build());
            Assertions.fail(
                    "the self-signed server must be rejected without the custom certificate, got " + resp.statusCode());
        } catch (final IllegalStateException expected) {
            // ExtendedHttpClient.send wraps the underlying SSLHandshakeException in an
            // IllegalStateException
            if (!(expected.getCause() instanceof SSLHandshakeException)) {
                throw expected;
            }
        } finally {
            https.stop(0);
        }
    }

    private static boolean isNoProxy(final ProxySelector selector, final String uri) {
        return isNoProxy(selector, URI.create(uri));
    }

    private static boolean isNoProxy(final ProxySelector selector, final URI uri) {
        return selector.select(uri).stream().allMatch(p -> p.type() == Proxy.Type.DIRECT);
    }

    private static String pem() {
        try {
            return Files.readString(Path.of("src/test/resources/io/yupiik/ia/agent/core/wiring/localhost.pem"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An HTTPS server whose private key/certificate are the committed localhost.p12 fixture. */
    private static HttpsServer httpsServer() throws Exception {
        final var keyStore = KeyStore.getInstance("PKCS12");
        try (final var in =
                Files.newInputStream(Path.of("src/test/resources/io/yupiik/ia/agent/core/wiring/localhost.p12"))) {
            keyStore.load(in, "changeit".toCharArray());
        }
        final var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());
        final var ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, new SecureRandom());

        final var https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(ssl));
        https.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, -1);
            }
        });
        https.start();
        return https;
    }
}
