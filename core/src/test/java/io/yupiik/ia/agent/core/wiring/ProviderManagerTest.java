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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderManagerTest {

    @TempDir
    static Path storageHome;

    private static ProviderManager manager(final Map<String, String> overrides) {
        final CodingAgentConfiguration cfg = TestConfigs.codingAgent(overrides);
        return new ProviderManager(cfg, new JsonMapperImpl(List.of(), key -> Optional.empty()));
    }

    private static ProviderManager twoProviders() {
        return manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "fallback-0"),
                Map.entry("yca.provider.providers.0.models.length", "3"),
                Map.entry("yca.provider.providers.0.models.0.model", "claude"),
                Map.entry("yca.provider.providers.0.models.0.behavior.outputTokens", "1000"),
                Map.entry("yca.provider.providers.0.models.0.behavior.reasoningEffort", "low"),
                Map.entry("yca.provider.providers.0.models.0.behavior.systemPromptMaxChars", "4000"),
                Map.entry("yca.provider.providers.0.models.1.model", "claude-sonnet-5"),
                Map.entry("yca.provider.providers.0.models.1.behavior.outputTokens", "2000"),
                Map.entry("yca.provider.providers.0.models.1.behavior.reasoningEffort", "high"),
                Map.entry("yca.provider.providers.0.models.1.behavior.systemPromptMaxChars", "9000"),
                Map.entry("yca.provider.providers.0.models.2.model", "haiku"),
                Map.entry("yca.provider.providers.0.models.2.behavior.outputTokens", "-1"),
                Map.entry("yca.provider.providers.0.models.2.behavior.reasoningEffort", ""),
                Map.entry("yca.provider.providers.0.models.2.behavior.systemPromptMaxChars", "-1"),
                Map.entry("yca.provider.providers.1.id", "p1"),
                Map.entry("yca.provider.providers.1.model", "fallback-1")));
    }

    @Test
    void providerPositionIsZeroForFlatDefaultAndOnePlusIndexOtherwise() {
        final var flat = manager(Map.of("yca.provider.model", "m"));
        assertEquals(0, flat.providerPosition(flat.providers().get(0)));

        final var manager = twoProviders();
        assertEquals(1, manager.providerPosition(manager.providers().get(0)));
        assertEquals(2, manager.providerPosition(manager.providers().get(1)));
        assertEquals(0, manager.providerPosition(null));
    }

    @Test
    void outputTokensExactWinsOverContainsAndInvalidIsIgnored() {
        final var manager = twoProviders();
        final var provider = manager.providers().get(0);
        // exact match wins over the earlier contains entry ("claude" contains match is listed first)
        assertEquals(2000, manager.outputTokensOverride(provider, "claude-sonnet-5"));
        // contains fallback follows the list order
        assertEquals(1000, manager.outputTokensOverride(provider, "claude-opus-4"));
        // zero/negative override is ignored
        assertEquals(0, manager.outputTokensOverride(provider, "haiku-4-5"));
        // unlisted model
        assertEquals(0, manager.outputTokensOverride(provider, "gpt-5"));
    }

    @Test
    void systemPromptMaxCharsIsExactThenContainsAndIgnoresInvalid() {
        final var manager = twoProviders();
        final var provider = manager.providers().get(0);
        // exact match wins over the earlier contains entry
        assertEquals(9000, manager.systemPromptMaxChars(provider, "claude-sonnet-5"));
        // contains fallback follows the list order
        assertEquals(4000, manager.systemPromptMaxChars(provider, "claude-opus-4"));
        // zero/negative override is ignored (falls back to 0 = unset)
        assertEquals(0, manager.systemPromptMaxChars(provider, "haiku-4-5"));
        // unlisted model returns 0 = no override
        assertEquals(0, manager.systemPromptMaxChars(provider, "gpt-5"));
    }

    @Test
    void modelIndexMatchesExactThenContains() {
        final var manager = twoProviders();
        final var provider = manager.providers().get(0);
        assertEquals(1, manager.modelIndex(provider, "claude-sonnet-5"));
        assertEquals(0, manager.modelIndex(provider, "claude-opus-4"));
        assertEquals(-1, manager.modelIndex(provider, "gpt-5"));
    }

    @Test
    void reasoningEffortIsExactThenContainsAndIgnoresBlank() {
        final var manager = twoProviders();
        final var provider = manager.providers().get(0);
        assertEquals("high", manager.reasoningEffort(provider, "claude-sonnet-5"));
        assertEquals("low", manager.reasoningEffort(provider, "claude-opus-4"));
        assertNull(manager.reasoningEffort(provider, "haiku-4-5"));
        assertNull(manager.reasoningEffort(provider, "gpt-5"));
    }

    @Test
    void providerOptionsAreCachedPerProviderInstance() {
        final var manager = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "m"),
                Map.entry("yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}")));
        final var provider = manager.providers().get(0);
        final var first = manager.providerOptions(provider);
        final var second = manager.providerOptions(provider);
        assertSame(first, second, "parsed providerSpecificOptions must be cached, not re-parsed per request");
        assertEquals(0.2, ((Number) first.get("temperature")).doubleValue());
    }

    private static ProviderManager tuningProvider() {
        return manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "m"),
                Map.entry("yca.provider.providers.0.providerSpecificOptions", "{\"echo\":1}"),
                Map.entry("yca.provider.providers.0.tuning.reasoningEffort", "medium"),
                Map.entry("yca.provider.providers.0.tuning.outputTokens", "4096"),
                Map.entry("yca.provider.providers.0.tuning.systemPromptMaxChars", "5000"),
                Map.entry("yca.provider.providers.0.tuning.providerSpecificOptions", "{\"frequency_penalty\":0.5}"),
                Map.entry("yca.provider.providers.0.tuning.variants.length", "2"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7"),
                Map.entry("yca.provider.providers.0.tuning.variants.0.top_p", "0.9"),
                Map.entry("yca.provider.providers.0.tuning.variants.1.name", "reasoning"),
                Map.entry("yca.provider.providers.0.tuning.variants.1.reasoningEffort", "high"),
                Map.entry("yca.provider.providers.0.models.length", "1"),
                Map.entry("yca.provider.providers.0.models.0.model", "claude-sonnet-5"),
                Map.entry("yca.provider.providers.0.models.0.behavior.reasoningEffort", "low"),
                Map.entry(
                        "yca.provider.providers.0.models.0.behavior.providerSpecificOptions",
                        "{\"temperature\":0.2}")));
    }

    @Test
    void tuningFallsBackToProviderLevelWhenNoModelEntryMatches() {
        final var manager = tuningProvider();
        final var provider = manager.providers().get(0);
        final var tuning = manager.tuning(provider, "gpt-5");
        assertEquals("medium", tuning.reasoningEffort());
        assertEquals(4096, tuning.outputTokens());
        assertEquals("medium", manager.reasoningEffort(provider, "gpt-5"));
        assertEquals(4096, manager.outputTokensOverride(provider, "gpt-5"));
        assertEquals(5000, manager.systemPromptMaxChars(provider, "gpt-5"));
        assertEquals(2, manager.variants(provider, "gpt-5").size());
    }

    @Test
    void perModelBehaviorWinsOverProviderLevelTuning() {
        final var manager = tuningProvider();
        final var provider = manager.providers().get(0);
        final var tuning = manager.tuning(provider, "claude-sonnet-5");
        assertEquals("low", tuning.reasoningEffort());
        assertEquals("low", manager.reasoningEffort(provider, "claude-sonnet-5"));
        // per-model providerSpecificOptions wins over the provider-level one
        assertEquals("{\"temperature\":0.2}", manager.providerSpecificOptions(provider, "claude-sonnet-5"));
        // provider-level (top-level) providerSpecificOptions applies for an unlisted model
        assertEquals("{\"echo\":1}", manager.providerSpecificOptions(provider, "gpt-5"));
    }

    @Test
    void tuningOptionsAreParsedAndCached() {
        final var manager = tuningProvider();
        final var provider = manager.providers().get(0);
        final var tuning = manager.tuning(provider, "gpt-5");
        // tuning-level providerSpecificOptions is what ChatClient merges per-request
        assertEquals(0.5, ((Number) manager.tuningOptions(tuning).get("frequency_penalty")).doubleValue());
        final var first = manager.tuningOptions(tuning);
        final var second = manager.tuningOptions(tuning);
        assertSame(first, second, "parsed tuning providerSpecificOptions must be cached");
    }

    @Test
    void variantsExposeWireMappedFields() {
        final var manager = tuningProvider();
        final var provider = manager.providers().get(0);
        final var variants = manager.variants(provider, "gpt-5");
        assertEquals("balanced", variants.get(0).name());
        assertEquals(0.7, variants.get(0).temperature());
        assertEquals(0.9, variants.get(0).topP());
        assertEquals("high", variants.get(1).reasoningEffort());
    }
}
