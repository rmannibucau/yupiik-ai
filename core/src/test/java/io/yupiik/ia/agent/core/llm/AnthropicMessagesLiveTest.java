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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Live smoke test against the FreeInference Anthropic facade (https://freeinference.org/anthropic/v1/messages).
 * Disabled unless {@code YCA_LIVE_TESTS=true}; the API key is read from {@code yca.provider.apiKey} in
 * {@code ~/.yupiik/coding-agent/configuration.json} (see {@link Config#apiKey()}).
 */
class AnthropicMessagesLiveTest {
    private static final String BASE = "https://freeinference.org/anthropic/v1/messages";
    private static final String MODEL = "deepseek-v4-flash";

    @Test
    void liveBlockingAndStreamingAndModels() throws Exception {
        final var key = Config.apiKey();
        assumeTrue(
                key != null && !key.isBlank(),
                "no API key, skip living test (set YCA_ANTHROPIC_KEY or the yca.provider.apiKey config)");
        assumeTrue(Boolean.parseBoolean(System.getenv("YCA_LIVE_TESTS")), "set YCA_LIVE_TESTS=true to run live test");

        final var cfg = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", "/tmp/yca-live"),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry("yca.provider.providers.0.baseUrl", BASE),
                Map.entry("yca.provider.providers.0.apiKey", key),
                Map.entry("yca.provider.providers.0.model", MODEL),
                Map.entry("yca.provider.providers.0.api", "anthropic"),
                Map.entry("yca.provider.providers.0.contextWindow", "1000000"),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "30"),
                Map.entry("yca.llm.retriesMaxAttempts", "2"),
                Map.entry("yca.llm.retriesBaseDelayMs", "500"),
                Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                Map.entry("yca.llm.maxConcurrent", "1"),
                Map.entry("yca.llm.retryOnContextOverflow", "true"),
                Map.entry("yca.llm.maxIterations", "250")));
        final var mapper = new JsonMapperImpl(
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
                ignored -> Optional.empty());
        final var client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());
        try {
            final var result = client.chat(new ChatCompletionRequest(
                    MODEL,
                    List.of(new Message("user", "reply with exactly: pong", null, null, null, null)),
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null));
            assertNotNull(result.message());
            assertNotNull(result.message().content());

            final var sb = new StringBuilder();
            final var streamed = client.stream(
                    new ChatCompletionRequest(
                            MODEL,
                            List.of(new Message("user", "reply with exactly: ping", null, null, null, null)),
                            null,
                            true,
                            null,
                            null,
                            64,
                            null,
                            null),
                    delta -> {
                        if (delta instanceof io.yupiik.llm.client.model.LlmStreamEvent.TextDelta(final String text)
                                && text != null) {
                            sb.append(text);
                        }
                    },
                    () -> false);
            assertNotNull(streamed);
            assertTrue(sb.length() > 0, "expected streamed text, got '" + sb + "'");

            final var models = client.models();
            assertNotNull(models);
            assertTrue(models.ids().contains(MODEL), "expected model " + MODEL + " in " + models.ids());

            assertEquals(ApiFlavor.ANTHROPIC, client.flavor());
        } finally {
            client.close();
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
