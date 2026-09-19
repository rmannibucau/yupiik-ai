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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cuts a chunked HTTP connection mid-chunk after a first complete SSE chunk: the client must
 * surface a {@link MidStreamFailureException} carrying the partial stream state instead of an
 * opaque transport error, so the agent loop can replay the request safely.
 */
class ChatClientMidStreamTest {

    @TempDir
    Path storageHome;

    @Test
    void midStreamDisconnectSurfacesAsMidStreamFailure() throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"he\"},\"finish_reason\":null}]}",
                "",
                "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"llo\"},\"finish_reason\":null}]}",
                "",
                "data: [DONE]",
                "");
        final var sseBytes = sse.getBytes(StandardCharsets.UTF_8);
        final var firstChunkDelivered = new CountDownLatch(2);
        final var serverDone = new CountDownLatch(1);
        try (final var server = new ServerSocket(0, 1, InetAddress.getByName("localhost"))) {
            final var serverThread = new Thread(() -> serveOne(server, sseBytes, firstChunkDelivered, serverDone));
            serverThread.start();

            final var port = server.getLocalPort();
            final var cfg = configuration(port);
            final var client = new ChatClient(cfg, mapper(), new ProviderManager(cfg, mapper()), Clock.systemUTC());
            final var deltas = new CopyOnWriteArrayList<LlmStreamEvent>();
            final Consumer<LlmStreamEvent> onDelta = delta -> {
                if (delta instanceof TextDelta) {
                    firstChunkDelivered.countDown();
                }
                deltas.add(delta);
            };
            try {
                final var failure =
                        assertThrows(IOException.class, () -> client.stream(request(), onDelta, () -> false));
                assertInstanceOf(MidStreamFailureException.class, failure);
                assertTrue(
                        ((MidStreamFailureException) failure).partialText().contains("hello"),
                        "the partial text seen before the cut must be preserved");
            } finally {
                client.close();
                assertTrue(serverDone.await(5, TimeUnit.SECONDS), "server thread must finish");
            }
            assertTrue(
                    deltas.stream().anyMatch(TextDelta.class::isInstance),
                    "deltas delivered before the cut must still reach the consumer");
        }
    }

    private void serveOne(
            final ServerSocket server,
            final byte[] sseBytes,
            final CountDownLatch firstChunkDelivered,
            final CountDownLatch serverDone) {
        try (final Socket socket = server.accept()) {
            final var in = socket.getInputStream();
            final var head = new StringBuilder();
            for (var c = in.read(); c != -1; c = in.read()) {
                head.append((char) c);
                final var asString = head.toString();
                if (asString.endsWith("\r\n\r\n")) {
                    break;
                }
            }
            final var out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: text/event-stream\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write((Integer.toHexString(sseBytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(sseBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // only cut once the client actually consumed the first chunk, otherwise the failure
            // would happen before any fragment and would not be a mid-stream failure
            firstChunkDelivered.await(5, TimeUnit.SECONDS);
            out.write("5\r\nhel".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (final IOException | InterruptedException e) {
            // the client may already be gone, nothing to do
        } finally {
            serverDone.countDown();
        }
    }

    private CodingAgentConfiguration configuration(final int port) {
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:" + port + "/v1"),
                Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                Map.entry("yca.provider.providers.0.model", "test-model"),
                Map.entry("yca.provider.providers.0.api", "completions"),
                Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "5"),
                Map.entry("yca.llm.retriesMaxAttempts", "1"),
                Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                Map.entry("yca.llm.maxConcurrent", "1"),
                Map.entry("yca.llm.retryOnContextOverflow", "true"),
                Map.entry("yca.llm.maxIterations", "250")));
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model", List.of(Message.user("hi")), null, null, null, null, null, null, null);
    }

    private JsonMapper mapper() {
        return new JsonMapperImpl(
                List.of(
                        new Message$FusionJsonCodec(),
                        new ToolCall$FusionJsonCodec(),
                        new ToolCall$FunctionCall$FusionJsonCodec(),
                        new Usage$FusionJsonCodec(),
                        new ChatCompletionRequest$FusionJsonCodec(),
                        new ModelsResponse$FusionJsonCodec(),
                        new ModelsResponse$Model$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatCompletionRequest$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireMessage$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireMessage$WireToolCall$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireMessage$WireFunction$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.WireTool$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.OpenAIUsage$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatCompletionResponse$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatCompletionResponse$Choice$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatChunk$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatChunk$ChunkChoice$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ChatChunk$ChunkDelta$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ResponsesRequest$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ResponsesInput$FusionJsonCodec(),
                        new io.yupiik.llm.client.openai.model.ResponsesTool$FusionJsonCodec()),
                key -> Optional.empty());
    }
}
