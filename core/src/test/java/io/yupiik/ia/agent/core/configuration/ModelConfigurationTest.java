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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelConfigurationTest {

    @Test
    void resolvedPruneFallsBackToCompactWhenBlank() {
        assertEquals("compact-model", new ModelConfiguration("compact-model", "").resolvedPrune());
        assertEquals("compact-model", new ModelConfiguration("compact-model", null).resolvedPrune());
    }

    @Test
    void resolvedPruneKeepsItsOwnValue() {
        assertEquals("prune-model", new ModelConfiguration("compact-model", "prune-model").resolvedPrune());
    }

    @Test
    void resolvedPruneCanBeBlankWhenBothAreBlank() {
        assertEquals("", new ModelConfiguration("", "").resolvedPrune());
    }

    @Test
    void variantExposesSamplingFacetsWithWireMappedNames() {
        final var variant = new ModelVariantConfiguration(
                "balanced", 0.7, 0.9, 40.0, 2048, "high", "{\"thinking\":{\"type\":\"enabled\"}}");
        assertEquals("balanced", variant.name());
        assertEquals(0.7, variant.temperature());
        assertEquals(0.9, variant.topP());
        assertEquals(40.0, variant.topK());
        assertEquals(2048, variant.maxTokens());
        assertEquals("high", variant.reasoningEffort());
        assertEquals("{\"thinking\":{\"type\":\"enabled\"}}", variant.extra());
    }

    @Test
    void tuningUnsetNullableFacetsAreNull() {
        final var tuning = new ModelTuningConfiguration("", null, null, List.of(), "", null);
        assertNull(tuning.outputTokens());
        assertNull(tuning.systemPromptMaxChars());
        assertNull(tuning.anthropic());
        assertEquals(List.of(), tuning.variants());
    }

    @Test
    void anthropicSplitBlocksDefaultsToAuto() {
        assertNull(new AnthropicDialectConfiguration(null).explicitSplitBlocks());
        assertNull(new AnthropicDialectConfiguration("auto").explicitSplitBlocks());
        assertNull(new AnthropicDialectConfiguration("").explicitSplitBlocks());
        assertEquals(Boolean.TRUE, new AnthropicDialectConfiguration("true").explicitSplitBlocks());
        assertEquals(Boolean.FALSE, new AnthropicDialectConfiguration("false").explicitSplitBlocks());
        assertNull(new AnthropicDialectConfiguration("whatever").explicitSplitBlocks());
    }
}
