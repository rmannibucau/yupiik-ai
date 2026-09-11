package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assumeTrue(key != null && !key.isBlank(), "no API key, skip living test (set YCA_ANTHROPIC_KEY or the yca.provider.apiKey config)");
        assumeTrue(Boolean.parseBoolean(System.getenv("YCA_LIVE_TESTS")), "set YCA_LIVE_TESTS=true to run live test");

        final var provider = new ProviderConfiguration(
                "default", BASE, key, MODEL, ApiFlavor.ANTHROPIC, null,
                1000000, -1, List.of(), 30, "", Map.of(), null);
        final var cfg = new CodingAgentConfiguration("/tmp/yca-live", null, null,
                new ProviderListConfiguration(List.of(provider), "", null, null, null, null, null, 1000000, -1, List.of(), 30),
                null, new LlmConfiguration(2, 500, 0, 1, true, 250),
                null, null, null, null, null, null, null, null, null, null, null,
                io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig());
        final var mapper = new JsonMapperImpl(List.of(
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
                new ModelsResponse$Model$FusionJsonCodec()), ignored -> Optional.empty());
        final var client = new ChatClient(cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC());
        try {
            final var result = client.chat(new ChatCompletionRequest(MODEL,
                    List.of(new Message("user", "reply with exactly: pong", null, null, null, null)),
                    null, false, null, null, null, null, null));
            assertNotNull(result.message());
            assertNotNull(result.message().content());

            final var sb = new StringBuilder();
            final var streamed = client.stream(new ChatCompletionRequest(MODEL,
                    List.of(new Message("user", "reply with exactly: ping", null, null, null, null)),
                    null, true, null, null, 64, null, null),
                    delta -> {
                        if (delta.type() == StreamDelta.Type.TEXT && delta.text() != null) {
                            sb.append(delta.text());
                        }
                    }, () -> false);
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