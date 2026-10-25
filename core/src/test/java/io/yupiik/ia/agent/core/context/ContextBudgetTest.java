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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.llm.ModelsResponse.SourceType;
import io.yupiik.ia.agent.core.llm.ModelsResponse.TokenCount;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {
    private static TokenCount window(final int value) {
        return new TokenCount(value, true, SourceType.CONFIGURED, "test");
    }

    private static TokenCount output(final int value, final boolean absolute) {
        return new TokenCount(
                value, absolute, absolute ? SourceType.DETECTED_ABSOLUTE : SourceType.DETECTED_RELATIVE, "test");
    }

    @Test
    void declaredOutputCapIsReservedFromTheWindow() {
        assertEquals(32_768, ContextBudget.outputReserve(window(262_144), output(32_768, true)));
        assertEquals(229_376, ContextBudget.usableWindow(window(262_144), output(32_768, true)));
    }

    @Test
    void heuristicReserveAppliesWithoutADeclaredCap() {
        assertEquals(20_000, ContextBudget.outputReserve(window(100_000), null));
        assertEquals(80_000, ContextBudget.usableWindow(window(100_000), null));
        assertEquals(1_024, ContextBudget.outputReserve(window(4_096), null));
        assertEquals(3_072, ContextBudget.usableWindow(window(4_096), null));
    }

    @Test
    void declaredCapNotSmallerThanTheWindowFallsBackToTheHeuristic() {
        assertEquals(8_192, ContextBudget.outputReserve(window(32_768), output(32_768, true)));
        assertEquals(24_576, ContextBudget.usableWindow(window(32_768), output(32_768, true)));
        assertEquals(8_192, ContextBudget.outputReserve(window(32_768), output(65_536, true)));
        assertEquals(24_576, ContextBudget.usableWindow(window(32_768), output(65_536, true)));
    }

    @Test
    void usableWindowNeverDropsBelowTheFloor() {
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.usableWindow(window(2_048), output(1_500, true)));
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.usableWindow(null, output(8_000, true)));
        assertEquals(0, ContextBudget.outputReserve(null, output(8_000, true)));
    }

    @Test
    void largeDeclaredCapIsReservedInFullWhenItFits() {
        assertEquals(32_000, ContextBudget.outputReserve(window(128_000), output(32_000, true)));
        assertEquals(96_000, ContextBudget.usableWindow(window(128_000), output(32_000, true)));
    }

    @Test
    void equalInputAndOutputCapsAreNotATrustedPartition() {
        // LiteLLM style metadata: max_input_tokens == max_output_tokens; the cap is a per-side
        // limit, not a hard partition of the window, so the conservative reserve applies
        assertEquals(20_000, ContextBudget.outputReserve(window(131_072), output(131_072, true)));
        assertEquals(111_072, ContextBudget.usableWindow(window(131_072), output(131_072, true)));
    }

    @Test
    void relativeOutputCapsAreHintsNotHardPartitions() {
        // a gateway-derived cap (e.g. max_output_length) is never trusted as a hard partition
        assertEquals(1_024, ContextBudget.outputReserve(window(4_096), output(4_096, false)));
        assertEquals(3_072, ContextBudget.usableWindow(window(4_096), output(4_096, false)));
        assertEquals(20_000, ContextBudget.outputReserve(window(131_072), output(65_536, false)));
        assertEquals(111_072, ContextBudget.usableWindow(window(131_072), output(65_536, false)));
    }
}
