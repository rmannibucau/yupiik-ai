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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Live smoke test against the FreeInference OpenAI Responses facade
 * (https://freeinference.org/v1/responses), forcing a tool call while capturing the raw SSE wire
 * (yca.httpClient.debug=true logs each exchange as a curl trace on the {@code yca.http} logger).
 * Disabled unless {@code YCA_LIVE_TESTS=true}.
 *
 * <p>This is a diagnostic-only test: it prints the captured wire and the decoded tool call so the
 * tool `name` arrival (or absence) on the Responses stream can be inspected.
 */
class ResponsesToolCallLiveTest {
    private static final String BASE = "https://freeinference.org/v1/responses";

    @Test
    void liveResponsesToolCallNameOnWire() throws Exception {
        final var key = Config.apiKey();
        assumeTrue(
                key != null && !key.isBlank(),
                "no API key, skip live test (set YCA_ANTHROPIC_KEY or the yca.provider.apiKey config)");
        assumeTrue(Boolean.parseBoolean(System.getenv("YCA_LIVE_TESTS")), "set YCA_LIVE_TESTS=true to run live test");

        final var cfg = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", "/tmp/yca-live"),
                Map.entry("yca.httpClient.debug", "true"),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry("yca.provider.providers.0.baseUrl", BASE),
                Map.entry("yca.provider.providers.0.apiKey", key),
                Map.entry("yca.provider.providers.0.model", "deepseek-v4-flash"),
                Map.entry("yca.provider.providers.0.api", "openai-responses"),
                Map.entry("yca.provider.providers.0.contextWindow", "1000000"),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "30"),
                Map.entry("yca.llm.retriesMaxAttempts", "2"),
                Map.entry("yca.llm.retriesBaseDelayMs", "500"),
                Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                Map.entry("yca.llm.maxConcurrent", "1"),
                Map.entry("yca.llm.retryOnContextOverflow", "true"),
                Map.entry("yca.llm.maxIterations", "1")));
        final var mapper = new JsonMapperImpl(
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
                ignored -> Optional.empty());

        final var logs = new ByteArrayOutputStream();
        final var logger = Logger.getLogger("yca.http");
        final var disabledParent = logger.getUseParentHandlers();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                try {
                    final var msg = record.getMessage();
                    if (msg != null) {
                        logs.write(msg.getBytes(StandardCharsets.UTF_8));
                        logs.write('\n');
                    }
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
        logger.setUseParentHandlers(false);
        final var previousLevel = logger.getLevel();
        logger.setLevel(java.util.logging.Level.ALL);
        try {
            final var client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());
            try {
                final var tool = Tool.function(
                        "search",
                        "search the web",
                        Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))));
                final var result = client.stream(
                        new ChatCompletionRequest(
                                "deepseek-v4-flash",
                                List.of(new Message(
                                        "user", "call the search tool with q = weather paris", null, null, null, null)),
                                null,
                                true,
                                List.of(tool),
                                null,
                                null,
                                null,
                                null),
                        delta -> {
                            if (delta instanceof io.yupiik.llm.client.model.LlmStreamEvent.TextDelta(final String t)) {
                                System.out.println("LIVE_STREAM_TEXT=[" + t + "]");
                            } else if (delta
                                    instanceof
                                    io.yupiik.llm.client.model.LlmStreamEvent.ToolCallDelta(
                                            final int idx,
                                            final String id,
                                            final String name,
                                            final String args)) {
                                System.out.println("LIVE_STREAM_TOOL_DELTA index=" + idx + " id=[" + id + "] name=["
                                        + name + "] args=[" + args + "]");
                            }
                        });
                assertNotNull(result);
                System.out.println("LIVE_RESULT role=" + result.role() + " content=[" + result.content() + "]");
                final var calls = result.toolCalls();
                if (calls == null || calls.isEmpty()) {
                    System.out.println("LIVE_NO_TOOL_CALL");
                } else {
                    for (final var c : calls) {
                        final var name =
                                c.function() == null ? null : c.function().name();
                        System.out.println("LIVE_TOOL_CALL name=[" + name + "] args=["
                                + (c.function() == null ? null : c.function().arguments()) + "] index=[" + c.index()
                                + "] id=[" + c.id() + "]");
                    }
                }
            } finally {
                client.close();
            }
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(disabledParent);
            logger.setLevel(previousLevel);
            final var trace = logs.toString(StandardCharsets.UTF_8);
            System.out.println(
                    "===== CAPTURED yca.http WIRE (haystack, look for output_item / function_call / name) =====");
            System.out.println(trace);
            System.out.println("===== END WIRE =====");
            assertTrue(
                    trace.contains(BASE), "expected curl trace for " + BASE + " in the yca.http log; got:\n" + trace);
        }
    }

    private static final class Config {
        private static String apiKey() {
            final var env = System.getenv("YCA_ANTHROPIC_KEY");
            if (env != null && !env.isBlank()) {
                return env;
            }
            final var home = System.getenv("YCA_STORAGE_HOME");
            final var file = home != null && !home.isBlank()
                    ? Path.of(home).resolve("configuration.json")
                    : Path.of(System.getProperty("user.home"), ".yupiik", "coding-agent", "configuration.json");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                final var root = new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(), key -> Optional.empty())
                        .fromString(Object.class, Files.readString(file, StandardCharsets.UTF_8));
                return readKey(root);
            } catch (final IOException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        private static String readKey(final Object root) {
            if (!(root instanceof Map<?, ?> map)) {
                return null;
            }
            final var yca = map.get("yca");
            if (!(yca instanceof Map<?, ?> ycaMap)) {
                return null;
            }
            final var provider = ycaMap.get("provider");
            if (!(provider instanceof Map<?, ?> providerMap)) {
                return null;
            }
            final var apiKey = providerMap.get("apiKey");
            return apiKey == null ? null : apiKey.toString();
        }
    }
}
