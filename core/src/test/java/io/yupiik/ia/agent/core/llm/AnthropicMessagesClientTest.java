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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicMessagesClientTest {
    private HttpServer server;
    private final AtomicReference<String> reqHeaders = new AtomicReference<>();
    private final AtomicReference<String> reqBody = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> streamTexts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ProviderConfiguration provider(final String baseUrl) {
        return new ProviderConfiguration(
                "default",
                baseUrl,
                "sk-test",
                "deepseek-v4-flash",
                ApiFlavor.ANTHROPIC,
                null,
                1000000,
                -1,
                List.of(),
                30,
                "",
                java.util.Map.of(),
                null);
    }

    private AnthropicMessagesClient client() {
        return new AnthropicMessagesClient(mapper());
    }

    private JsonMapper mapper() {
        return new JsonMapperImpl(
                List.of(
                        new Message$FusionJsonCodec(),
                        new ToolCall$FusionJsonCodec(),
                        new ToolCall$FunctionCall$FusionJsonCodec(),
                        new Usage$FusionJsonCodec(),
                        new ChatCompletionRequest$FusionJsonCodec(),
                        new AnthropicMessagesRequest$FusionJsonCodec(),
                        new AnthropicMessage$FusionJsonCodec(),
                        new AnthropicTool$FusionJsonCodec(),
                        new AnthropicMessagesResponse$FusionJsonCodec(),
                        new AnthropicMessagesResponse$ContentBlock$FusionJsonCodec(),
                        new AnthropicMessagesResponse$AnthropicUsage$FusionJsonCodec(),
                        new AnthropicStreamEvent$FusionJsonCodec(),
                        new AnthropicStreamEvent$Delta$FusionJsonCodec(),
                        new AnthropicModels$FusionJsonCodec(),
                        new AnthropicModels$Model$FusionJsonCodec(),
                        new ModelsResponse$FusionJsonCodec(),
                        new ModelsResponse$Model$FusionJsonCodec()),
                key -> Optional.empty());
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "deepseek-v4-flash",
                List.of(
                        new Message("system", "be concise", null, null, null, null),
                        new Message("user", "hi", null, null, null, null)),
                null,
                false,
                null,
                null,
                64,
                null,
                null);
    }

    @Test
    void chatBlockingUsesXApiKeyAndAnthropicVersion() throws Exception {
        server.createContext("/anthropic/v1/messages", exchange -> {
            final var authz = exchange.getRequestHeaders().getFirst("authorization");
            reqHeaders.set("authz=" + authz + "|x-api-key:"
                    + exchange.getRequestHeaders().getFirst("x-api-key") + "|anthropic-version:"
                    + exchange.getRequestHeaders().getFirst("anthropic-version"));
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final var body =
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-v4-flash\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        final var result = client().chat(
                        provider("http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages"),
                        request());
        assertEquals("hello", result.message().content());
        assertEquals(6, result.usage().totalTokens());
        final var headers = reqHeaders.get();
        assertEquals("authz=null|x-api-key:sk-test|anthropic-version:2023-06-01", headers);
        final var body = reqBody.get();
        assertTrue(body.contains("\"max_tokens\":64"));
        assertTrue(body.contains("\"system\":\"be concise\""));
        assertTrue(body.contains("\"model\":\"deepseek-v4-flash\""));
    }

    @Test
    void streamEmitsTextAndFinishDeltas() throws Exception {
        final var base = "http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages";
        server.createContext("/anthropic/v1/messages", exchange -> {
            final var sse = String.join(
                    "\n",
                    "event: message_start",
                    "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-v4-flash\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}",
                    "",
                    "event: content_block_start",
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "",
                    "event: content_block_delta",
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}",
                    "",
                    "event: content_block_delta",
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                    "",
                    "event: content_block_stop",
                    "data: {\"type\":\"content_block_stop\",\"index\":0}",
                    "",
                    "event: message_delta",
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}",
                    "",
                    "event: message_stop",
                    "data: {\"type\":\"message_stop\"}",
                    "");
            final var bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        final var streamReq = new ChatCompletionRequest(
                "deepseek-v4-flash",
                List.of(new Message("user", "hi", null, null, null, null)),
                null,
                true,
                null,
                null,
                64,
                null,
                null);
        final var finish = new AtomicReference<String>();
        final var message = client().stream(
                provider(base),
                streamReq,
                delta -> {
                    if (delta.type() == StreamDelta.Type.TEXT) {
                        streamTexts.add(delta.text());
                    } else if (delta.type() == StreamDelta.Type.FINISH) {
                        finish.set(delta.finishReason());
                    }
                },
                () -> false);
        assertEquals(List.of("hel", "lo"), streamTexts);
        assertEquals("end_turn", finish.get());
        assertEquals("hello", message.content());
    }

    @Test
    void modelsListsIds() throws Exception {
        server.createContext("/anthropic/v1/models", exchange -> {
            final var body =
                    "{\"data\":[{\"type\":\"model\",\"id\":\"a-m\",\"display_name\":\"A M\",\"created_at\":\"1970-01-01T00:00:00Z\"},"
                            + "{\"type\":\"model\",\"id\":\"b-m\",\"display_name\":\"B M\",\"created_at\":\"1970-01-01T00:00:00Z\"}]}";
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        final var models = client().models(
                        provider("http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages"));
        assertEquals(List.of("a-m", "b-m"), models.ids());
    }

    @Test
    void modelsReturnsEmptyOnFlavorMissing() throws Exception {
        server.createContext("/anthropic/v1/models", exchange -> {
            final var bytes = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        final var models = client().models(
                        provider("http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages"));
        assertTrue(models.data().isEmpty());
    }

    @Test
    void chatWithToolUseMapsToFunctionCall() throws Exception {
        server.createContext("/anthropic/v1/messages", exchange -> {
            final var body =
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-v4-flash\","
                            + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read\",\"input\":{\"path\":\"/a\"}}],"
                            + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}";
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        final var result = client().chat(
                        provider("http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages"),
                        request());
        assertNotNull(result.message().toolCalls());
        assertEquals(1, result.message().toolCalls().size());
        final var call = result.message().toolCalls().getFirst();
        assertEquals("t1", call.id());
        assertEquals("read", call.function().name());
        assertEquals("{\"path\":\"/a\"}", call.function().arguments());
    }
}
