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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ReasoningFormatMode;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ProviderManagerTest {

    @TempDir
    static Path storageHome;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private ProviderManager manager(final Map<String, String> overrides) {
        final CodingAgentConfiguration cfg = TestConfigs.codingAgent(overrides);
        return new ProviderManager(cfg, mapper);
    }

    private ProviderManager twoProviders() {
        return manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "fallback-0"),
                Map.entry("yca.provider.providers.0.models.length", "4"),
                Map.entry("yca.provider.providers.0.models.0.model", "claude"),
                Map.entry("yca.provider.providers.0.models.0.behavior.outputTokens", "1000"),
                Map.entry("yca.provider.providers.0.models.0.behavior.reasoningEffort", "low"),
                Map.entry("yca.provider.providers.0.models.0.behavior.systemPromptMaxChars", "4000"),
                Map.entry("yca.provider.providers.0.models.0.behavior.plateauRounds", "4"),
                Map.entry("yca.provider.providers.0.models.0.behavior.maxIterations", "80"),
                Map.entry("yca.provider.providers.0.models.0.behavior.repeatSuccessThreshold", "2"),
                Map.entry("yca.provider.providers.0.models.1.model", "claude-sonnet-5"),
                Map.entry("yca.provider.providers.0.models.1.behavior.outputTokens", "2000"),
                Map.entry("yca.provider.providers.0.models.1.behavior.reasoningEffort", "high"),
                Map.entry("yca.provider.providers.0.models.1.behavior.systemPromptMaxChars", "9000"),
                Map.entry("yca.provider.providers.0.models.1.behavior.plateauRounds", "6"),
                Map.entry("yca.provider.providers.0.models.1.behavior.maxIterations", "120"),
                Map.entry("yca.provider.providers.0.models.1.behavior.repeatSuccessThreshold", "6"),
                Map.entry("yca.provider.providers.0.models.2.model", "haiku"),
                Map.entry("yca.provider.providers.0.models.2.behavior.outputTokens", "-1"),
                Map.entry("yca.provider.providers.0.models.2.behavior.reasoningEffort", ""),
                Map.entry("yca.provider.providers.0.models.2.behavior.systemPromptMaxChars", "-1"),
                Map.entry("yca.provider.providers.0.models.3.model", "deepseek-v4-flash"),
                Map.entry("yca.provider.providers.0.models.3.contextWindow", "1000000"),
                Map.entry("yca.provider.providers.1.id", "p1"),
                Map.entry("yca.provider.providers.1.model", "fallback-1")));
    }

    @Test
    void defaultVariantIsNullWithoutConfiguration() {
        final var manager = twoProviders();
        final var provider = manager.providers().get(0);
        assertNull(manager.defaultVariant(provider, "claude"));
        // blank provider tuning counts as unset as well
        final var withBlankTuning = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "m"),
                Map.entry("yca.provider.providers.0.tuning.defaultVariant", "")));
        assertNull(withBlankTuning.defaultVariant(withBlankTuning.providers().get(0), "m"));
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
    void contextWindowOverrideFollowsExactContainsAndProviderFallback() {
        final var manager = twoProviders();
        final var provider = manager.providers().get(0);
        // per-model override wins over the provider value
        assertEquals(1_000_000, manager.contextWindowOverride(provider, "deepseek-v4-flash"));
        // contains fallback: a configured "deepseek" entry matches "deepseek-v4-flash"
        final var withContains = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "deepseek-v4-flash"),
                Map.entry("yca.provider.providers.0.contextWindow", "64000"),
                Map.entry("yca.provider.providers.0.models.length", "1"),
                Map.entry("yca.provider.providers.0.models.0.model", "deepseek"),
                Map.entry("yca.provider.providers.0.models.0.contextWindow", "1000000")));
        final var pContains = withContains.providers().get(0);
        assertEquals(1_000_000, withContains.contextWindowOverride(pContains, "deepseek-v4-flash"));
        // no per-model entry for this model -> provider value
        // forces a provider contextWindow to verify the fallback
        final var withProviderWindow = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "gpt-5"),
                Map.entry("yca.provider.providers.0.contextWindow", "64000"),
                Map.entry("yca.provider.providers.0.models.length", "1"),
                Map.entry("yca.provider.providers.0.models.0.model", "deepseek"),
                Map.entry("yca.provider.providers.0.models.0.contextWindow", "1000000")));
        final var pWithModel = withProviderWindow.providers().get(0);
        assertEquals(1_000_000, withProviderWindow.contextWindowOverride(pWithModel, "deepseek-v4-flash"));
        // model has no entry -> provider window wins
        assertEquals(64_000, withProviderWindow.contextWindowOverride(pWithModel, "gpt-5"));
        // unset provider + unset model -> 0 (unset)
        final var noWindow = manager(Map.of("yca.storageHome", storageHome.toString(), "yca.provider.model", "m"));
        final var pNoWindow = noWindow.providers().get(0);
        assertEquals(0, noWindow.contextWindowOverride(pNoWindow, "m"));
    }

    @Test
    void contextWindowOverrideZeroMeansUnsetAndFallsThrough() {
        final var manager = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "m"),
                Map.entry("yca.provider.providers.0.contextWindow", "32000"),
                Map.entry("yca.provider.providers.0.models.length", "1"),
                Map.entry("yca.provider.providers.0.models.0.model", "m"),
                // not set -> falls through to provider
                Map.entry("yca.provider.providers.0.models.0.style.color", "#fff")));
        final var provider = manager.providers().get(0);
        assertEquals(32_000, manager.contextWindowOverride(provider, "m"));
    }

    @Test
    void plateauRoundsAndMaxIterationsFollowExactThenContainsAndIgnoreInvalid() {
        final var manager = twoProviders();
        final var provider = manager.providers().get(0);
        // exact match wins over the earlier contains entry
        assertEquals(6, manager.plateauRounds(provider, "claude-sonnet-5"));
        // contains fallback follows the list order
        assertEquals(4, manager.plateauRounds(provider, "claude-opus-4"));
        // zero/negative override is ignored (falls back to 0 = unset)
        assertEquals(0, manager.plateauRounds(provider, "haiku-4-5"));
        // unlisted model returns 0 = no override
        assertEquals(0, manager.plateauRounds(provider, "gpt-5"));
        // same cascade for the iteration window cap
        assertEquals(120, manager.maxIterationsOverride(provider, "claude-sonnet-5"));
        assertEquals(80, manager.maxIterationsOverride(provider, "claude-opus-4"));
        assertEquals(0, manager.maxIterationsOverride(provider, "haiku-4-5"));
        assertEquals(0, manager.maxIterationsOverride(provider, "gpt-5"));
        // same cascade for the doom-loop success threshold
        assertEquals(6, manager.repeatSuccessThreshold(provider, "claude-sonnet-5"));
        assertEquals(2, manager.repeatSuccessThreshold(provider, "claude-opus-4"));
        assertEquals(0, manager.repeatSuccessThreshold(provider, "haiku-4-5"));
        assertEquals(0, manager.repeatSuccessThreshold(provider, "gpt-5"));
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

    private ProviderManager tuningProvider() {
        return manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "m"),
                Map.entry("yca.provider.providers.0.providerSpecificOptions", "{\"echo\":1}"),
                Map.entry("yca.provider.providers.0.tuning.reasoningEffort", "medium"),
                Map.entry("yca.provider.providers.0.tuning.defaultVariant", "balanced"),
                Map.entry("yca.provider.providers.0.tuning.outputTokens", "4096"),
                Map.entry("yca.provider.providers.0.tuning.systemPromptMaxChars", "5000"),
                Map.entry("yca.provider.providers.0.tuning.plateauRounds", "3"),
                Map.entry("yca.provider.providers.0.tuning.maxIterations", "60"),
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
                Map.entry("yca.provider.providers.0.models.0.behavior.defaultVariant", "fast"),
                Map.entry("yca.provider.providers.0.models.0.behavior.plateauRounds", "4"),
                Map.entry("yca.provider.providers.0.models.0.behavior.maxIterations", "90"),
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
        assertEquals(3, manager.plateauRounds(provider, "gpt-5"));
        assertEquals(60, manager.maxIterationsOverride(provider, "gpt-5"));
        assertEquals(2, manager.variants(provider, "gpt-5").size());
        assertEquals("balanced", manager.defaultVariant(provider, "gpt-5"));
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
        // per-model behavior.plateauRounds / maxIterations win over the provider tuning
        assertEquals(4, manager.plateauRounds(provider, "claude-sonnet-5"));
        assertEquals(90, manager.maxIterationsOverride(provider, "claude-sonnet-5"));
        // provider-level (top-level) providerSpecificOptions applies for an unlisted model
        assertEquals("{\"echo\":1}", manager.providerSpecificOptions(provider, "gpt-5"));
        // a per-model behavior without variants falls back to the provider-level variants
        assertEquals(2, manager.variants(provider, "claude-sonnet-5").size());
        // per-model behavior.defaultVariant wins over the provider-level tuning.defaultVariant
        assertEquals("fast", manager.defaultVariant(provider, "claude-sonnet-5"));
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
    void repairToolCallArgumentsCascade() {
        // behavior wins over provider tuning
        final var withBehaviorEnabled = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "qwen3.8-27b"),
                Map.entry("yca.provider.providers.0.tuning.repairToolCallArguments", "disabled"),
                Map.entry("yca.provider.providers.0.models.length", "1"),
                Map.entry("yca.provider.providers.0.models.0.model", "qwen3*"),
                Map.entry("yca.provider.providers.0.models.0.behavior.repairToolCallArguments", "enabled")));
        final var provider = withBehaviorEnabled.providers().get(0);
        assertTrue(
                withBehaviorEnabled.repairToolCallArguments(provider, "qwen3.8-27b"),
                "model behavior enabled must win over provider disabled");

        // provider tuning wins over the auto heuristic when the model is not a qwen 27B
        final var withProviderEnabled = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "gpt-5-mini"),
                Map.entry("yca.provider.providers.0.tuning.repairToolCallArguments", "enabled")));
        final var provider2 = withProviderEnabled.providers().get(0);
        assertTrue(
                withProviderEnabled.repairToolCallArguments(provider2, "gpt-5-mini"),
                "provider enabled must apply to any model");

        // provider disabled wins over auto
        final var withProviderDisabled = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "qwen3.8-27b"),
                Map.entry("yca.provider.providers.0.tuning.repairToolCallArguments", "disabled")));
        final var provider3 = withProviderDisabled.providers().get(0);
        assertFalse(
                withProviderDisabled.repairToolCallArguments(provider3, "qwen3.8-27b"),
                "provider disabled must win over auto qwen 27B");

        // no config at all: auto enables for a Qwen 27B model and for OpenAI compatible dialects
        final var autoOnly = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "qwen3.8-27b")));
        final var provider4 = autoOnly.providers().get(0);
        assertTrue(autoOnly.repairToolCallArguments(provider4, "qwen3.8-27b"), "auto must enable for qwen 27B");
        // a non-Qwen model on an OpenAI compatible (default OpenAI v1) provider is repaired too
        assertTrue(
                autoOnly.repairToolCallArguments(provider4, "gpt-5-mini"),
                "auto must enable for a non-qwen-27B model on an OpenAI compatible provider");

        // the explicitly non-OpenAI anthropic dialect stays opted out, an OpenAI completions one is on
        final var dialects = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "a"),
                Map.entry("yca.provider.providers.0.model", "claude-3-7-sonnet"),
                Map.entry("yca.provider.providers.0.api", "anthropic"),
                Map.entry("yca.provider.providers.1.id", "c"),
                Map.entry("yca.provider.providers.1.model", "gpt-5-mini"),
                Map.entry("yca.provider.providers.1.api", "completions")));
        final var anthropicProvider = dialects.providers().get(0);
        assertFalse(
                dialects.repairToolCallArguments(anthropicProvider, "claude-3-7-sonnet"),
                "auto must stay disabled for the non-OpenAI anthropic dialect");
        final var completionsProvider = dialects.providers().get(1);
        assertTrue(
                dialects.repairToolCallArguments(completionsProvider, "gpt-5-mini"),
                "auto must enable for an explicit completions (OpenAI) dialect");
    }

    @Test
    void inBandToolCallsCascade() {
        // behavior wins over provider tuning
        final var withBehaviorEnabled = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "deepseek-v4"),
                Map.entry("yca.provider.providers.0.tuning.inBandToolCalls", "disabled"),
                Map.entry("yca.provider.providers.0.models.length", "1"),
                Map.entry("yca.provider.providers.0.models.0.model", "deepseek*"),
                Map.entry("yca.provider.providers.0.models.0.behavior.inBandToolCalls", "enabled")));
        final var provider = withBehaviorEnabled.providers().get(0);
        assertTrue(
                withBehaviorEnabled.inBandToolCalls(provider, "deepseek-v4"),
                "model behavior enabled must win over provider disabled");

        // provider tuning wins over auto
        final var withProviderDisabled = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "deepseek-v4"),
                Map.entry("yca.provider.providers.0.tuning.inBandToolCalls", "disabled")));
        final var provider2 = withProviderDisabled.providers().get(0);
        assertFalse(
                withProviderDisabled.inBandToolCalls(provider2, "deepseek-v4"), "provider disabled must win over auto");

        // no config at all: auto enables (extract when no native call was produced)
        final var autoOnly = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "gpt-5-mini")));
        final var provider3 = autoOnly.providers().get(0);
        assertTrue(autoOnly.inBandToolCalls(provider3, "gpt-5-mini"), "auto must enable extraction by default");
    }

    @Test
    void reasoningFormatCascadeFollowsBehaviorThenProviderThenAuto() {
        // behavior wins over provider tuning
        final var withBehavior = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "deepseek-v4"),
                Map.entry("yca.provider.providers.0.tuning.reasoningFormat", "qwen"),
                Map.entry("yca.provider.providers.0.models.length", "1"),
                Map.entry("yca.provider.providers.0.models.0.model", "deepseek*"),
                Map.entry("yca.provider.providers.0.models.0.behavior.reasoningFormat", "deepseek_r1")));
        final var provider = withBehavior.providers().get(0);
        assertEquals(
                ReasoningFormatMode.DEEPSEEK_R1,
                withBehavior.reasoningFormat(provider, "deepseek-v4"),
                "model behavior must win over provider tuning");

        // provider tuning applies when the model behavior is unset
        final var withProvider = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "qwen3-coder"),
                Map.entry("yca.provider.providers.0.tuning.reasoningFormat", "qwen")));
        final var provider2 = withProvider.providers().get(0);
        assertEquals(
                ReasoningFormatMode.QWEN,
                withProvider.reasoningFormat(provider2, "qwen3-coder"),
                "provider tuning must apply when no model behavior is set");

        // no config at all: auto
        final var autoOnly = manager(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "p0"),
                Map.entry("yca.provider.providers.0.model", "gpt-5-mini")));
        final var provider3 = autoOnly.providers().get(0);
        assertEquals(ReasoningFormatMode.AUTO, autoOnly.reasoningFormat(provider3, "gpt-5-mini"));
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
