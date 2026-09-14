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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                try {
                    logs.write(record.getMessage().getBytes(StandardCharsets.UTF_8));
                } catch (final java.io.IOException e) {
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
    void debugEnablesCurlLoggerWithPayload() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of("yca.httpClient.debug", "true"));
        final var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
        try (final var client = new HttpClientProducer().httpClient(config)) {
            client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer secret-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}"))
                    .build());
        }
        final var trace = new String(logs.toByteArray(), StandardCharsets.UTF_8);
        Assertions.assertTrue(trace.contains("curl -i -X POST"), () -> "missing curl post in: " + trace);
        Assertions.assertTrue(trace.contains(url), () -> "missing url in: " + trace);
        Assertions.assertTrue(trace.contains("--data-raw '{\"a\":1}'"), () -> "missing payload in: " + trace);
        assertFalse(trace.contains("secret-token"), () -> "authorization must be redacted in: " + trace);
        assertTrue(trace.contains("HTTP/1.1 200"), () -> "missing response line in: " + trace);
    }

    @Test
    void debugDisabledSkipsCurlLogger() throws Exception {
        final var config = TestConfigs.codingAgent(Map.of("yca.httpClient.debug", "false"));
        try (final var client = new HttpClientProducer().httpClient(config)) {
            final var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
            client.send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}"))
                    .build());
        }
        Assertions.assertTrue(logs.size() == 0, () -> "no curl trace expected when debug disabled, got: " + logs);
    }
}
