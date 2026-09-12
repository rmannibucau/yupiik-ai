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
package io.yupiik.ia.agent.tui;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.StructuralCodeGraphConfiguration;
import io.yupiik.ia.agent.core.llm.ChatChunk$ChunkChoice$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatChunk$ChunkDelta$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatChunk$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatCompletionResponse$Choice$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ChatCompletionResponse$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.Message$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ModelsResponse$Model$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ResponsesInput$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ResponsesRequest$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ResponsesTool$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FunctionCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.ToolCall$FusionJsonCodec;
import io.yupiik.ia.agent.core.llm.Usage$FusionJsonCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal OpenAI flavored LLM double: serves /v1/chat/completions (SSE when the request
 * streams, plain JSON otherwise), /v1/models and a 404 /v1/responses.
 */
public final class MockLlmServer implements AutoCloseable {
    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String answer = "hello from mock";

    public MockLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            if (body.contains("\"stream\":true")) {
                final var text = answer();
                final var cut = Math.max(1, text.length() / 2);
                final var sse = String.join(
                        "\n",
                        chunk(text.substring(0, cut)),
                        "",
                        chunk(text.substring(cut)),
                        "",
                        "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "",
                        "data: [DONE]",
                        "",
                        "");
                respond(exchange, 200, sse, "text/event-stream");
            } else {
                respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                                + answer()
                                + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "application/json");
            }
        });
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"data\":[{\"id\":\"model-a\",\"owned_by\":\"x\"},{\"id\":\"model-b\",\"owned_by\":\"y\"}]}",
                        "application/json"));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port() + "/v1";
    }

    public String answer() {
        return answer;
    }

    public void answer(final String value) {
        this.answer = value;
    }

    public List<String> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String chunk(final String content) {
        return "data: {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + content + "\"},\"finish_reason\":null}]}";
    }

    public static JsonMapper mapper() {
        return new JsonMapperImpl(
                List.of(
                        new Message$FusionJsonCodec(),
                        new ToolCall$FusionJsonCodec(),
                        new ToolCall$FunctionCall$FusionJsonCodec(),
                        new Usage$FusionJsonCodec(),
                        new ChatCompletionRequest$FusionJsonCodec(),
                        new ChatCompletionResponse$FusionJsonCodec(),
                        new ChatCompletionResponse$Choice$FusionJsonCodec(),
                        new ChatChunk$FusionJsonCodec(),
                        new ChatChunk$ChunkChoice$FusionJsonCodec(),
                        new ChatChunk$ChunkDelta$FusionJsonCodec(),
                        new ResponsesRequest$FusionJsonCodec(),
                        new ResponsesInput$FusionJsonCodec(),
                        new ResponsesTool$FusionJsonCodec(),
                        new ModelsResponse$FusionJsonCodec(),
                        new ModelsResponse$Model$FusionJsonCodec()),
                key -> Optional.empty());
    }

    public static CodingAgentConfiguration configuration(final String baseUrl, final String storageHome) {
        return configuration(baseUrl, storageHome, null, null);
    }

    public static CodingAgentConfiguration configuration(
            final String baseUrl, final String storageHome, final PermissionsConfiguration permissions) {
        return configuration(baseUrl, storageHome, permissions, null);
    }

    public static CodingAgentConfiguration configuration(
            final String baseUrl,
            final String storageHome,
            final PermissionsConfiguration permissions,
            final StructuralCodeGraphConfiguration codeGraph) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", storageHome);
        map.put("yca.provider.providers.length", "1");
        map.put("yca.provider.providers.0.id", "default");
        map.put("yca.provider.providers.0.baseUrl", baseUrl);
        map.put("yca.provider.providers.0.apiKey", "test-key");
        map.put("yca.provider.providers.0.model", "test-model");
        map.put("yca.provider.providers.0.api", "auto");
        map.put("yca.provider.providers.0.contextWindow", "128000");
        map.put("yca.provider.providers.0.timeoutSeconds", "5");
        map.put("yca.llm.retriesMaxAttempts", "1");
        map.put("yca.llm.retriesBaseDelayMs", "1");
        map.put("yca.llm.rateLimitRequestsPerMinute", "0");
        map.put("yca.llm.maxConcurrent", "1");
        map.put("yca.llm.retryOnContextOverflow", "true");
        map.put("yca.llm.maxIterations", "250");
        if (permissions != null) {
            map.put("yca.permissions.mode", permissions.mode().name().toLowerCase());
            map.put("yca.permissions.allow", String.join(",", permissions.allow()));
            map.put("yca.permissions.deny", String.join(",", permissions.deny()));
            map.put("yca.permissions.planBash", String.join(",", permissions.planBash()));
        }
        if (codeGraph != null) {
            map.put("yca.structuralCodeGraph.enabled", String.valueOf(codeGraph.enabled()));
            map.put("yca.structuralCodeGraph.enrich", String.valueOf(codeGraph.enrich()));
            if (codeGraph.baseUrl() != null) {
                map.put("yca.structuralCodeGraph.baseUrl", codeGraph.baseUrl());
            }
            if (codeGraph.apiKey() != null) {
                map.put("yca.structuralCodeGraph.apiKey", codeGraph.apiKey());
            }
            if (codeGraph.model() != null) {
                map.put("yca.structuralCodeGraph.model", codeGraph.model());
            }
            map.put("yca.structuralCodeGraph.maxFileBytes", String.valueOf(codeGraph.maxFileBytes()));
            map.put("yca.structuralCodeGraph.languageExtensions", String.join(",", codeGraph.languageExtensions()));
            map.put("yca.structuralCodeGraph.storeDir", codeGraph.storeDir());
            map.put("yca.structuralCodeGraph.docs", String.valueOf(codeGraph.docs()));
            map.put("yca.structuralCodeGraph.docStoreDir", codeGraph.docStoreDir());
            map.put("yca.structuralCodeGraph.deep", String.valueOf(codeGraph.deep()));
            map.put("yca.structuralCodeGraph.noRefresh", String.valueOf(codeGraph.noRefresh()));
        }
        return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(map.get(key))).get();
    }

    private static void respond(
            final com.sun.net.httpserver.HttpExchange exchange,
            final int status,
            final String body,
            final String contentType) {
        try {
            exchange.getRequestBody().readAllBytes();
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
