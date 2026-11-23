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
package io.yupiik.ia.agent.core.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ModelStyleServiceTest {

    @TempDir
    Path storageHome;

    private JsonMapper mapper;
    private ChatClient chatClient;
    private final List<ExtendedHttpClient> httpClients = new ArrayList<>();
    private final List<ChatClient> chatClients = new ArrayList<>();
    private ModelStyleService service;
    private ProviderManager providerManager;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @AfterEach
    void tearDown() {
        chatClients.forEach(ChatClient::close);
        httpClients.forEach(ExtendedHttpClient::close);
    }

    private ChatClient client(final CodingAgentConfiguration configuration, final ProviderManager providers) {
        final var httpClient =
                new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        httpClients.add(httpClient);
        final var client = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), httpClient);
        chatClients.add(client);
        return client;
    }

    private void init() {
        final CodingAgentConfiguration cfg = TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "tone"),
                Map.entry("yca.provider.providers.0.model", "claude-opus-4"),
                Map.entry("yca.provider.providers.0.color", "#00ff00"),
                Map.entry("yca.provider.providers.0.models.length", "3"),
                Map.entry("yca.provider.providers.0.models.0.model", "claude-opus-4"),
                Map.entry("yca.provider.providers.0.models.1.model", "claude-sonnet-5"),
                Map.entry("yca.provider.providers.0.models.2.model", "claude-haiku-4-5"),
                Map.entry("yca.provider.providers.0.models.2.style.color", "#ff00ff"),
                Map.entry("yca.provider.providers.0.models.2.style.bold", "false"),
                Map.entry("yca.provider.providers.1.id", "plain"),
                Map.entry("yca.provider.providers.1.model", "gpt-5"),
                Map.entry("yca.provider.providers.1.models.length", "1"),
                Map.entry("yca.provider.providers.1.models.0.model", "gpt-5")));
        providerManager = new ProviderManager(cfg, mapper);
        chatClient = client(cfg, providerManager);
        service = new ModelStyleService(cfg, chatClient);
    }

    @Test
    void firstTwoListedModelsAreBoldByDefault() {
        init();
        final var provider = providerManager.providers().get(0);
        assertTrue(service.resolve(provider, "claude-opus-4", true).bold());
        assertTrue(service.resolve(provider, "claude-sonnet-5", true).bold());
        assertFalse(service.resolve(provider, "claude-haiku-4-5", true).bold());
    }

    @Test
    void explicitModelColorAndBoldWin() {
        init();
        final var provider = providerManager.providers().get(0);
        final var style = service.resolve(provider, "claude-haiku-4-5", true);
        assertTrue(isMagenta(style.hex()), "explicit color should drive the tone: " + style.hex());
        assertFalse(style.bold());
    }

    @Test
    void modelColorWinsOverProviderTone() {
        init();
        final var provider = providerManager.providers().get(0);
        assertTrue(isGreen(service.resolve(provider, "claude-opus-4", true).hex()));
        assertTrue(isMagenta(service.resolve(provider, "claude-haiku-4-5", true).hex()));
    }

    @Test
    void containsMatchingAppliesTheStyle() {
        init();
        final var provider = providerManager.providers().get(0);
        final var style = service.resolve(provider, "claude-haiku-4-5-20251001", true);
        assertTrue(isMagenta(style.hex()), "contains match should apply the model style: " + style.hex());
        assertFalse(style.bold());
    }

    @Test
    void unlistedModelsAreBoldAndDeterministic() {
        init();
        final var provider = providerManager.providers().get(0);
        assertTrue(service.resolve(provider, "some-unknown-model", true).bold());
        assertNotEquals(
                service.resolve(provider, "some-unknown-model", true).hex(),
                service.resolve(provider, "another-unknown-model", true).hex());
    }

    @Test
    void contrastIsAdjustedAgainstTheBackground() {
        init();
        final var provider = providerManager.providers().get(0);
        assertNotEquals(
                service.resolve(provider, "claude-opus-4", true).hex(),
                service.resolve(provider, "claude-opus-4", false).hex());
    }

    @Test
    void currentUsesTheChatClientModelAndPersistedTheme() {
        init();
        final var provider = providerManager.providers().get(0);
        final var current = service.current();
        assertEquals(service.resolve(provider, "claude-opus-4", true).hex(), current.hex());
        assertEquals(service.resolve(provider, "claude-opus-4", true).bold(), current.bold());
    }

    @Test
    void ansiPrefixCarriesTrueColorAndOptionalBold() {
        init();
        final var provider = providerManager.providers().get(0);
        final var boldStyle = service.resolve(provider, "claude-sonnet-5", true);
        assertTrue(boldStyle.ansiPrefix().contains("\u001b[38;2;"));
        assertTrue(boldStyle.ansiPrefix().contains("\u001b[1m"));
        final var plainStyle = service.resolve(provider, "claude-haiku-4-5", true);
        assertFalse(plainStyle.ansiPrefix().contains("\u001b[1m"));
    }

    private void initValueBased() {
        final CodingAgentConfiguration cfg = TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "alpha"),
                Map.entry("yca.provider.providers.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.length", "2"),
                Map.entry("yca.provider.providers.0.models.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.1.model", "model-b"),
                Map.entry("yca.provider.providers.1.id", "beta"),
                Map.entry("yca.provider.providers.1.model", "model-a"),
                Map.entry("yca.provider.providers.1.models.length", "1"),
                Map.entry("yca.provider.providers.1.models.0.model", "model-a")));
        providerManager = new ProviderManager(cfg, mapper);
        chatClient = client(cfg, providerManager);
        service = new ModelStyleService(cfg, chatClient);
    }

    @Test
    void idDerivedColorIsDeterministicAcrossCalls() {
        initValueBased();
        final var provider = providerManager.providers().get(0);
        assertEquals(
                service.resolve(provider, "model-a", true).hex(),
                service.resolve(provider, "model-a", true).hex(),
                "same (provider, model) must resolve to the same color");
    }

    @Test
    void differentProviderIdsProduceDifferentFamilyHues() {
        initValueBased();
        final var alpha = providerManager.providers().get(0);
        final var beta = providerManager.providers().get(1);
        assertNotEquals(
                service.resolve(alpha, "model-a", true).hex(),
                service.resolve(beta, "model-a", true).hex(),
                "two providers sharing a model name should not collide on the family hue");
    }

    @Test
    void differentModelsUnderOneProviderProduceDistinctShades() {
        initValueBased();
        final var provider = providerManager.providers().get(0);
        assertNotEquals(
                service.resolve(provider, "model-a", true).hex(),
                service.resolve(provider, "model-b", true).hex(),
                "distinct model names under one provider should get distinguishable shades");
    }

    @Test
    void listedModelColorIsInvariantToListPosition() {
        final CodingAgentConfiguration first = TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "alpha"),
                Map.entry("yca.provider.providers.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.length", "3"),
                Map.entry("yca.provider.providers.0.models.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.1.model", "model-b"),
                Map.entry("yca.provider.providers.0.models.2.model", "model-c")));
        final var firstManager = new ProviderManager(first, mapper);
        final var firstClient = client(first, firstManager);
        final var firstService = new ModelStyleService(first, firstClient);
        final var ax = firstService
                .resolve(firstManager.providers().get(0), "model-c", true)
                .hex();

        final CodingAgentConfiguration reordered = TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "alpha"),
                Map.entry("yca.provider.providers.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.length", "3"),
                Map.entry("yca.provider.providers.0.models.0.model", "model-c"),
                Map.entry("yca.provider.providers.0.models.1.model", "model-b"),
                Map.entry("yca.provider.providers.0.models.2.model", "model-a")));
        final var reorderedManager = new ProviderManager(reordered, mapper);
        final var reorderedClient = client(reordered, reorderedManager);
        final var reorderedService = new ModelStyleService(reordered, reorderedClient);
        final var cx = reorderedService
                .resolve(reorderedManager.providers().get(0), "model-c", true)
                .hex();

        assertEquals(ax, cx, "reordering the models list must not change a model's color");
    }

    private static int channel(final String hex, final int offset) {
        return Integer.parseInt(hex.substring(1 + offset * 2, 3 + offset * 2), 16);
    }

    private static boolean isMagenta(final String hex) {
        return channel(hex, 0) > 180 && channel(hex, 2) > 180 && channel(hex, 1) < 80;
    }

    private static boolean isGreen(final String hex) {
        return channel(hex, 1) > 180 && channel(hex, 0) < 160 && channel(hex, 2) < 160;
    }
}
