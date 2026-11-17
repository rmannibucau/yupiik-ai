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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.openai.model.ChatCompletionRequest$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatCompletionResponse$Choice$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ChatCompletionResponse$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesInput$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesRequest$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$OutputContent$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesResponse$OutputItem$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.ResponsesTool$FusionJsonCodec;
import io.yupiik.llm.client.openai.model.WireMessage$FusionJsonCodec;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UnixProviderHttpClientTest {
    @Test
    void typedConfigurationUsesDottedKeyAndDefaultsToDisabled() {
        final var disabled =
                new NativeProviderConfiguration$FusionConfigurationFactory(Configuration.of(Map.of())).get();
        assertNull(disabled.socket());
        assertEquals(ApiFlavor.COMPLETIONS, disabled.api());
        try (final var delegate = new NoNetworkClient()) {
            assertSame(delegate, UnixProviderHttpClient.fromConfiguration(delegate, disabled));
            final var configured = new NativeProviderConfiguration$FusionConfigurationFactory(
                            Configuration.of(Map.of("yca.native.provider.socket", "relative")))
                    .get();
            assertEquals("relative", configured.socket());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> UnixProviderHttpClient.fromConfiguration(delegate, configured));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ApiFlavor.class,
            names = {"COMPLETIONS", "RESPONSES"})
    void actualChatClientUsesPreparedNativeTransport(final ApiFlavor api) throws Exception {
        final var directory = Files.createTempDirectory("yca-chat-uds-");
        final var path = directory.resolve("p");
        final var served = new CompletableFuture<Void>();
        try (final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var mapper = new JsonMapperImpl(
                        List.of(
                                new ChatCompletionRequest$FusionJsonCodec(),
                                new ChatCompletionResponse$FusionJsonCodec(),
                                new ChatCompletionResponse$Choice$FusionJsonCodec(),
                                new WireMessage$FusionJsonCodec(),
                                new ResponsesRequest$FusionJsonCodec(),
                                new ResponsesInput$FusionJsonCodec(),
                                new ResponsesTool$FusionJsonCodec(),
                                new ResponsesResponse$FusionJsonCodec(),
                                new ResponsesResponse$OutputItem$FusionJsonCodec(),
                                new ResponsesResponse$OutputContent$FusionJsonCodec()),
                        ignored -> Optional.empty());
                final var http = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                        .setDelegate(UnixProviderHttpClient.fromConfiguration(
                                new NoNetworkClient(),
                                new NativeProviderConfiguration$FusionConfigurationFactory(Configuration.of(Map.of(
                                                "yca.native.provider.socket",
                                                path.toString(),
                                                "yca.native.provider.api",
                                                api.name())))
                                        .get())))) {
            listener.bind(UnixDomainSocketAddress.of(path));
            Thread.ofVirtual().start(() -> {
                try (final var channel = listener.accept()) {
                    final var in = UnixProviderProtocol.input(channel);
                    assertEquals(UnixProviderProtocol.MAGIC, in.readInt());
                    assertEquals("attempt-capability", in.readUTF());
                    assertEquals(api.name(), in.readUTF());
                    final var request = new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
                    assertTrue(request.contains("hello over unix"));
                    final var out = UnixProviderProtocol.output(channel);
                    out.writeInt(200);
                    out.writeUTF("application/json");
                    final var body = (api == ApiFlavor.RESPONSES
                                    ? "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"native answer\"}]}]}"
                                    : "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"native answer\"},\"finish_reason\":\"stop\"}]}")
                            .getBytes(StandardCharsets.UTF_8);
                    out.writeInt(body.length);
                    out.write(body);
                    out.writeInt(0);
                    served.complete(null);
                } catch (final Throwable e) {
                    served.completeExceptionally(e);
                }
            });
            final var config = TestConfigs.codingAgent(Map.of(
                    "yca.storageHome", directory.toString(),
                    "yca.provider.baseUrl", UnixProviderProtocol.endpoint(api).toString(),
                    "yca.provider.apiKey", "attempt-capability",
                    "yca.provider.model", "test-model",
                    "yca.provider.api", api.name(),
                    "yca.provider.contextWindow", "128000",
                    "yca.llm.retriesMaxAttempts", "1"));
            try (final var chat =
                    new ChatClient(config, mapper, new ProviderManager(config, mapper), Clock.systemUTC(), http)) {
                final var result = chat.chat(new ChatCompletionRequest(
                        "test-model",
                        List.of(Message.user("hello over unix")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
                assertEquals("native answer", result.message().content());
            }
            served.get(1, TimeUnit.SECONDS);
        } finally {
            try (final var paths = Files.walk(directory)) {
                for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        }
    }

    @Test
    void streamsFramesAndRejectsTruncationOversizedFramesRoutesAndMissingCapability() throws Exception {
        // Includes listener/client creation and cleanup; no provider, TLS server, sleeps or warmup.
        final var directory = Files.createTempDirectory("yca-uds-");
        final var path = directory.resolve("p");
        try (final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var client = new UnixProviderHttpClient(new NoNetworkClient(), path, ApiFlavor.COMPLETIONS)) {
            listener.bind(UnixDomainSocketAddress.of(path));
            for (final int mode : new int[] {0, 1, 2}) {
                final var server = new CompletableFuture<Void>();
                Thread.ofVirtual().start(() -> {
                    try (final var connection = listener.accept()) {
                        final var in = UnixProviderProtocol.input(connection);
                        assertEquals(UnixProviderProtocol.MAGIC, in.readInt());
                        assertEquals("attempt", in.readUTF());
                        assertEquals("COMPLETIONS", in.readUTF());
                        assertEquals("{}", new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8));
                        final var out = UnixProviderProtocol.output(connection);
                        out.writeInt(200);
                        out.writeUTF("text/event-stream");
                        if (mode == 2) {
                            out.writeInt(UnixProviderProtocol.FRAME_SIZE + 1);
                        } else {
                            out.writeInt(3);
                            out.writeBytes("one");
                            if (mode == 0) {
                                out.writeInt(3);
                                out.writeBytes("two");
                                out.writeInt(0);
                            }
                        }
                        server.complete(null);
                    } catch (final Throwable e) {
                        server.completeExceptionally(e);
                    }
                });
                final var request = request().build();
                if (mode == 0) {
                    final var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (final var stream = response.body()) {
                        assertEquals("onetwo", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    assertEquals(200, response.statusCode());
                } else {
                    assertThrows(IOException.class, () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
                }
                server.get(1, TimeUnit.SECONDS);
            }
            for (final var uri : new String[] {
                "http://yca-native.invalid/v1/models",
                "http://yca-native.invalid/v1/responses",
                "http://yca-native.invalid:80/v1/chat/completions",
                "http://yca-native.invalid/v1/chat/completions?target=https://other.invalid"
            }) {
                assertThrows(
                        IOException.class,
                        () -> client.send(
                                HttpRequest.newBuilder(URI.create(uri))
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString()));
            }
            assertThrows(
                    IOException.class,
                    () -> client.send(
                            HttpRequest.newBuilder(UnixProviderProtocol.ENDPOINT)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()));
            assertSame(
                    client,
                    UnixProviderHttpClient.fromConfiguration(
                            client, new NativeProviderConfiguration(null, ApiFlavor.COMPLETIONS)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new UnixProviderHttpClient(client, Path.of("relative"), ApiFlavor.COMPLETIONS));
        } finally {
            Files.deleteIfExists(path);
            Files.delete(directory);
        }
    }

    @Test
    void cancellationAndDeadlineCloseSocketWithoutWaitingForResponse() throws Exception {
        final var directory = Files.createTempDirectory("yca-uds-");
        final var path = directory.resolve("p");
        try (final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var client = new UnixProviderHttpClient(new NoNetworkClient(), path, ApiFlavor.COMPLETIONS)) {
            listener.bind(UnixDomainSocketAddress.of(path));
            for (final boolean cancel : new boolean[] {true, false}) {
                final var accepted = new CompletableFuture<SocketChannel>();
                final var disconnected = new CompletableFuture<Void>();
                Thread.ofVirtual().start(() -> {
                    try (final var connection = listener.accept()) {
                        final var in = UnixProviderProtocol.input(connection);
                        in.readInt();
                        in.readUTF();
                        in.readUTF();
                        in.readNBytes(in.readInt());
                        accepted.complete(connection);
                        assertEquals(-1, in.read());
                        disconnected.complete(null);
                    } catch (final Throwable error) {
                        disconnected.completeExceptionally(error);
                    }
                });
                final var future = client.sendAsync(
                        request().timeout(Duration.ofMillis(cancel ? 300 : 30)).build(),
                        HttpResponse.BodyHandlers.ofString());
                accepted.get(1, TimeUnit.SECONDS);
                if (cancel) {
                    assertTrue(future.cancel(true));
                } else {
                    assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
                }
                disconnected.get(1, TimeUnit.SECONDS);
            }
        } finally {
            Files.deleteIfExists(path);
            Files.delete(directory);
        }
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(UnixProviderProtocol.ENDPOINT)
                .header("Authorization", "Bearer attempt")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
    }

    /** Any fallback to TCP is a test failure; native requests never need JDK HTTP/TLS initialization. */
    private static final class NoNetworkClient extends HttpClient {
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        public SSLContext sslContext() {
            throw new AssertionError("No TLS in native agent");
        }

        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        public Version version() {
            return Version.HTTP_1_1;
        }

        public Optional<Executor> executor() {
            return Optional.empty();
        }

        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("Unexpected network dispatch");
        }

        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("Unexpected network dispatch");
        }

        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final HttpResponse.PushPromiseHandler<T> push) {
            throw new AssertionError("Unexpected network dispatch");
        }
    }
}
