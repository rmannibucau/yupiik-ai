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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HintsBarTest {
    @Test
    void narrowWidthDropsToCoreSubset() {
        final var wide = HintsBar.segments(false, false, 200);
        final var narrow = HintsBar.segments(false, false, 60);
        assertTrue(narrow.size() < wide.size());
        assertEquals(4, narrow.size());
    }

    @Test
    void overlayStateSwitchesSegments() {
        final var overlay = HintsBar.segments(false, true, 200);
        final var idle = HintsBar.segments(false, false, 200);
        assertFalse(overlay.equals(idle));
        assertTrue(overlay.stream().anyMatch(b -> b.key().contains("Esc")));
    }

    @Test
    void busyStateDiffersFromIdle() {
        assertFalse(HintsBar.segments(true, false, 200).equals(HintsBar.segments(false, false, 200)));
    }

    @Test
    void renderProducesStyledLine() {
        final var line = HintsBar.render(false, false, PaletteTest.darkPalette(), 200);
        assertFalse(line.isEmpty());
    }

    @Test
    void renderNeverExceedsWidth() {
        final var line = HintsBar.render(false, false, PaletteTest.darkPalette(), 30);
        assertTrue(line.width() <= 30
                || line.width()
                        <= KeymapCatalog.idleHints().getFirst().key().length()
                                + KeymapCatalog.idleHints().getFirst().label().length()
                                + 1);
    }

    @Test
    void tinyWidthStillShowsOneSegment() {
        final var line = HintsBar.render(false, false, PaletteTest.darkPalette(), 5);
        assertFalse(line.isEmpty());
    }
}
