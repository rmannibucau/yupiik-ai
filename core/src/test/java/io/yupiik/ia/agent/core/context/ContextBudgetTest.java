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

import org.junit.jupiter.api.Test;

class ContextBudgetTest {
    @Test
    void declaredOutputCapIsReservedFromTheWindow() {
        assertEquals(32_768, ContextBudget.outputReserve(262_144, 32_768));
        assertEquals(229_376, ContextBudget.usableWindow(262_144, 32_768));
    }

    @Test
    void heuristicReserveAppliesWithoutADeclaredCap() {
        assertEquals(20_000, ContextBudget.outputReserve(100_000, 0));
        assertEquals(80_000, ContextBudget.usableWindow(100_000, 0));
        assertEquals(1_024, ContextBudget.outputReserve(4_096, 0));
        assertEquals(3_072, ContextBudget.usableWindow(4_096, 0));
    }

    @Test
    void declaredCapNotSmallerThanTheWindowFallsBackToTheHeuristic() {
        assertEquals(8_192, ContextBudget.outputReserve(32_768, 32_768));
        assertEquals(24_576, ContextBudget.usableWindow(32_768, 32_768));
        assertEquals(8_192, ContextBudget.outputReserve(32_768, 65_536));
        assertEquals(24_576, ContextBudget.usableWindow(32_768, 65_536));
    }

    @Test
    void usableWindowNeverDropsBelowTheFloor() {
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.usableWindow(2_048, 1_500));
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.usableWindow(0, 8_000));
        assertEquals(0, ContextBudget.outputReserve(0, 8_000));
    }

    @Test
    void largeDeclaredCapIsReservedInFullWhenItFits() {
        assertEquals(32_000, ContextBudget.outputReserve(128_000, 32_000));
        assertEquals(96_000, ContextBudget.usableWindow(128_000, 32_000));
    }
}
